package com.mundymorning.touchweathertracker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide small settings and entitlement state. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("twt_prefs", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false); set(v) { sp.edit().putBoolean("onboarded", v).apply() }

    /** Layer toggles on the map. */
    var mapRadar: Boolean get() = sp.getBoolean("map_radar", true); set(v) { sp.edit().putBoolean("map_radar", v).apply() }
    var mapSatellite: String get() = sp.getString("map_sat", "off") ?: "off"; set(v) { sp.edit().putString("map_sat", v).apply() }  // off | visible | infrared | watervapor
    var mapWarnings: Boolean get() = sp.getBoolean("map_warn", true); set(v) { sp.edit().putBoolean("map_warn", v).apply() }
    var mapTracks: Boolean get() = sp.getBoolean("map_tracks", true); set(v) { sp.edit().putBoolean("map_tracks", v).apply() }
    var mapObservations: Boolean get() = sp.getBoolean("map_obs", false); set(v) { sp.edit().putBoolean("map_obs", v).apply() }
    var mapGfs: Boolean get() = sp.getBoolean("map_gfs", false); set(v) { sp.edit().putBoolean("map_gfs", v).apply() }
    var mapEcmwf: Boolean get() = sp.getBoolean("map_ecmwf", false); set(v) { sp.edit().putBoolean("map_ecmwf", v).apply() }
    var mapImagery: Boolean get() = sp.getBoolean("map_imagery", false); set(v) { sp.edit().putBoolean("map_imagery", v).apply() }

    // ---- notifications ----
    var notificationsAsked: Boolean get() = sp.getBoolean("notif_asked", false); set(v) { sp.edit().putBoolean("notif_asked", v).apply() }
    var wirelessAlertsNoticeShown: Boolean get() = sp.getBoolean("wea_notice", false); set(v) { sp.edit().putBoolean("wea_notice", v).apply() }

    // ---- event mode ----
    var eventModeActiveStorm: String? get() = sp.getString("event_storm", null); set(v) { sp.edit().putString("event_storm", v).apply() }
    var eventModeDeclined: Set<String> get() = sp.getStringSet("event_declined", emptySet()) ?: emptySet(); set(v) { sp.edit().putStringSet("event_declined", v).apply() }
    var eventModeEndPromptDismissed: Set<String> get() = sp.getStringSet("event_end_dismissed", emptySet()) ?: emptySet(); set(v) { sp.edit().putStringSet("event_end_dismissed", v).apply() }
    var eventModePromptedFor: Set<String> get() = sp.getStringSet("event_prompted", emptySet()) ?: emptySet(); set(v) { sp.edit().putStringSet("event_prompted", v).apply() }
    /** User preference: allow the app to offer event mode at all. */
    var eventModeOffers: Boolean get() = sp.getBoolean("event_offers", true); set(v) { sp.edit().putBoolean("event_offers", v).apply() }

    // ---- monetization ----
    /** Rewarded-ad progress toward the 24-hour unlock (resets after unlock). */
    var adViews: Int get() = sp.getInt("ad_views", 0); set(v) { sp.edit().putInt("ad_views", v).apply() }
    var unlockUntilMs: Long get() = sp.getLong("unlock_until", 0L); set(v) { sp.edit().putLong("unlock_until", v).apply() }
    var ownsAdFree: Boolean get() = sp.getBoolean("own_adfree", false); set(v) { sp.edit().putBoolean("own_adfree", v).apply() }
    var hasSubscription: Boolean get() = sp.getBoolean("own_sub", false); set(v) { sp.edit().putBoolean("own_sub", v).apply() }
    var billingCheckedMs: Long get() = sp.getLong("billing_checked", 0L); set(v) { sp.edit().putLong("billing_checked", v).apply() }
    var adConsentDone: Boolean get() = sp.getBoolean("ad_consent", false); set(v) { sp.edit().putBoolean("ad_consent", v).apply() }

    // ---- notification de-dup: alertId -> signature of last notified state ----
    fun notifiedSignature(alertId: String): String? = sp.getString("n_$alertId", null)
    fun setNotified(alertId: String, sig: String) { sp.edit().putString("n_$alertId", sig).apply() }
    fun notifiedAll(): Map<String, String> = sp.all.filterKeys { it.startsWith("n_") }.mapKeys { it.key.removePrefix("n_") }.mapValues { it.value.toString() }
    fun forgetNotified(alertId: String) { sp.edit().remove("n_$alertId").apply() }
    fun pruneNotified(keep: Set<String>) {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("n_") && it.removePrefix("n_") !in keep }.forEach { e.remove(it) }
        e.apply()
    }

    // ---- last widget selection ----
    fun widgetPlace(widgetId: Int): String? = sp.getString("w_$widgetId", null)
    fun setWidgetPlace(widgetId: Int, placeId: String) { sp.edit().putString("w_$widgetId", placeId).apply() }

    private val _tick = MutableStateFlow(0)
    val changes: StateFlow<Int> get() = _tick
    fun bump() { _tick.value = _tick.value + 1 }

    val premiumActive: Boolean
        get() = ownsAdFree || hasSubscription || unlockUntilMs > System.currentTimeMillis()
}
