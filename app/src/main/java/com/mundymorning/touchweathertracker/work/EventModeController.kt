package com.mundymorning.touchweathertracker.work

import android.content.Context
import com.mundymorning.touchweathertracker.data.Prefs

/** Single place that mutates event-mode choices so notifications, the in-app card and settings stay consistent. */
object EventModeController {
    fun apply(ctx: Context, prefs: Prefs, action: String, stormId: String) {
        when (action) {
            EventActionReceiver.ACTION_ENABLE -> { prefs.eventModeActiveStorm = stormId; prefs.eventModeDeclined = prefs.eventModeDeclined - stormId }
            EventActionReceiver.ACTION_DECLINE -> prefs.eventModeDeclined = prefs.eventModeDeclined + stormId
            EventActionReceiver.ACTION_END -> { prefs.eventModeActiveStorm = null }
            EventActionReceiver.ACTION_KEEP -> prefs.eventModeEndPromptDismissed = prefs.eventModeEndPromptDismissed + stormId
        }
        Scheduler.schedule(ctx, prefs.eventModeActiveStorm != null)
        prefs.bump()
    }
}
