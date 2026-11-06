package com.mundymorning.touchweathertracker.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.mundymorning.touchweathertracker.BuildConfig
import com.mundymorning.touchweathertracker.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PremiumSource { NONE, AD_UNLOCK, AD_FREE_PURCHASE, SUBSCRIPTION }

data class Entitlement(
    val premium: Boolean = false,
    val source: PremiumSource = PremiumSource.NONE,
    val unlockUntilMs: Long = 0L,
    val adViews: Int = 0,
    val adsRequired: Int = Monetization.ADS_REQUIRED,
    val adReady: Boolean = false,
    val adLoading: Boolean = false,
    val billingReady: Boolean = false,
    val adFreePrice: String? = null,
    val subscriptionPrice: String? = null,
    val message: String? = null,
)

/**
 * The whole app is locked unless the user has an entitlement: four completed rewarded ads (24 hours of use), the ad-free purchase, or the
 * subscription. There are no banner ads. Rewarded ads use AdMob with UMP consent; purchases use Google Play Billing.
 * Product ids must exist in Play Console (see docs/MONETIZATION.md). Without them the paywall states plainly that purchases are unavailable.
 */
class Monetization(private val app: Context, private val prefs: Prefs) : PurchasesUpdatedListener {
    companion object {
        const val ADS_REQUIRED = 4
        const val UNLOCK_MS = 24L * 60 * 60 * 1000
        const val PRODUCT_AD_FREE = "ad_free_premium"          // one-time purchase
        const val PRODUCT_SUBSCRIPTION = "all_apps_subscription" // subscription (all-apps pass)
    }

    private var lastMessage: String? = null
    private var loadingAd = false
    private val _state = MutableStateFlow(Entitlement())
    val state: StateFlow<Entitlement> = _state.asStateFlow()

    private var billing: BillingClient? = null
    private var adFreeDetails: ProductDetails? = null
    private var subDetails: ProductDetails? = null
    private var rewarded: RewardedAd? = null
    private var adsInitialised = false

    init { _state.value = compute() }

    private fun compute(msg: String? = lastMessage): Entitlement {
        val now = System.currentTimeMillis()
        val source = when {
            prefs.hasSubscription -> PremiumSource.SUBSCRIPTION
            prefs.ownsAdFree -> PremiumSource.AD_FREE_PURCHASE
            prefs.unlockUntilMs > now -> PremiumSource.AD_UNLOCK
            else -> PremiumSource.NONE
        }
        return Entitlement(
            premium = source != PremiumSource.NONE, source = source, unlockUntilMs = prefs.unlockUntilMs, adViews = prefs.adViews,
            adReady = rewarded != null, adLoading = loadingAd, billingReady = billing?.isReady == true,
            adFreePrice = adFreeDetails?.oneTimePurchaseOfferDetails?.formattedPrice,
            subscriptionPrice = subDetails?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice,
            message = msg,
        )
    }
    private fun publish(msg: String? = null) { lastMessage = msg; _state.value = compute(msg) }

    fun refresh() = publish(_state.value.message)
    /** True when the app may be used. Re-evaluated against the clock so a 24-hour unlock expires on time. */
    val unlocked: Boolean get() = prefs.premiumActive

    // ---------------------------------------------------- ads
    /** Gather consent (UMP) then initialise the Mobile Ads SDK. Must be called from an Activity. */
    fun startAds(activity: Activity) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ -> if (info.canRequestAds()) initAds(activity) }
            if (info.canRequestAds()) initAds(activity)
        }, { _ -> /* consent info failed: do not request ads */ })
    }

    private fun initAds(ctx: Context) {
        if (adsInitialised) return
        adsInitialised = true
        MobileAds.initialize(ctx) { loadRewarded() }
    }

    fun privacyOptionsRequired(ctx: Context) = UserMessagingPlatform.getConsentInformation(ctx).privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    fun showPrivacyOptions(activity: Activity) = UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
    fun canRequestAds(ctx: Context) = UserMessagingPlatform.getConsentInformation(ctx).canRequestAds()

    fun loadRewarded() {
        if (!adsInitialised || rewarded != null) return
        loadingAd = true; publish(lastMessage)
        RewardedAd.load(app, BuildConfig.ADMOB_REWARDED_UNIT, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) { rewarded = ad; loadingAd = false; publish(null) }
            override fun onAdFailedToLoad(e: LoadAdError) { rewarded = null; loadingAd = false; publish("No ad is available right now. Try again in a moment.") }
        })
    }

    /** Show one rewarded ad; four completed views unlock premium for 24 hours. */
    fun watchAd(activity: Activity) {
        val ad = rewarded
        if (ad == null) { loadRewarded(); publish("No ad is ready yet. Try again in a moment."); return }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { rewarded = null; publish(); loadRewarded() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { rewarded = null; publish("The ad could not be shown."); loadRewarded() }
        }
        ad.show(activity) {
            val n = prefs.adViews + 1
            if (n >= ADS_REQUIRED) { prefs.adViews = 0; prefs.unlockUntilMs = System.currentTimeMillis() + UNLOCK_MS; publish("Premium unlocked for 24 hours.") }
            else { prefs.adViews = n; publish("$n of $ADS_REQUIRED ads watched.") }
        }
    }

    // ---------------------------------------------------- billing
    fun startBilling() {
        if (billing?.isReady == true) return
        val client = billing ?: BillingClient.newBuilder(app).setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build().also { billing = it }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) { queryProducts(); restore(silent = true) }
                else publish("Google Play purchases are unavailable on this device (${r.debugMessage.ifBlank { "code ${r.responseCode}" }}).")
            }
            override fun onBillingServiceDisconnected() { publish() }
        })
    }

    private fun queryProducts() {
        val c = billing ?: return
        fun query(id: String, type: String, set: (ProductDetails?) -> Unit) {
            val p = QueryProductDetailsParams.newBuilder().setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build())).build()
            c.queryProductDetailsAsync(p) { _, list -> set(list.firstOrNull()); publish() }
        }
        query(PRODUCT_AD_FREE, BillingClient.ProductType.INAPP) { adFreeDetails = it }
        query(PRODUCT_SUBSCRIPTION, BillingClient.ProductType.SUBS) { subDetails = it }
    }

    fun buyAdFree(activity: Activity) = launch(activity, adFreeDetails, "The ad-free purchase isn't available from Google Play for this build yet.")
    fun subscribe(activity: Activity) = launch(activity, subDetails, "The subscription isn't available from Google Play for this build yet.")

    private fun launch(activity: Activity, pd: ProductDetails?, unavailable: String) {
        val c = billing
        if (c == null || !c.isReady) { startBilling(); publish("Google Play billing isn't ready. Try again in a moment."); return }
        if (pd == null) { publish(unavailable); return }
        val b = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd)
        pd.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let { b.setOfferToken(it) }
        val res = c.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(b.build())).build())
        if (res.responseCode != BillingClient.BillingResponseCode.OK) publish("Purchase could not start (${res.debugMessage.ifBlank { res.responseCode.toString() }}).")
    }

    /** Restore purchases from Google Play. */
    fun restore(silent: Boolean = false) {
        val c = billing
        if (c == null || !c.isReady) { if (!silent) { startBilling(); publish("Connecting to Google Play… try Restore again in a moment.") }; return }
        var inapp = false; var subs = false; var done = 0; var failed = false
        fun finish() {
            if (++done < 2) return
            if (!failed) { prefs.ownsAdFree = inapp; prefs.hasSubscription = subs; prefs.billingCheckedMs = System.currentTimeMillis() }
            publish(if (silent) null else if (failed) "Couldn't reach Google Play to restore purchases." else if (inapp || subs) "Purchases restored." else "No previous purchases were found for this Google account.")
        }
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) failed = true else inapp = list.any { handle(it, PRODUCT_AD_FREE) }
            finish()
        }
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, list ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) failed = true else subs = list.any { handle(it, PRODUCT_SUBSCRIPTION) }
            finish()
        }
    }

    /** Returns true if the purchase grants the entitlement; acknowledges it if needed. */
    private fun handle(p: Purchase, product: String): Boolean {
        if (product !in p.products || p.purchaseState != Purchase.PurchaseState.PURCHASED) return false
        if (!p.isAcknowledged) billing?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
        return true
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach {
                    if (handle(it, PRODUCT_AD_FREE)) prefs.ownsAdFree = true
                    if (handle(it, PRODUCT_SUBSCRIPTION)) prefs.hasSubscription = true
                }
                publish("Thank you — premium is active.")
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> publish("Purchase cancelled.")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> publish("Purchase failed (${r.debugMessage.ifBlank { r.responseCode.toString() }}).")
        }
    }
}
