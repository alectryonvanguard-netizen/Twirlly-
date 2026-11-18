package com.example.twirlly

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**

* Google Play payments: coin packs (consumable) and Premium (subscription).

* Create these products in Play Console with exactly these ids.
  */
  object Pay {
  
  val COIN_IDS = mapOf(
  70 to "twirlly_coins_70",
  350 to "twirlly_coins_350",
  700 to "twirlly_coins_700",
  1400 to "twirlly_coins_1400",
  3500 to "twirlly_coins_3500",
  7000 to "twirlly_coins_7000"
  )
  
  const val PREMIUM_MONTHLY = "twirlly_premium_monthly"
  const val PREMIUM_YEARLY = "twirlly_premium_yearly"
  
  /** Real prices from Google Play, in the user's own currency. */
  val prices = mutableStateMapOf<String, String>()
  
  private val details = HashMap<String, ProductDetails>()
  private var client: BillingClient? = null
  private var onMessage: ((Boolean, String) -> Unit)? = null
  
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val main = Handler(Looper.getMainLooper())
  
  fun coinId(coins: Int): String? = COIN_IDS[coins]
  
  private fun say(ok: Boolean, msg: String) {
  main.post {
  onMessage?.invoke(ok, msg)
  }
  }
  
  private fun handle(p: Purchase) {
  if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
  
   val id = p.products.firstOrNull() ?: return

 scope.launch {
     val (ok, msg) = CloudPay.verify(id, p.purchaseToken)
     say(ok, msg)
 }
  
  }
  
  fun start(app: Context, onResult: (Boolean, String) -> Unit) {
  onMessage = onResult
  
   if (client != null) return

 val c = BillingClient.newBuilder(app)
     .setListener(
         PurchasesUpdatedListener { result, purchases ->
             if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                 purchases?.forEach { purchase ->
                     handle(purchase)
                 }
             } else if (
                 result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED
             ) {
                 say(false, "The payment did not go through.")
             }
         }
     )
     .enablePendingPurchases(
         PendingPurchasesParams.newBuilder()
             .enableOneTimeProducts()
             .build()
     )
     .build()

 client = c

 c.startConnection(object : BillingClientStateListener {

     override fun onBillingSetupFinished(result: BillingResult) {
         if (result.responseCode != BillingClient.BillingResponseCode.OK) return

         scope.launch {
             load(
                 c,
                 BillingClient.ProductType.INAPP,
                 COIN_IDS.values.toList()
             )

             load(
                 c,
                 BillingClient.ProductType.SUBS,
                 listOf(PREMIUM_MONTHLY, PREMIUM_YEARLY)
             )

             // Restore purchases that were not confirmed previously.
             for (type in listOf(
                 BillingClient.ProductType.INAPP,
                 BillingClient.ProductType.SUBS
             )) {
                 val params = QueryPurchasesParams.newBuilder()
                     .setProductType(type)
                     .build()

                 c.queryPurchasesAsync(
                     params,
                     object : PurchasesResponseListener {
                         override fun onQueryPurchasesResponse(
                             billingResult: BillingResult,
                             purchasesList: MutableList<Purchase>
                         ) {
                             if (
                                 billingResult.responseCode ==
                                 BillingClient.BillingResponseCode.OK
                             ) {
                                 purchasesList.forEach { purchase ->
                                     handle(purchase)
                                 }
                             }
                         }
                     }
                 )
             }
         }
     }

     override fun onBillingServiceDisconnected() {
         // Billing can reconnect when startConnection is called again.
     }
 })
  
  }
  
  private suspend fun load(
  c: BillingClient,
  type: String,
  ids: List<String>
  ) {
  val params = QueryProductDetailsParams.newBuilder()
  .setProductList(
  ids.map { id ->
  QueryProductDetailsParams.Product.newBuilder()
  .setProductId(id)
  .setProductType(type)
  .build()
  }
  )
  .build()
  
   val result = c.queryProductDetails(params)

 result.productDetailsList?.forEach { pd ->
     details[pd.productId] = pd

     val price =
         pd.oneTimePurchaseOfferDetails?.formattedPrice
             ?: pd.subscriptionOfferDetails
                 ?.firstOrNull()
                 ?.pricingPhases
                 ?.pricingPhaseList
                 ?.firstOrNull()
                 ?.formattedPrice

     if (price != null) {
         main.post {
             prices[pd.productId] = price
         }
     }
 }
  
  }
  
  /** Opens the Google Play payment sheet. */
  fun buy(activity: Activity, productId: String): String? {
  
   val c = client
     ?: return "Payments are not ready yet. Try again in a moment."

 val pd = details[productId]
     ?: return "This item is not available yet. It must be created and active in Google Play."

 if (!Cloud.online()) {
     return "Sign in to your account first so your purchase is saved to it."
 }

 val params = BillingFlowParams.ProductDetailsParams.newBuilder()
     .setProductDetails(pd)
     .apply {
         val offer = pd.subscriptionOfferDetails
             ?.firstOrNull()
             ?.offerToken

         if (offer != null) {
             setOfferToken(offer)
         }
     }
     .build()

 val result = c.launchBillingFlow(
     activity,
     BillingFlowParams.newBuilder()
         .setProductDetailsParamsList(listOf(params))
         .build()
 )

 return if (
     result.responseCode == BillingClient.BillingResponseCode.OK
 ) {
     null
 } else {
     "Could not open Google Play."
 }
  
  }
  }
