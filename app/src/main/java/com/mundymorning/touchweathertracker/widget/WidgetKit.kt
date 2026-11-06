package com.mundymorning.touchweathertracker.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mundymorning.touchweathertracker.MainActivity
import com.mundymorning.touchweathertracker.R
import com.mundymorning.touchweathertracker.TouchWeatherApp
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.source.ActiveStorm

/** Everything a widget needs, read from the on-device caches (widgets never block on the network). */
data class WData(
    val locked: Boolean,
    val place: Place?,
    val weather: WeatherData?,
    val alerts: List<Alert>?,
    val storms: List<ActiveStorm>?,
    val stormsFetchedMs: Long?,
) {
    val tz: String? get() = place?.timeZone
    val isDay: Boolean get() = weather?.hourly?.firstOrNull()?.isDay ?: true
    val temp: Double? get() = weather?.conditions?.tempF ?: weather?.hourly?.firstOrNull()?.tempF
    val summary: String? get() = weather?.conditions?.summary ?: weather?.hourly?.firstOrNull()?.summary
    val topAlert: Alert? get() = alerts?.minByOrNull { it.level.ordinal }
    val warningActive: Boolean get() = alerts?.any { it.level == AlertLevel.WARNING } == true
    val updatedMs: Long? get() = weather?.fetchedAtMs
}

object WidgetKit {
    fun repo(ctx: Context) = (ctx.applicationContext as TouchWeatherApp).repo

    /** [pickedWidgetId] is used by widgets that let the user choose a place; the others follow the app's selected place. */
    fun load(ctx: Context, pickedWidgetId: Int? = null): WData {
        val r = repo(ctx)
        val locked = !r.prefs.premiumActive
        val place = pickedWidgetId?.let { id -> r.prefs.widgetPlace(id)?.let { pid -> r.places.value.firstOrNull { it.id == pid } } } ?: if (pickedWidgetId == null) r.selected() else null
        val w = place?.let { r.cachedWeather(it.id)?.value }
        val alerts = place?.let { r.store.read("alerts_${it.id}", AlertSet.serializer())?.alerts }
        val st = r.store.read("storms", Cached.serializer(kotlinx.serialization.builtins.ListSerializer(StormSnapshot.serializer())))
        return WData(locked, place, w, alerts, st?.value?.map { it.toStorm() }, st?.fetchedAtMs)
    }

    fun icon(summary: String?, day: Boolean): Int {
        val s = summary?.lowercase().orEmpty()
        return when {
            "thunder" in s -> R.drawable.wx_storm
            "snow" in s || "flurr" in s || "blizzard" in s || "sleet" in s || "ice" in s -> R.drawable.wx_snow
            "rain" in s || "shower" in s || "drizzle" in s -> R.drawable.wx_rain
            "fog" in s || "haze" in s || "smoke" in s || "mist" in s -> R.drawable.wx_fog
            "partly" in s || "mostly sunny" in s || "mostly clear" in s || "few clouds" in s || "scattered" in s -> if (day) R.drawable.wx_partly_day else R.drawable.wx_partly_night
            "cloud" in s || "overcast" in s -> R.drawable.wx_cloud
            "wind" in s || "breezy" in s -> R.drawable.wx_wind
            else -> if (day) R.drawable.wx_sun else R.drawable.wx_moon
        }
    }

    fun background(d: WData): Int = when {
        d.warningActive -> R.drawable.widget_bg_alert
        !d.isDay -> R.drawable.widget_bg_night
        else -> R.drawable.widget_bg_day
    }

    fun open(tab: String, placeId: String? = null): Action = actionStartActivity(
        Intent().setClassName("com.mundymorning.touchweathertracker", "com.mundymorning.touchweathertracker.MainActivity")
            .putExtra(MainActivity.EXTRA_TAB, tab).also { i -> placeId?.let { i.putExtra(MainActivity.EXTRA_PLACE, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    )

    fun updated(ms: Long?, tz: String?, short: Boolean = false): String =
        if (ms == null) "Not updated yet" else (if (short) Units.clock(ms, tz).replace(" ", "") else "Updated ${Units.clock(ms, tz)}")
}

// ---- style helpers ----
val WHITE = ColorProvider(Color.White)
val WHITE_DIM = ColorProvider(Color(0xCCFFFFFF))
val WARN_TEXT = ColorProvider(Color(0xFFFFD6D2))

fun ts(size: Int, bold: Boolean = false, color: ColorProvider = WHITE, center: Boolean = false) =
    TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = if (center) TextAlign.Center else TextAlign.Start)

@androidx.compose.runtime.Composable
fun WIcon(res: Int, size: Dp, description: String? = null) = Image(ImageProvider(res), description, GlanceModifier.size(size))

@androidx.compose.runtime.Composable
fun WFrame(bg: Int, onClick: Action, pad: Dp = 10.dp, content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(bg)).clickable(onClick).padding(pad)) { content() }
}

@androidx.compose.runtime.Composable
fun LockedWidget() =
    WFrame(R.drawable.widget_bg_neutral, WidgetKit.open("today")) {
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Locked", style = ts(14, true, center = true)); Text("Open Touch Weather Tracker to unlock", style = ts(11, color = WHITE_DIM, center = true), maxLines = 3)
        }
    }

@androidx.compose.runtime.Composable
fun NoPlaceWidget(msg: String = "Open Touch Weather Tracker to add a place") =
    WFrame(R.drawable.widget_bg_neutral, WidgetKit.open("today")) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(msg, style = ts(12, color = WHITE_DIM, center = true), maxLines = 4) }
    }
