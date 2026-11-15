package com.example.twirlly

import android.content.Context
import android.util.Patterns
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/** Real accounts: native Google account picker, email and password, email confirmation, password reset. */
object FirebaseAuthProvider : AuthProvider {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    override val isCloud: Boolean get() = true

    private fun friendly(e: Exception): String {
        if (e is FirebaseNetworkException) return "No internet connection. Check your network and try again."
        val code = (e as? FirebaseAuthException)?.errorCode ?: ""
        return when (code) {
            // one message for all of these, so nobody can use the app to find out which emails have accounts
            "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_EMAIL" ->
                "The email or password is not correct."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "This email may already have an account. Try signing in, or reset your password."
            "ERROR_WEAK_PASSWORD" -> "Choose a stronger password."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many tries. Please wait a few minutes."
            "ERROR_USER_DISABLED" -> "This account has been disabled."
            "ERROR_REQUIRES_RECENT_LOGIN" -> "For your safety, please confirm it is you again."
            else -> "Something went wrong. Please try again."
        }
    }

    private fun passwordProblem(email: String, password: String): String? = when {
        !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Enter a valid email address."
        password.length < 8 -> "Use at least 8 characters."
        !password.any { it.isDigit() } || !password.any { it.isLetter() } -> "Use both letters and numbers."
        else -> null
    }

    override suspend fun signIn(email: String, password: String): String? {
        if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) return "Enter a valid email address."
        return try {
            auth.signInWithEmailAndPassword(email.trim(), password).await()
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    override suspend fun signUp(email: String, password: String): String? {
        val problem = passwordProblem(email, password)
        if (problem != null) return problem
        return try {
            val r = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            r.user?.sendEmailVerification()?.await()
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    override suspend fun google(context: Context): String? {
        val (cred, err) = googleCredential(context)
        if (cred == null) return err ?: "Google sign-in did not work."
        return try {
            auth.signInWithCredential(cred).await()
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    private fun tokenOf(c: Credential): String? =
        if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            GoogleIdTokenCredential.createFrom(c.data).idToken
        else null

    /** The native Google account picker (Android Credential Manager). No browser is opened. */
    private suspend fun googleCredential(context: Context): Pair<AuthCredential?, String?> {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resId == 0) return Pair(null, "Google sign-in is not set up yet (the Firebase file is missing).")
        val serverId = context.getString(resId)
        val cm = CredentialManager.create(context)
        try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverId)
                .setAutoSelectEnabled(false)
                .build()
            val r = cm.getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val t = tokenOf(r.credential)
            if (t != null) return Pair(GoogleAuthProvider.getCredential(t, null), null)
        } catch (e: GetCredentialCancellationException) {
            return Pair(null, "Sign-in was cancelled.")
        } catch (e: NoCredentialException) {
            // no account on the phone is offered here, try the full Google sheet below (it can add an account)
        } catch (e: Exception) {
            return Pair(null, "Google sign-in did not work. Please try again.")
        }
        return try {
            val option = GetSignInWithGoogleOption.Builder(serverId).build()
            val r = cm.getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val t = tokenOf(r.credential)
            if (t != null) Pair(GoogleAuthProvider.getCredential(t, null), null) else Pair(null, "Google sign-in did not work.")
        } catch (e: GetCredentialCancellationException) {
            Pair(null, "Sign-in was cancelled.")
        } catch (e: Exception) {
            Pair(null, "Google sign-in did not work. Please try again.")
        }
    }

    override suspend fun sendVerification(): String? {
        return try {
            auth.currentUser?.sendEmailVerification()?.await()
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    override suspend fun isVerified(): Boolean {
        return try {
            auth.currentUser?.reload()?.await()
            auth.currentUser?.isEmailVerified == true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun resetPassword(email: String): String? {
        if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) return "Enter your email address first."
        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            null
        } catch (e: Exception) {
            if (e is FirebaseNetworkException) friendly(e) else null
        }
    }

    override suspend fun reauthenticate(context: Context, password: String?): String? {
        val user = auth.currentUser ?: return "You are not signed in."
        return try {
            if (password != null) {
                val email = user.email ?: return "This account has no email."
                user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
            } else {
                val (cred, err) = googleCredential(context)
                if (cred == null) return err ?: "Could not confirm it is you."
                user.reauthenticate(cred).await()
            }
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    override fun signOut() {
        try { auth.signOut() } catch (_: Exception) {}
    }

    override suspend fun deleteAccount(): String? {
        return try {
            auth.currentUser?.delete()?.await()
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    override fun currentEmail(): String? = try { auth.currentUser?.email } catch (e: Exception) { null }

    override fun usesGoogle(): Boolean = try {
        val ids = auth.currentUser?.providerData?.map { it.providerId } ?: emptyList()
        "google.com" in ids && "password" !in ids
    } catch (e: Exception) { false }
}
