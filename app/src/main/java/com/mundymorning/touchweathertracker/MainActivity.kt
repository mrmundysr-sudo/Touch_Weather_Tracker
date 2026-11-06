package com.mundymorning.touchweathertracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mundymorning.touchweathertracker.ui.LaunchRequest
import com.mundymorning.touchweathertracker.ui.TwApp
import com.mundymorning.touchweathertracker.ui.TwTheme
import com.mundymorning.touchweathertracker.work.Scheduler

class MainActivity : ComponentActivity() {
    private var request by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TouchWeatherApp
        request = fromIntent(intent)
        if (app.repo.prefs.onboarded) { Scheduler.schedule(this, app.repo.prefs.eventModeActiveStorm != null); if (app.repo.prefs.premiumActive) Scheduler.refreshNow(this) }
        // Ads consent + SDK start, and billing connection (entitlements are re-checked on each launch).
        app.monetization.startAds(this)
        app.monetization.startBilling()
        setContent {
            TwTheme {
                Surface(Modifier.fillMaxSize()) { TwApp(request) { request = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = fromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        (application as TouchWeatherApp).monetization.refresh()
    }

    private fun fromIntent(i: Intent?): LaunchRequest? {
        if (i == null) return null
        val tab = i.getStringExtra(EXTRA_TAB); val alert = i.getStringExtra(EXTRA_ALERT_ID); val place = i.getStringExtra(EXTRA_PLACE); val storm = i.getStringExtra(EXTRA_EVENT_STORM)
        return if (tab == null && alert == null && place == null && storm == null) null else LaunchRequest(tab, alert, place, storm)
    }

    companion object {
        const val EXTRA_TAB = "twt.tab"
        const val EXTRA_ALERT_ID = "twt.alert"
        const val EXTRA_PLACE = "twt.place"
        const val EXTRA_EVENT_STORM = "twt.eventStorm"
    }
}
