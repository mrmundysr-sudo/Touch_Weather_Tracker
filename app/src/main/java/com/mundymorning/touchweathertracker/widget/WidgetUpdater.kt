package com.mundymorning.touchweathertracker.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.mundymorning.touchweathertracker.TouchWeatherApp

object WidgetUpdater {
    /** Refresh cached data the widgets read, then redraw them. OS-controlled scheduling means widgets are never "instant". */
    suspend fun updateAll(ctx: Context) {
        val repo = (ctx.applicationContext as TouchWeatherApp).repo
        val ids = mutableSetOf<String>()
        repo.selected()?.let { ids += it.id }
        repo.places.value.forEach { ids += it.id }
        repo.places.value.filter { it.id in ids }.take(6).forEach { p ->
            runCatching { repo.weather(p) }
            runCatching { repo.alertsFor(p) }
        }
        runCatching { repo.storms() }
        EverydayWeatherWidget().updateAll(ctx)
        SavedPlaceWidget().updateAll(ctx)
        StormWatchWidget().updateAll(ctx)
    }
}
