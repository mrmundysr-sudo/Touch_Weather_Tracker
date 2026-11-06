package com.mundymorning.touchweathertracker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.mundymorning.touchweathertracker.TouchWeatherApp
import com.mundymorning.touchweathertracker.ui.TwTheme
import kotlinx.coroutines.launch

/** Lets the user pick which saved place the "Saved Place" widget shows. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val repo = (application as TouchWeatherApp).repo
        setContent {
            TwTheme {
                val places by repo.places.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp).statusBarsPadding()) {
                        Text("Choose a place for this widget", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        if (places.isEmpty()) Text("Add a place in Touch Weather Tracker first, then add this widget again.")
                        places.forEach { p ->
                            ListItem(
                                headlineContent = { Text(p.name) },
                                supportingContent = { Text(listOfNotNull(if (p.isHome) "Home" else null, p.subtitle ?: p.state).joinToString(" · ")) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                                    repo.prefs.setWidgetPlace(widgetId, p.id)
                                    lifecycleScope.launch {
                                        runCatching { repo.weather(p); repo.alertsFor(p) }
                                        val gid = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(widgetId)
                                        SavedPlaceWidget().update(this@WidgetConfigActivity, gid)
                                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                        finish()
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
