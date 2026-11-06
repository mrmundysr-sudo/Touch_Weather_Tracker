package com.mundymorning.touchweathertracker.widget

import android.content.Context
import com.mundymorning.touchweathertracker.TouchWeatherApp
import kotlinx.coroutines.withTimeoutOrNull

object WidgetUpdater {
    /** Redraw every widget from the on-device caches only (no network). Each widget is isolated so one failure never blocks the others. */
    suspend fun redraw(ctx: Context) = AllWidgets.updateAll(ctx)

    /**
     * Refresh cached data, then redraw. Home is fetched first and the widgets redrawn right away, so a Home change never waits on
     * other places or slow responses; remaining places are refreshed with a time limit. OS-controlled scheduling means widgets are never "instant".
     */
    suspend fun updateAll(ctx: Context) {
        val repo = (ctx.applicationContext as TouchWeatherApp).repo
        if (repo.prefs.premiumActive) {
            repo.home()?.let { h ->
                withTimeoutOrNull(25_000) { runCatching { repo.weather(h) }; runCatching { repo.alertsFor(h) } }
            }
            redraw(ctx)
            withTimeoutOrNull(60_000) {
                repo.places.value.filter { !it.isHome }.take(5).forEach { p -> runCatching { repo.weather(p) }; runCatching { repo.alertsFor(p) } }
                runCatching { repo.storms() }
            }
        }
        redraw(ctx)
    }
}
