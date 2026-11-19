package com.example.twirlly

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Money is handled by the server. The app only asks: coins and Premium live in documents that only the
 * Cloud Functions can write (see firestore.rules), so a changed app cannot give itself coins.
 */
object CloudPay {
    private fun fn() = FirebaseFunctions.getInstance()

    /** Sends a Google Play purchase to the server, which checks it with Google and then adds the coins or Premium. */
    suspend fun verify(productId: String, token: String): Pair<Boolean, String> {
        return try {
            val r = fn().getHttpsCallable("verifyPurchase").call(mapOf("productId" to productId, "purchaseToken" to token)).await()
            val m = r.data as? Map<*, *>
            Pair(true, (m?.get("message") as? String) ?: "Purchase complete")
        } catch (e: Exception) {
            Pair(false, "Could not confirm the purchase yet. If you were charged, it will be added shortly.")
        }
    }

    /** When the account was made, from the server clock. Used for the free first week. */
    suspend fun createdAt(): Long {
        val uid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (e: Exception) { null } ?: return 0L
        return try {
            FirebaseFirestore.getInstance().collection("users").document(uid).get().await().getTimestamp("createdAt")?.toDate()?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    suspend fun spend(coins: Int): Boolean = try {
        fn().getHttpsCallable("spendCoins").call(mapOf("coins" to coins)).await()
        true
    } catch (e: Exception) { false }

    /** Live balance and Premium status of the signed-in account. */
    fun listenWallet(onCoins: (Int) -> Unit, onPremium: (Boolean) -> Unit): ListenerRegistration? {
        val uid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (e: Exception) { null } ?: return null
        return FirebaseFirestore.getInstance().collection("users").document(uid).collection("wallet").addSnapshotListener { snap, _ ->
            snap?.documents?.forEach { d ->
                when (d.id) {
                    "main" -> onCoins((d.getLong("coins") ?: 0L).toInt())
                    "premium" -> onPremium(d.getBoolean("active") == true && (d.getLong("until") ?: 0L) > System.currentTimeMillis())
                }
            }
        }
    }
}
