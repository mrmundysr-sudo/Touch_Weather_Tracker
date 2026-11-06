package com.mundymorning.touchweathertracker.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mundymorning.touchweathertracker.TouchWeatherApp

class EventActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val storm = intent.getStringExtra("storm") ?: return
        val app = context.applicationContext as TouchWeatherApp
        EventModeController.apply(context, app.repo.prefs, intent.action.orEmpty(), storm)
        Notifier.cancel(context, "offer$storm"); Notifier.cancel(context, "end$storm")
    }

    companion object {
        const val ACTION_ENABLE = "twt.event.enable"
        const val ACTION_DECLINE = "twt.event.decline"
        const val ACTION_END = "twt.event.end"
        const val ACTION_KEEP = "twt.event.keep"
    }
}
