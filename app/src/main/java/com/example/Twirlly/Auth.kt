package com.example.twirlly

import android.content.Context

/**
 * Sign-in hook. Every function returns null on success, or a message to show the user.
 * With Firebase set up, TwirllyApplication switches this to FirebaseAuthProvider. Without it, LocalAuth keeps
 * everything on this phone.
 */
interface AuthProvider {
    suspend fun signIn(email: String, password: String): String?
    suspend fun signUp(email: String, password: String): String?
    suspend fun google(context: Context): String?

    /** True when accounts are online (Firebase). */
    val isCloud: Boolean get() = false
    suspend fun sendVerification(): String? = null
    /** True when the email of the signed-in account is confirmed. Google accounts are always confirmed. */
    suspend fun isVerified(): Boolean = true
    suspend fun resetPassword(email: String): String? = "Password reset needs an online account."
    /** Asks the account owner to prove it is them again: with the password, or with Google when password is null. */
    suspend fun reauthenticate(context: Context, password: String?): String? = "Sign in to an account first."
    fun signOut() {}
    suspend fun deleteAccount(): String? = null
    fun currentEmail(): String? = null
    fun usesGoogle(): Boolean = false
}

/** Default: checks the form only. The password is never stored. */
object LocalAuth : AuthProvider {
    private fun check(email: String, password: String): String? = when {
        !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Enter a valid email address."
        password.length < 6 -> "Password must have at least 6 characters."
        else -> null
    }

    override suspend fun signIn(email: String, password: String): String? = check(email, password)
    override suspend fun signUp(email: String, password: String): String? = check(email, password)
    override suspend fun google(context: Context): String? = "Google sign-in is not connected yet."
}

object Auth {
    var provider: AuthProvider = LocalAuth
}
