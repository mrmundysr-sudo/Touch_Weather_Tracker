import Foundation
import StoreKit
import UIKit
import GoogleMobileAds
import UserMessagingPlatform
import TWCore

enum PremiumSource { case none, adUnlock, adFreePurchase, subscription }

/// The whole app is locked unless the user has an entitlement: four completed rewarded ads (24 hours of use), the ad-free purchase, or the
/// subscription. There are no banner ads. Rewarded ads use AdMob with UMP consent; purchases use StoreKit 2.
@MainActor
final class Monetization: NSObject, ObservableObject, GADFullScreenContentDelegate {
    static let adsRequired = 4
    static let unlockMs: Int64 = 24 * 60 * 60 * 1000
    static let productAdFree = "ad_free_premium"            // one-time purchase (non-consumable)
    static let productSubscription = "all_apps_subscription" // auto-renewing subscription
    /// Google's published TEST rewarded unit. Replace with the owner's unit before any release (docs/MONETIZATION.md).
    static let rewardedUnit = "ca-app-pub-3940256099942544/1712485313"

    @Published private(set) var premium = false
    @Published private(set) var source: PremiumSource = .none
    @Published private(set) var unlockUntilMs: Int64 = 0
    @Published private(set) var adViews = 0
    @Published private(set) var adReady = false
    @Published private(set) var adLoading = false
    @Published private(set) var billingReady = false
    @Published private(set) var adFreePrice: String?
    @Published private(set) var subscriptionPrice: String?
    @Published var message: String?

    private let prefs: Prefs
    private var adFree: Product?
    private var subscription: Product?
    private var rewarded: GADRewardedAd?
    private var adsStarted = false
    private var updatesTask: Task<Void, Never>?

    init(prefs: Prefs) { self.prefs = prefs; super.init(); refresh(); startTransactionListener() }
    deinit { updatesTask?.cancel() }

    /// Re-evaluate against the clock (a 24-hour unlock expires on time).
    func refresh() {
        let now = Units.nowMs()
        source = prefs.hasSubscription ? .subscription : prefs.ownsAdFree ? .adFreePurchase : prefs.unlockUntilMs > now ? .adUnlock : .none
        premium = source != .none; unlockUntilMs = prefs.unlockUntilMs; adViews = prefs.adViews
    }

    // MARK: ads
    func startAds() {
        guard !adsStarted else { return }
        UMPConsentInformation.sharedInstance.requestConsentInfoUpdate(with: UMPRequestParameters()) { [weak self] error in
            Task { @MainActor in
                guard let self, error == nil else { return }
                if let vc = Self.topViewController() {
                    UMPConsentForm.loadAndPresentIfRequired(from: vc) { [weak self] _ in Task { @MainActor in self?.initAdsIfAllowed() } }
                } else { self.initAdsIfAllowed() }
            }
        }
        initAdsIfAllowed()
    }

    var canRequestAds: Bool { UMPConsentInformation.sharedInstance.canRequestAds }
    var privacyOptionsRequired: Bool { UMPConsentInformation.sharedInstance.privacyOptionsRequirementStatus == .required }
    func showPrivacyOptions() { if let vc = Self.topViewController() { UMPConsentForm.presentPrivacyOptionsForm(from: vc) { _ in } } }

    private func initAdsIfAllowed() {
        guard !adsStarted, canRequestAds else { return }
        adsStarted = true
        GADMobileAds.sharedInstance().start { [weak self] _ in Task { @MainActor in self?.loadRewarded() } }
    }

    func loadRewarded() {
        guard adsStarted, rewarded == nil, !adLoading else { return }
        adLoading = true
        GADRewardedAd.load(withAdUnitID: Self.rewardedUnit, request: GADRequest()) { [weak self] ad, error in
            Task { @MainActor in
                guard let self else { return }
                self.adLoading = false
                if let ad { self.rewarded = ad; ad.fullScreenContentDelegate = self; self.adReady = true; self.message = nil }
                else { self.rewarded = nil; self.adReady = false; self.message = "No ad is available right now. Try again in a moment." }
            }
        }
    }

    /// Show one rewarded ad; four completed views unlock the app for 24 hours.
    func watchAd() {
        guard let ad = rewarded, let vc = Self.topViewController() else { loadRewarded(); message = "No ad is ready yet. Try again in a moment."; return }
        ad.present(fromRootViewController: vc) { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                let n = self.prefs.adViews + 1
                if n >= Self.adsRequired { self.prefs.adViews = 0; self.prefs.unlockUntilMs = Units.nowMs() + Self.unlockMs; self.message = "Unlocked for 24 hours." }
                else { self.prefs.adViews = n; self.message = "\(n) of \(Self.adsRequired) ads watched." }
                self.refresh()
            }
        }
    }

    nonisolated func adDidDismissFullScreenContent(_ ad: GADFullScreenPresentingAd) { Task { @MainActor in self.rewarded = nil; self.adReady = false; self.loadRewarded() } }
    nonisolated func ad(_ ad: GADFullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        Task { @MainActor in self.rewarded = nil; self.adReady = false; self.message = "The ad could not be shown."; self.loadRewarded() }
    }

    // MARK: purchases (StoreKit 2)
    func loadProducts() async {
        do {
            let ps = try await Product.products(for: [Self.productAdFree, Self.productSubscription])
            adFree = ps.first { $0.id == Self.productAdFree }; subscription = ps.first { $0.id == Self.productSubscription }
            adFreePrice = adFree?.displayPrice; subscriptionPrice = subscription?.displayPrice
            billingReady = !ps.isEmpty
            if ps.isEmpty { message = "The App Store products aren't available for this build yet." }
        } catch { billingReady = false; message = "Couldn't reach the App Store (\(error.localizedDescription))." }
    }

    func buyAdFree() async { await purchase(adFree, unavailable: "The purchase isn't available from the App Store for this build yet.") }
    func subscribe() async { await purchase(subscription, unavailable: "The subscription isn't available from the App Store for this build yet.") }

    private func purchase(_ product: Product?, unavailable: String) async {
        guard let product else { await loadProducts(); message = message ?? unavailable; return }
        do {
            switch try await product.purchase() {
            case .success(let v):
                if case .verified(let t) = v { await t.finish(); await refreshEntitlements(); message = "Thank you — Touch Weather is unlocked." }
                else { message = "The purchase couldn't be verified." }
            case .userCancelled: message = "Purchase cancelled."
            case .pending: message = "The purchase is pending approval."
            @unknown default: break
            }
        } catch { message = "Purchase failed (\(error.localizedDescription))." }
    }

    func restore() async {
        do { try await AppStore.sync(); await refreshEntitlements(); message = (prefs.ownsAdFree || prefs.hasSubscription) ? "Purchases restored." : "No previous purchases were found for this Apple ID." }
        catch { message = "Couldn't reach the App Store to restore purchases." }
    }

    /// Re-derive ownership from the App Store's current entitlements (expired or refunded purchases drop out).
    func refreshEntitlements() async {
        var adFreeOwned = false, subOwned = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let t) = result, t.revocationDate == nil else { continue }
            if t.productID == Self.productAdFree { adFreeOwned = true }
            if t.productID == Self.productSubscription, (t.expirationDate ?? .distantFuture) > Date() { subOwned = true }
        }
        prefs.ownsAdFree = adFreeOwned; prefs.hasSubscription = subOwned
        refresh()
    }

    private func startTransactionListener() {
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                if case .verified(let t) = update { await t.finish() }
                await self?.refreshEntitlements()
            }
        }
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var vc = scene?.keyWindow?.rootViewController
        while let p = vc?.presentedViewController { vc = p }
        return vc
    }
}
