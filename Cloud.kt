package com.example.twirlly

import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Your data in Firebase (Cloud Firestore). Everything lives under users/{your id}, and the security rules
 * only let you read and write it. Only people with a confirmed email can use it.
 * Your state is packed (compressed) into a few small chunks so it fits Firestore's document size limit.
 */
object Cloud {
    private const val CHUNK = 700_000
    private const val MAX_PARTS = 20
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastHash = 0

    private fun db() = FirebaseFirestore.getInstance()

    /** The signed-in user's id, only when the email is confirmed. */
    fun uid(): String? = try {
        FirebaseAuth.getInstance().currentUser?.takeIf { it.isEmailVerified }?.uid
    } catch (e: Exception) { null }

    fun online(): Boolean = uid() != null

    /** Waits a few seconds after the last change, then saves. Does nothing for guests. */
    fun scheduleSync(json: String) {
        if (uid() == null) return
        val h = json.hashCode()
        if (h == lastHash) return
        job?.cancel()
        job = scope.launch {
            delay(6_000)
            if (pushState(json)) lastHash = h
        }
    }

    fun cancelPending() { job?.cancel(); job = null }
    fun markSynced(json: String) { lastHash = json.hashCode() }
    fun forget() { lastHash = 0 }

    private fun gzip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    private fun gunzip(b: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(b)).use { String(it.readBytes(), Charsets.UTF_8) }

    suspend fun pushState(json: String): Boolean {
        val uid = uid() ?: return false
        return try {
            val packed = Base64.encodeToString(gzip(json.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            val parts = packed.chunked(CHUNK)
            if (parts.size > MAX_PARTS) return false
            val col = db().collection("users").document(uid).collection("state")
            val batch = db().batch()
            parts.forEachIndexed { i, p -> batch.set(col.document("c$i"), mapOf("i" to i, "d" to p)) }
            for (i in parts.size until MAX_PARTS) batch.delete(col.document("c$i"))
            batch.set(col.document("meta"), mapOf("parts" to parts.size, "updatedAt" to System.currentTimeMillis(), "v" to 1))
            batch.commit().await()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Your saved data and the time it was saved, or null if nothing is saved yet. */
    suspend fun pullState(): Pair<String, Long>? {
        val uid = uid() ?: return null
        return try {
            val col = db().collection("users").document(uid).collection("state")
            val meta = col.document("meta").get().await()
            if (!meta.exists()) return null
            val parts = (meta.getLong("parts") ?: 0L).toInt()
            val sb = StringBuilder()
            for (i in 0 until parts) {
                val d = col.document("c$i").get().await().getString("d") ?: return null
                sb.append(d)
            }
            Pair(gunzip(Base64.decode(sb.toString(), Base64.NO_WRAP)), meta.getLong("updatedAt") ?: 0L)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves the public profile and claims the username. The rules make a username belong to one person,
     * allow a change only every 7 days (checked with the server clock), and never let the app give itself a badge.
     * Returns a message when it did not work.
     */
    suspend fun saveProfile(p: Profile, changedName: Boolean, changedUser: Boolean, onboarding: Boolean): String? {
        val uid = uid() ?: return null
        return try {
            val users = db().collection("users").document(uid)
            val names = db().collection("usernames")
            val current = users.get().await()
            val oldClaim = current.getString("username")
            if (oldClaim != p.name) {
                val taken = names.document(p.name).get().await()
                if (taken.exists() && taken.getString("uid") != uid) return "That username is already taken."
            }
            val data = hashMapOf<String, Any>(
                "username" to p.name, "displayName" to p.displayName, "bio" to p.bio,
                "isPrivate" to p.isPrivate, "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!current.exists()) {
                data["verified"] = false
                data["createdAt"] = FieldValue.serverTimestamp()
            }
            if (!onboarding && changedName) data["nameChangedAt"] = FieldValue.serverTimestamp()
            if (!onboarding && changedUser) data["usernameChangedAt"] = FieldValue.serverTimestamp()
            db().runBatch { b ->
                if (oldClaim != p.name) {
                    b.set(names.document(p.name), mapOf("uid" to uid))
                    if (!oldClaim.isNullOrBlank()) b.delete(names.document(oldClaim))
                }
                b.set(users, data, SetOptions.merge())
            }.await()
            null
        } catch (e: Exception) {
            "Could not save online. If you changed your name or username recently, wait for the 7 days. Otherwise try again."
        }
    }

    /** Removes everything saved online for this account (the account itself is deleted separately). */
    suspend fun deleteAllData(): String? {
        val uid = uid() ?: return null
        return try {
            val users = db().collection("users").document(uid)
            val col = users.collection("state")
            for (i in 0 until MAX_PARTS) col.document("c$i").delete().await()
            col.document("meta").delete().await()
            val snap = users.get().await()
            val un = snap.getString("username")
            if (un != null) db().collection("usernames").document(un).delete().await()
            users.delete().await()
            null
        } catch (e: Exception) {
            "Could not delete your online data. Please try again."
        }
    }
}
