package com.mundymorning.touchweathertracker.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.Text
import com.mundymorning.touchweathertracker.R
import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.AlertLevel
import com.mundymorning.touchweathertracker.domain.StormReportBuilder
import com.mundymorning.touchweathertracker.source.ActiveStorm
import com.mundymorning.touchweathertracker.ui.groupDays
import kotlin.math.roundToInt

/** Wraps lock / no-place handling so each widget only draws its own layout. */
@androidx.compose.runtime.Composable
private fun Guard(d: WData, needsPlace: Boolean = true, content: @androidx.compose.runtime.Composable () -> Unit) {
    if (d.locked) LockedWidget() else if (needsPlace && d.place == null) NoPlaceWidget() else content()
}

private fun wxDesc(d: WData) = "${d.place?.name ?: ""} ${d.temp?.roundToInt() ?: ""} degrees ${d.summary ?: ""}"

// =============================================================== Everyday Weather family (follows the app's selected place)

/** 1x1 – icon and temperature. */
class TempSmallWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetKit.load(context)
        provideContent { Guard(d) {
            WFrame(WidgetKit.background(d), WidgetKit.open("today", d.place?.id), pad = 6.dp) {
                Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                    WIcon(WidgetKit.icon(d.summary, d.isDay), 30.dp, wxDesc(d))
                    Text(Units.temp(d.temp), style = ts(20, true, center = true))
                    Text(WidgetKit.updated(d.updatedMs, d.tz, short = true), style = ts(9, color = WHITE_DIM, center = true), maxLines = 1)
                }
            }
        } }
    }
}

/** 3x1 – temperature, place and update time with icon. */
class TempWideWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetKit.load(context)
        provideContent { Guard(d) {
            WFrame(WidgetKit.background(d), WidgetKit.open("today", d.place?.id)) {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) {
                        Text(Units.temp(d.temp), style = ts(30, true))
                        Text(d.place?.name.orEmpty(), style = ts(12, true), maxLines = 1)
                        Text("↻ " + WidgetKit.updated(d.updatedMs, d.tz), style = ts(10, color = WHITE_DIM), maxLines = 1)
                    }
                    WIcon(WidgetKit.icon(d.summary, d.isDay), 44.dp, wxDesc(d))
                }
            }
        } }
    }
}

/** 2x3 – tall card. */
class TempTallWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetKit.load(context)
        provideContent { Guard(d) {
            WFrame(WidgetKit.background(d), WidgetKit.open("today", d.place?.id), pad = 12.dp) {
                Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Units.temp(d.temp), style = ts(46, true, center = true))
                    Spacer(GlanceModifier.defaultWeight())
                    WIcon(WidgetKit.icon(d.summary, d.isDay), 64.dp, wxDesc(d))
                    Spacer(GlanceModifier.defaultWeight())
                    Text(d.summary ?: "—", style = ts(13, center = true), maxLines = 2)
                    Text(d.place?.name.orEmpty(), style = ts(14, true, center = true), maxLines = 1)
                    d.weather?.daily?.let { groupDays(it).firstOrNull() }?.let { day -> Text("H ${Units.temp(day.high)}  L ${Units.temp(day.low)}", style = ts(12, color = WHITE_DIM, center = true)) }
                    Text("↻ " + WidgetKit.updated(d.updatedMs, d.tz), style = ts(10, color = WHITE_DIM, center = true), maxLines = 1)
                }
            }
        } }
    }
}

/** 4x2 – next five hours with icons. */
class HourlyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetKit.load(context)
        provideContent { Guard(d) {
            val now = System.currentTimeMillis()
            val hours = d.weather?.hourly?.filter { it.timeMs >= now - 30 * 60_000L }?.take(5).orEmpty()
            WFrame(WidgetKit.background(d), WidgetKit.open("today", d.place?.id)) {
                Column(GlanceModifier.fillMaxSize()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight()) {
                            Text(d.place?.name.orEmpty(), style = ts(12, true), maxLines = 1)
                            Row(verticalAlignment = Alignment.Bottom) { Text(Units.temp(d.temp), style = ts(28, true)); Spacer(GlanceModifier.width(6.dp)); Text(d.summary ?: "", style = ts(12), maxLines = 1) }
                        }
                        WIcon(WidgetKit.icon(d.summary, d.isDay), 36.dp, wxDesc(d))
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth()) {
                        hours.forEach { h ->
                            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Units.clock(h.timeMs, d.tz).replace(":00", "").replace(" ", ""), style = ts(10, color = WHITE_DIM, center = true), maxLines = 1)
                                WIcon(WidgetKit.icon(h.summary, h.isDay), 22.dp, h.summary)
                                Text(Units.temp(h.tempF), style = ts(12, true, center = true))
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(2.dp))
                    Text(WidgetKit.updated(d.updatedMs, d.tz) + " · NWS forecast", style = ts(9, color = WHITE_DIM), maxLines = 1)
                }
            }
        } }
    }
}

/** 4x2 – today detail with hi/lo, feels-like, wind, precipitation and a few hours. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetKit.load(context)
        provideContent { Guard(d) {
            val c = d.weather?.conditions
            val day = d.weather?.daily?.let { groupDays(it).firstOrNull() }
            val now = System.currentTimeMillis()
            val hours = d.weather?.hourly?.filter { it.timeMs >= now - 30 * 60_000L }?.take(3).orEmpty()
            WFrame(WidgetKit.background(d), WidgetKit.open("today", d.place?.id)) {
                Column(GlanceModifier.fillMaxSize()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(Units.temp(d.temp), style = ts(36, true))
                        Spacer(GlanceModifier.width(8.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text(d.summary ?: "—", style = ts(13, true), maxLines = 2)
                            Text("${d.place?.name.orEmpty()}", style = ts(11, color = WHITE_DIM), maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("H ${Units.temp(day?.high)} / L ${Units.temp(day?.low)}", style = ts(12, true))
                            Text("Feels ${Units.temp(c?.feelsLikeF ?: d.temp)}", style = ts(11, color = WHITE_DIM))
                            Text(c?.windMph?.let { "Wind ${it.roundToInt()} mph" } ?: "", style = ts(11, color = WHITE_DIM), maxLines = 1)
                        }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth()) {
                        hours.forEach { h ->
                            Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                                WIcon(WidgetKit.icon(h.summary, h.isDay), 20.dp, h.summary)
                                Spacer(GlanceModifier.width(3.dp))
                                Column { Text(Units.clock(h.timeMs, d.tz).replace(":00", "").replace(" ", ""), style = ts(9, color = WHITE_DIM)); Text(Units.temp(h.tempF), style = ts(12, true)) }
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(2.dp))
                    Text(WidgetKit.updated(d.updatedMs, d.tz) + (c?.observedAtMs?.let { " · observed ${Units.clock(it, d.tz)}" } ?: ""), style = ts(9, color = WHITE_DIM), maxLines = 1)
                }
            }
        } }
    }
}

/** 4x2 – five-day forecast. */
class FiveDayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetKit.load(context)
        provideContent { Guard(d) {
            val days = d.weather?.daily?.let { groupDays(it) }?.take(5).orEmpty()
            WFrame(WidgetKit.background(d), WidgetKit.open("today", d.place?.id)) {
                Column(GlanceModifier.fillMaxSize()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.place?.name.orEmpty(), style = ts(13, true), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                        Text(Units.temp(d.temp), style = ts(18, true))
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth()) {
                        days.forEachIndexed { i, day ->
                            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (i == 0) "Today" else Units.dayName(day.startMs, d.tz), style = ts(10, color = WHITE_DIM, center = true), maxLines = 1)
                                WIcon(WidgetKit.icon(day.summary, true), 26.dp, day.summary)
                                Text(Units.temp(day.high), style = ts(13, true, center = true))
                                Text(Units.temp(day.low), style = ts(11, color = WHITE_DIM, center = true))
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(2.dp))
                    Text(WidgetKit.updated(d.updatedMs, d.tz) + " · NWS forecast", style = ts(9, color = WHITE_DIM), maxLines = 1)
                }
            }
        } }
    }
}

// =============================================================== Saved Place family (place chosen per widget)

@androidx.compose.runtime.Composable
private fun AlertLine(d: WData, size: Int = 12) {
    val a = d.topAlert
    if (a != null) Row(verticalAlignment = Alignment.CenterVertically) {
        WIcon(R.drawable.wx_alert, (size + 6).dp, "Alert")
        Spacer(GlanceModifier.width(4.dp))
        Text("${a.level.name.lowercase().replaceFirstChar { it.uppercase() }}: ${a.event}${if ((d.alerts?.size ?: 0) > 1) " +${d.alerts!!.size - 1}" else ""}", style = ts(size, true, WARN_TEXT.takeIf { d.warningActive } ?: WHITE), maxLines = 2)
    } else if (d.alerts != null) Text("No active alerts found", style = ts(size - 1, color = WHITE_DIM), maxLines = 1)
}

/** 2x2 – a place you choose, with its top active alert. */
class SavedPlaceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val d = WidgetKit.load(context, wid)
        provideContent { Guard(d) {
            WFrame(WidgetKit.background(d), WidgetKit.open("alerts", d.place?.id)) {
                Column(GlanceModifier.fillMaxSize()) {
                    Text(d.place?.name.orEmpty(), style = ts(12, true), maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WIcon(WidgetKit.icon(d.summary, d.isDay), 30.dp, wxDesc(d)); Spacer(GlanceModifier.width(6.dp)); Text(Units.temp(d.temp), style = ts(26, true))
                    }
                    Text(d.summary ?: "", style = ts(11, color = WHITE_DIM), maxLines = 1)
                    Spacer(GlanceModifier.height(4.dp))
                    AlertLine(d, 11)
                    Spacer(GlanceModifier.defaultWeight())
                    Text(WidgetKit.updated(d.updatedMs, d.tz), style = ts(9, color = WHITE_DIM), maxLines = 1)
                }
            }
        } }
    }
}

/** 4x1 – place, temperature and top alert in one row. */
class SavedPlaceWideWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val d = WidgetKit.load(context, wid)
        provideContent { Guard(d) {
            WFrame(WidgetKit.background(d), WidgetKit.open("alerts", d.place?.id), pad = 8.dp) {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    WIcon(WidgetKit.icon(d.summary, d.isDay), 34.dp, wxDesc(d))
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(Units.temp(d.temp), style = ts(22, true)); Spacer(GlanceModifier.width(8.dp)); Text(d.place?.name.orEmpty(), style = ts(12, true), maxLines = 1) }
                        AlertLine(d, 11)
                    }
                    Text(WidgetKit.updated(d.updatedMs, d.tz, short = true), style = ts(9, color = WHITE_DIM), maxLines = 1)
                }
            }
        } }
    }
}

// =============================================================== Storm Watch

/** Active hurricane status, distance/direction from the selected place, and the latest official advisory time. */
class StormWatchWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetKit.load(context)
        provideContent { Guard(d, needsPlace = false) {
            val big = LocalSize.current.height >= 100.dp
            val storms = d.storms.orEmpty()
            val s = storms.maxByOrNull { it.intensityKt ?: 0 }
            WFrame(if (s != null) R.drawable.widget_bg_storm else R.drawable.widget_bg_neutral, WidgetKit.open("storms")) {
                Column(GlanceModifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) { WIcon(R.drawable.wx_cyclone, 18.dp, "Storm Watch"); Spacer(GlanceModifier.width(4.dp)); Text("Storm Watch", style = ts(11, true, WHITE_DIM)) }
                    if (d.stormsFetchedMs == null) {
                        Spacer(GlanceModifier.height(4.dp)); Text("No storm data yet", style = ts(14, true)); Text("Open the app to load NHC data.", style = ts(11, color = WHITE_DIM), maxLines = 2)
                    } else if (s == null) {
                        Spacer(GlanceModifier.height(4.dp)); Text("No active tropical cyclones", style = ts(15, true), maxLines = 2)
                        if (big) Text("Per the National Hurricane Center. This does not mean other hazards are absent.", style = ts(11, color = WHITE_DIM), maxLines = 3)
                    } else {
                        Spacer(GlanceModifier.height(2.dp))
                        Text("${s.classLabel} ${s.name}", style = ts(16, true), maxLines = 2)
                        Text(listOfNotNull(ActiveStorm.saffir(s.intensityKt), s.intensityKt?.let { "${StormReportBuilder.ktToMph(it)} mph" }).joinToString(" · "), style = ts(13))
                        d.place?.let { p -> Text("${Geo.distanceMiles(p.lat, p.lon, s.lat, s.lon).roundToInt()} mi ${Geo.compass(Geo.bearing(p.lat, p.lon, s.lat, s.lon))} of ${p.name}", style = ts(12), maxLines = 1) }
                        if (big && storms.size > 1) Text("+${storms.size - 1} more active", style = ts(11, color = WHITE_DIM))
                        Text("Advisory ${s.advisoryNumber ?: "—"} · ${s.advisoryIssuedMs?.let { Units.dayClock(it, d.tz) } ?: "time unknown"}", style = ts(10, color = WHITE_DIM), maxLines = 2)
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Text(WidgetKit.updated(d.stormsFetchedMs, d.tz), style = ts(9, color = WHITE_DIM), maxLines = 1)
                }
            }
        } }
    }
}

// =============================================================== receivers

abstract class TwWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        com.mundymorning.touchweathertracker.work.Scheduler.refreshNow(context)
    }
}
class TempSmallReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TempSmallWidget() }
class TempWideReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TempWideWidget() }
class TempTallReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TempTallWidget() }
class HourlyReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = HourlyWidget() }
class TodayReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TodayWidget() }
class FiveDayReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = FiveDayWidget() }
class SavedPlaceReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SavedPlaceWidget() }
class SavedPlaceWideReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SavedPlaceWideWidget() }
class StormWatchReceiver : TwWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = StormWatchWidget() }

object AllWidgets {
    suspend fun updateAll(ctx: Context) {
        TempSmallWidget().updateAll(ctx); TempWideWidget().updateAll(ctx); TempTallWidget().updateAll(ctx)
        HourlyWidget().updateAll(ctx); TodayWidget().updateAll(ctx); FiveDayWidget().updateAll(ctx)
        SavedPlaceWidget().updateAll(ctx); SavedPlaceWideWidget().updateAll(ctx); StormWatchWidget().updateAll(ctx)
    }
}
