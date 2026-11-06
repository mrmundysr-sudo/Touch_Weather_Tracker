package com.mundymorning.touchweathertracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.source.NwsSource
import kotlin.math.roundToInt

fun weatherIcon(summary: String?, day: Boolean = true): ImageVector {
    val s = summary?.lowercase().orEmpty()
    return when {
        "thunder" in s -> Icons.Filled.Thunderstorm
        "snow" in s || "flurr" in s || "blizzard" in s || "sleet" in s || "ice" in s -> Icons.Filled.AcUnit
        "rain" in s || "shower" in s || "drizzle" in s -> Icons.Filled.WaterDrop
        "fog" in s || "haze" in s || "smoke" in s || "mist" in s -> Icons.Filled.BlurOn
        "wind" in s || "breezy" in s -> Icons.Filled.Air
        "cloud" in s || "overcast" in s -> Icons.Filled.Cloud
        "clear" in s || "sunny" in s || "fair" in s -> if (day) Icons.Filled.WbSunny else Icons.Filled.NightsStay
        else -> if (day) Icons.Filled.WbSunny else Icons.Filled.NightsStay
    }
}

data class DayRow(val name: String, val startMs: Long, val high: Double?, val low: Double?, val summary: String, val detail: String, val precip: Double?, val wind: String?)

fun groupDays(periods: List<DailyPoint>): List<DayRow> {
    val out = mutableListOf<DayRow>()
    var i = 0
    while (i < periods.size) {
        val p = periods[i]
        if (p.highF != null) {
            val night = periods.getOrNull(i + 1)?.takeIf { it.lowF != null }
            out += DayRow(p.name, p.startMs, p.highF, night?.lowF, p.summary, p.detail + (night?.let { "\n\n${it.name}: ${it.detail}" } ?: ""), maxOf(p.precipChance ?: 0.0, night?.precipChance ?: 0.0).takeIf { (p.precipChance ?: night?.precipChance) != null }, p.windText)
            i += if (night != null) 2 else 1
        } else {
            out += DayRow(p.name, p.startMs, null, p.lowF, p.summary, p.detail, p.precipChance, p.windText); i++
        }
    }
    return out.take(8)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: AppViewModel, onOpenAlert: (String) -> Unit, onOpenPlaces: () -> Unit, onOpenSettings: () -> Unit, onOpenStorms: () -> Unit, onOpenAlerts: () -> Unit, onOpenPremium: () -> Unit) {
    val place by vm.selected.collectAsState()
    val places by vm.places.collectAsState()
    val st by vm.today.collectAsState()
    val ent by vm.mon.state.collectAsState()
    val storms by vm.storms.collectAsState()
    LaunchedEffect(place?.id) { vm.refreshToday(); vm.loadStorms() }
    val p = place
    if (p == null) { EmptyState(Icons.Outlined.Place, "Add a place", "Search by address, ZIP code or city to get your forecast.", Modifier.padding(top = 80.dp)) { Button(onClick = onOpenPlaces) { Text("Add a place") } }; return }

    val w = st.weather?.value
    val alerts = st.alerts?.value?.alerts.orEmpty().sortedWith(compareBy({ it.level.ordinal }, { it.endMs ?: Long.MAX_VALUE }))
    val warnings = alerts.filter { it.level == AlertLevel.WARNING }
    val urgent = warnings.isNotEmpty()
    val activeStorm = storms.storms?.value.orEmpty().maxByOrNull { it.intensityKt ?: 0 }
    var picker by remember { mutableStateOf(false) }

    PullToRefreshBox(isRefreshing = st.loading, onRefresh = { vm.refreshToday(true); vm.loadStorms(true) }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---------- Header (turns urgent when a warning is active) ----------
            val c = Tw.colors
            val top = if (urgent) c.warning.copy(alpha = 0.92f) else c.skyDeep
            val bottom = if (urgent) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.background
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(top, if (urgent) c.warning.copy(alpha = 0.55f) else Color(0xFF1B5B94).copy(alpha = if (isDark()) 0.35f else 0.95f), bottom))).statusBarsPadding()) {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 28.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable { picker = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (p.usesDeviceLocation) Icons.Filled.MyLocation else if (p.isHome) Icons.Filled.Home else Icons.Filled.Place, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                                Text(listOfNotNull(if (p.isHome) "Home" else null, p.state ?: p.subtitle).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                            }
                            Icon(Icons.Filled.ArrowDropDown, "Switch place", tint = Color.White)
                        }
                        IconButton(onClick = onOpenPlaces) { Icon(Icons.Outlined.EditLocationAlt, "Manage places", tint = Color.White) }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = Color.White) }
                    }
                    Spacer(Modifier.height(12.dp))
                    val cond = w?.conditions
                    val nowHour = w?.hourly?.firstOrNull()
                    val temp = cond?.tempF ?: nowHour?.tempF
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(weatherIcon(cond?.summary ?: nowHour?.summary, nowHour?.isDay ?: true), null, tint = Color.White, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(Units.temp(temp), style = MaterialTheme.typography.displayLarge, color = Color.White, modifier = Modifier.semantics { contentDescription = "Temperature ${temp?.roundToInt() ?: "unknown"} degrees Fahrenheit" })
                            Text(cond?.summary ?: nowHour?.summary ?: if (st.loading) "Loading…" else "Conditions unavailable", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                    }
                    val day = groupDays(w?.daily.orEmpty()).firstOrNull()
                    Text(buildString {
                        cond?.feelsLikeF?.let { append("Feels like ${Units.temp(it)}   ") }
                        day?.high?.let { append("H ${Units.temp(it)}  ") }; day?.low?.let { append("L ${Units.temp(it)}") }
                    }, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f), modifier = Modifier.padding(top = 4.dp))
                }
            }

            // ---------- Staleness / error ----------
            val wl = st.weather
            if (wl != null && wl.stale) StaleBanner(wl.error ?: "Couldn't refresh.", wl.fetchedAtMs, p.timeZone) { vm.refreshToday(true) }
            else if (wl != null && wl.value == null) ErrorState(wl.error ?: "Weather is unavailable.", { vm.refreshToday(true) })
            st.alerts?.takeIf { it.stale }?.let { StaleBanner("Alerts could not be refreshed. Do not assume there are no warnings.", it.fetchedAtMs, p.timeZone) { vm.refreshToday(true) } }

            // ---------- Alerts for this place ----------
            if (alerts.isNotEmpty()) Section("Active alerts at ${p.name}", action = { TextButton(onClick = onOpenAlerts) { Text("All alerts") } }) {
                alerts.take(4).forEach { a -> AlertRow(a, onClick = { onOpenAlert(a.id) }, tz = p.timeZone); Spacer(Modifier.height(8.dp)) }
                if (alerts.size > 4) Text("+${alerts.size - 4} more", style = MaterialTheme.typography.bodySmall)
            } else if (st.alerts?.value != null) {
                Section("Alerts") { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.CheckCircle, null, tint = Tw.colors.observed); Spacer(Modifier.width(8.dp)); Text("No active alerts found for this location. Conditions can change quickly — this is not a guarantee that no warning will be issued.", style = MaterialTheme.typography.bodyMedium) } }
            }

            // ---------- Storm focus ----------
            activeStorm?.let { s ->
                val d = Geo.distanceMiles(p.lat, p.lon, s.lat, s.lon).roundToInt()
                Section("Tropical activity") {
                    TwCard(onClick = onOpenStorms, container = if (d < 800) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Cyclone, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(36.dp)); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${s.classLabel} ${s.name}", style = MaterialTheme.typography.titleMedium)
                                Text("About $d mi ${Geo.compass(Geo.bearing(p.lat, p.lon, s.lat, s.lon))} of ${p.name}. Open the plain-language storm report.", style = MaterialTheme.typography.bodyMedium)
                            }
                            Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }
            }

            // ---------- Hourly ----------
            val now = System.currentTimeMillis()
            val hours = w?.hourly?.filter { it.timeMs >= now - 30 * 60_000L }?.take(24).orEmpty()
            if (hours.isNotEmpty()) Section("Next 24 hours") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hours) { h ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.width(72.dp).semantics(mergeDescendants = true) {
                            contentDescription = "${Units.clock(h.timeMs, p.timeZone)}, ${h.tempF?.roundToInt() ?: "unknown"} degrees, ${h.summary ?: ""}, ${h.precipChance?.roundToInt() ?: 0} percent chance of precipitation"
                        }) {
                            Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Units.clock(h.timeMs, p.timeZone).replace(":00", ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(weatherIcon(h.summary, h.isDay), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp).size(24.dp))
                                Text(Units.temp(h.tempF), style = MaterialTheme.typography.titleMedium)
                                Text(h.precipChance?.takeIf { it >= 10 }?.let { "${it.roundToInt()}%" } ?: " ", style = MaterialTheme.typography.labelSmall, color = Tw.colors.forecast)
                                Text(h.windMph?.let { "${it.roundToInt()} mph" } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                SourceLine("NWS hourly forecast", w?.forecastUpdatedMs ?: w?.fetchedAtMs, p.timeZone, stale = st.weather?.stale == true)
            }

            // ---------- Conditions ----------
            w?.conditions?.let { c ->
                Section("Current conditions") {
                    TwCard {
                        val rows = listOf(
                            Triple("Wind", c.windMph?.let { "${Units.mph(it)}${c.windDirDeg?.let { d -> " " + Geo.compass(d) } ?: ""}" } ?: "Calm / not reported", Icons.Outlined.Air),
                            Triple("Gusts", c.gustMph?.let { Units.mph(it) } ?: "None reported", Icons.Outlined.Storm),
                            Triple("Humidity", Units.pct(c.humidityPct), Icons.Outlined.WaterDrop),
                            Triple("Dew point", Units.temp(c.dewpointF), Icons.Outlined.Thermostat),
                            Triple("Visibility", Units.miles(c.visibilityMi), Icons.Outlined.Visibility),
                            Triple("Pressure", c.pressureInHg?.let { String.format("%.2f inHg", it) } ?: "—", Icons.Outlined.Speed),
                            Triple("Rain, last hour", c.precipLastHourIn?.let { Units.inches(it) } ?: "None reported", Icons.Outlined.Grain),
                            Triple("Feels like", Units.temp(c.feelsLikeF ?: c.tempF), Icons.Outlined.DeviceThermostat),
                        )
                        rows.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                pair.forEach { (label, value, icon) ->
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                                        Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleSmall) }
                                    }
                                }
                            }
                        }
                        w.sun?.let { s -> if (s.sunriseMs != null && s.sunsetMs != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WbTwilight, null, tint = Tw.colors.watch); Spacer(Modifier.width(6.dp))
                            Text("Sunrise ${Units.clock(s.sunriseMs, p.timeZone)}  ·  Sunset ${Units.clock(s.sunsetMs, p.timeZone)}", style = MaterialTheme.typography.bodyMedium)
                        } }
                    }
                    Spacer(Modifier.height(6.dp))
                    SourceLine("NWS station ${c.stationId ?: ""}${c.stationName?.let { " ($it)" } ?: ""}", c.observedAtMs, p.timeZone)
                }
            }

            // ---------- Daily ----------
            val days = groupDays(w?.daily.orEmpty())
            if (days.isNotEmpty()) Section("Forecast") {
                TwCard {
                    days.forEachIndexed { i, d ->
                        var open by remember(d.startMs) { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (i == 0 && d.name.isNotBlank()) d.name else Units.dayName(d.startMs, p.timeZone), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(88.dp))
                                Icon(weatherIcon(d.summary, true), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    d.precip?.takeIf { it > 0 }?.let { Text("${it.roundToInt()}% precip", style = MaterialTheme.typography.labelSmall, color = Tw.colors.forecast) }
                                }
                                Text("${Units.temp(d.high)} / ${Units.temp(d.low)}", style = MaterialTheme.typography.titleSmall)
                            }
                            if (open) Text(d.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                        if (i < days.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                SourceLine("NWS ${w?.forecastOffice ?: ""} forecast", w?.forecastUpdatedMs ?: w?.fetchedAtMs, p.timeZone, stale = st.weather?.stale == true)
            }

            // ---------- Severe weather outlook ----------
            st.outlook?.let { o ->
                Section("Severe-weather outlook") {
                    TwCard {
                        Text(NwsSource.outlookSummary(o.text), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        SourceLine("NWS ${o.office} Hazardous Weather Outlook", o.issuedMs, p.timeZone)
                    }
                }
            }

            // ---------- Ads (never while a warning is active) ----------
            if (!ent.premium && !urgent) {
                AdBanner(canRequest = vm.mon.canRequestAds(LocalContext.current))
                TextButton(onClick = onOpenPremium, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Remove ads") }
            }
            Text("Data from the National Weather Service and other U.S. government sources. Not an endorsement by any agency. For emergencies, follow official instructions and keep Wireless Emergency Alerts enabled.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picker) ModalBottomSheet(onDismissRequest = { picker = false }) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Switch place", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            places.forEach { pl ->
                ListItem(
                    headlineContent = { Text(pl.name) },
                    supportingContent = { Text(listOfNotNull(if (pl.isHome) "Home" else null, pl.state).joinToString(" · ")) },
                    leadingContent = { Icon(if (pl.isHome) Icons.Filled.Home else Icons.Filled.Place, null) },
                    trailingContent = { if (pl.id == p.id) Icon(Icons.Filled.Check, "Selected") },
                    modifier = Modifier.clickable { vm.selectPlace(pl.id); picker = false }.heightIn(min = 56.dp),
                )
            }
            ListItem(headlineContent = { Text("Add or manage places") }, leadingContent = { Icon(Icons.Filled.Add, null) }, modifier = Modifier.clickable { picker = false; onOpenPlaces() }.heightIn(min = 56.dp))
        }
    }
}

@Composable
fun isDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

@Composable
fun AlertRow(a: Alert, onClick: () -> Unit, tz: String?, area: String? = null) {
    TwCard(onClick = onClick, container = if (a.level == AlertLevel.WARNING) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeverityChip(a.level); Spacer(Modifier.width(8.dp))
            Text(a.event, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        Text(a.headline ?: a.areaDesc, style = MaterialTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
        val meta = listOfNotNull(area?.takeIf { it.isNotBlank() }, a.endMs?.let { "Until ${Units.dayClock(it, tz)}" }, a.sentMs?.let { "Issued ${Units.ago(it)}" }).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
