package com.mundymorning.touchweathertracker.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.mundymorning.touchweathertracker.MainActivity
import com.mundymorning.touchweathertracker.TouchWeatherApp
import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.source.ActiveStorm
import kotlin.math.roundToInt

private val BG = ColorProvider(day = Color(0xFFEAF3FB), night = Color(0xFF0E1D2F))
private val FG = ColorProvider(day = Color(0xFF0B2239), night = Color(0xFFEAF3FB))
private val MUTED = ColorProvider(day = Color(0xFF45607B), night = Color(0xFFA7BBD0))
private val ALERT = ColorProvider(day = Color(0xFFB3261E), night = Color(0xFFFFB4AB))
private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)
private val TALL = DpSize(250.dp, 220.dp)

private fun t(size: Int, bold: Boolean = false, color: ColorProvider = FG) = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

private fun repo(ctx: Context) = (ctx.applicationContext as TouchWeatherApp).repo

private fun open(tab: String, placeId: String? = null) = actionStartActivity(
    Intent().setClassName("com.mundymorning.touchweathertracker", "com.mundymorning.touchweathertracker.MainActivity")
        .putExtra(MainActivity.EXTRA_TAB, tab).also { i -> placeId?.let { i.putExtra(MainActivity.EXTRA_PLACE, it) } }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
)

private fun stamp(ms: Long?, tz: String?) = ms?.let { "Updated ${Units.clock(it, tz)}" } ?: "Not updated yet"

private fun locked(ctx: Context) = !repo(ctx).prefs.premiumActive

@androidx.compose.runtime.Composable
private fun Locked() = Empty("Locked — open Touch Weather Tracker to unlock", "today")

@androidx.compose.runtime.Composable
private fun Empty(msg: String, tab: String) {
    Box(GlanceModifier.fillMaxSize().background(BG).padding(12.dp).clickable(open(tab)), contentAlignment = Alignment.Center) {
        Text(msg, style = t(13, color = MUTED))
    }
}

/** Weather block shared by the Everyday and Saved Place widgets. */
@androidx.compose.runtime.Composable
private fun WeatherBlock(place: Place, w: WeatherData?, alerts: List<Alert>?, size: DpSize, showAlerts: Boolean, tab: String) {
    val c = w?.conditions
    val today = w?.daily?.firstOrNull()
    val hi = w?.daily?.mapNotNull { it.highF }?.firstOrNull(); val lo = w?.daily?.mapNotNull { it.lowF }?.firstOrNull()
    val now = System.currentTimeMillis()
    val next = w?.hourly?.filter { it.timeMs >= now - 30 * 60_000L }?.take(if (size.width >= 250.dp) 5 else 3).orEmpty()
    Column(GlanceModifier.fillMaxSize().background(BG).padding(12.dp).clickable(open(tab, place.id))) {
        Text(place.name, style = t(12, true, MUTED), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Units.temp(c?.tempF ?: next.firstOrNull()?.tempF), style = t(34, true))
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(c?.summary ?: today?.summary ?: "—", style = t(13), maxLines = 2)
                Text("H ${Units.temp(hi)}  L ${Units.temp(lo)}", style = t(12, color = MUTED))
            }
        }
        if (showAlerts) {
            val top = alerts?.minByOrNull { it.level.ordinal }
            if (top != null) Text("⚠ ${top.event}${if ((alerts.size) > 1) " +${alerts.size - 1}" else ""}", style = t(12, true, ALERT), maxLines = 1)
            else if (alerts != null) Text("No active alerts found", style = t(11, color = MUTED), maxLines = 1)
        }
        if (size.height >= 200.dp || (size.width >= 250.dp && size.height >= 110.dp && !showAlerts) ) {
            Spacer(GlanceModifier.height(4.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                next.forEach { h ->
                    Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Units.clock(h.timeMs, place.timeZone).replace(":00", ""), style = t(10, color = MUTED))
                        Text(Units.temp(h.tempF), style = t(13, true))
                    }
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(stamp(w?.fetchedAtMs, place.timeZone) + if (c?.observedAtMs != null) " · obs ${Units.clock(c.observedAtMs, place.timeZone)}" else "", style = t(10, color = MUTED), maxLines = 1)
    }
}

class EverydayWeatherWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val r = repo(context)
        val place = r.selected()
        val w = place?.let { r.cachedWeather(it.id)?.value }
        val isLocked = locked(context)
        provideContent {
            if (isLocked) Locked() else if (place == null) Empty("Open Touch Weather Tracker to add a place", "today") else WeatherBlock(place, w, null, LocalSize.current, false, "today")
        }
    }
}

class SavedPlaceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val r = repo(context)
        val wid = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val place = r.prefs.widgetPlace(wid)?.let { pid -> r.places.value.firstOrNull { it.id == pid } }
        val w = place?.let { r.cachedWeather(it.id)?.value }
        val alerts = place?.let { r.store.read("alerts_${it.id}", AlertSet.serializer())?.alerts }
        val isLocked = locked(context)
        provideContent {
            if (isLocked) Locked() else if (place == null) Empty("Tap to choose a saved place", "today") else WeatherBlock(place, w, alerts, LocalSize.current, true, "alerts")
        }
    }
}

class StormWatchWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val r = repo(context)
        val place = r.selected()
        val cached = r.store.read("storms", Cached.serializer(kotlinx.serialization.builtins.ListSerializer(StormSnapshot.serializer())))
        val storms = cached?.value?.map { it.toStorm() }.orEmpty()
        val fetched = cached?.fetchedAtMs
        val isLocked = locked(context)
        provideContent {
            if (isLocked) { Locked(); return@provideContent }
            val size = LocalSize.current
            Column(GlanceModifier.fillMaxSize().background(BG).padding(12.dp).clickable(open("storms"))) {
                Text("Storm Watch", style = t(12, true, MUTED))
                if (cached == null) {
                    Text("No storm data yet", style = t(14, true))
                    Text("Open the app to load NHC data.", style = t(11, color = MUTED))
                } else if (storms.isEmpty()) {
                    Text("No active tropical cyclones", style = t(15, true))
                    Text("Per the National Hurricane Center. This does not mean other hazards are absent.", style = t(11, color = MUTED), maxLines = 3)
                } else {
                    val s = storms.maxByOrNull { it.intensityKt ?: 0 }!!
                    Text("${s.classLabel} ${s.name}", style = t(16, true), maxLines = 2)
                    val cat = ActiveStorm.saffir(s.intensityKt)
                    Text(listOfNotNull(cat, s.intensityKt?.let { "${((it * 1.15078) / 5).roundToInt() * 5} mph" }).joinToString(" · "), style = t(13))
                    if (place != null) {
                        val d = Geo.distanceMiles(place.lat, place.lon, s.lat, s.lon).roundToInt()
                        Text("$d mi ${Geo.compass(Geo.bearing(place.lat, place.lon, s.lat, s.lon))} of ${place.name}", style = t(12), maxLines = 1)
                    }
                    if (size.height >= 200.dp && storms.size > 1) Text("+${storms.size - 1} more active", style = t(11, color = MUTED))
                    Text("Advisory ${s.advisoryNumber ?: "—"} · ${s.advisoryIssuedMs?.let { Units.dayClock(it, place?.timeZone) } ?: "time unknown"}", style = t(11, color = MUTED), maxLines = 1)
                }
                Spacer(GlanceModifier.defaultWeight())
                Text(stamp(fetched, place?.timeZone), style = t(10, color = MUTED))
            }
        }
    }
}

class EverydayWeatherReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EverydayWeatherWidget()
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) { super.onUpdate(context, appWidgetManager, appWidgetIds); com.mundymorning.touchweathertracker.work.Scheduler.refreshNow(context) }
}
class SavedPlaceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SavedPlaceWidget()
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) { super.onUpdate(context, appWidgetManager, appWidgetIds); com.mundymorning.touchweathertracker.work.Scheduler.refreshNow(context) }
}
class StormWatchReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StormWatchWidget()
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) { super.onUpdate(context, appWidgetManager, appWidgetIds); com.mundymorning.touchweathertracker.work.Scheduler.refreshNow(context) }
}
