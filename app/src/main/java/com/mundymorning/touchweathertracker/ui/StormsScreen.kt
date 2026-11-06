package com.mundymorning.touchweathertracker.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mundymorning.touchweathertracker.billing.PremiumFeature
import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.Place
import com.mundymorning.touchweathertracker.domain.*
import com.mundymorning.touchweathertracker.source.ActiveStorm
import com.mundymorning.touchweathertracker.source.TrackLine
import com.mundymorning.touchweathertracker.work.EventActionReceiver
import com.mundymorning.touchweathertracker.work.Notifier
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StormsScreen(vm: AppViewModel, onOpenStorm: (String) -> Unit) {
    val st by vm.storms.collectAsState()
    val place by vm.selected.collectAsState()
    val tick by vm.prefsTick.collectAsState()
    LaunchedEffect(Unit) { vm.loadStorms() }
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.prefs.notificationsAsked = true; vm.prefs.bump() }
    val storms = st.storms?.value.orEmpty()
    val active = remember(tick) { vm.prefs.eventModeActiveStorm }

    PullToRefreshBox(isRefreshing = st.loading, onRefresh = { vm.loadStorms(true) }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
            Text("Storms", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
            Text("Tropical cyclones from the National Hurricane Center.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))

            st.storms?.takeIf { it.stale }?.let { StaleBanner(it.error ?: "Couldn't refresh NHC data.", it.fetchedAtMs, place?.timeZone) { vm.loadStorms(true) } }

            // ---- Event mode ----
            val ev = st.event
            val eligible = ev?.eligibleStorm
            if (active != null) {
                val s = storms.firstOrNull { it.id == active }
                if (eligible?.id == active || ev?.hurricaneWarningInEffect == true) {
                    Section("Event mode") { TwCard(container = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("Event mode is on for ${s?.name ?: active.uppercase()}", style = MaterialTheme.typography.titleSmall)
                        Text("Your saved places' storm information and urgent app alerts refresh about every 15 minutes (Android may delay this). Wireless Emergency Alerts are separate and still the most reliable way to be warned.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.eventAction(EventActionReceiver.ACTION_END, active) }) { Text("Return to normal") }
                    } }
                } else if (active !in vm.prefs.eventModeEndPromptDismissed) {
                    Section("Event mode") { TwCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("${s?.name ?: "The storm"} is no longer expected to affect your places", style = MaterialTheme.typography.titleSmall)
                        Text("Official forecasts no longer show a U.S. hurricane landfall and related warnings have ended or aren't in effect for your places. Return to normal refresh settings, or keep event mode on?", style = MaterialTheme.typography.bodyMedium)
                        Row { TextButton(onClick = { vm.eventAction(EventActionReceiver.ACTION_END, active) }) { Text("Return to normal") }; TextButton(onClick = { vm.eventAction(EventActionReceiver.ACTION_KEEP, active) }) { Text("Keep event mode") } }
                    } }
                } else Section("Event mode") { TwCard { Text("Event mode is on for ${s?.name ?: active.uppercase()}.", style = MaterialTheme.typography.titleSmall); TextButton(onClick = { vm.eventAction(EventActionReceiver.ACTION_END, active) }) { Text("Return to normal") } } }
            } else if (eligible != null && vm.prefs.eventModeOffers) {
                var details by remember { mutableStateOf(false) }
                val declined = eligible.id in vm.prefs.eventModeDeclined
                Section("Hurricane event mode") { TwCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Cyclone, null); Spacer(Modifier.width(8.dp)); Text("${eligible.name} may make U.S. landfall as a hurricane", style = MaterialTheme.typography.titleSmall) }
                    Text(ev.reason.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    Text("Event mode helps the app refresh your saved places' storm information and urgent app alerts more often during this event. Android controls background work, so this is never instant or guaranteed.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    if (details) Text("• Uses your saved places and official NHC/NWS products — no continuous GPS tracking.\n• Refreshes about every 15 minutes instead of 30; Android may delay or batch it.\n• Needs notification permission to alert you.\n• You'll be asked again when the storm no longer threatens your places. You can turn it off in Settings any time.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = {
                            vm.eventAction(EventActionReceiver.ACTION_ENABLE, eligible.id)
                            if (!Notifier.canPost(ctx) && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("Enable for this event") }
                        TextButton(onClick = { vm.eventAction(EventActionReceiver.ACTION_DECLINE, eligible.id) }) { Text(if (declined) "Declined" else "Not now") }
                        TextButton(onClick = { details = !details }) { Text("Details") }
                    }
                } }
            }

            // ---- Storm list ----
            if (st.storms == null && st.loading) Loading(label = "Checking the National Hurricane Center…")
            else if (st.storms?.value == null) ErrorState(st.storms?.error ?: "Storm data is unavailable.", { vm.loadStorms(true) })
            else if (storms.isEmpty()) {
                EmptyState(Icons.Outlined.WbSunny, "No active tropical cyclones", "The National Hurricane Center is not currently issuing advisories for any tropical storm or hurricane. This does not mean other hazards are absent — check Alerts.")
                Section("Outlook") { LinkButton("NHC Tropical Weather Outlook", "https://www.nhc.noaa.gov/gtwo.php"); Spacer(Modifier.height(8.dp)); LinkButton("Hurricane preparedness (ready.gov)", "https://www.ready.gov/hurricanes") }
            } else {
                storms.sortedByDescending { it.intensityKt ?: 0 }.forEach { s ->
                    Section(s.classLabel + " " + s.name) {
                        TwCard(onClick = { onOpenStorm(s.id) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Cyclone, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(40.dp)); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(listOfNotNull(ActiveStorm.saffir(s.intensityKt), s.intensityKt?.let { "${StormReportBuilder.ktToMph(it)} mph winds" }, s.pressureMb?.let { "$it mb" }).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                                    place?.let { p -> Text("${Geo.distanceMiles(p.lat, p.lon, s.lat, s.lon).roundToInt()} mi ${Geo.compass(Geo.bearing(p.lat, p.lon, s.lat, s.lon))} of ${p.name}", style = MaterialTheme.typography.bodyMedium) }
                                    if (s.moveDirDeg != null && s.moveSpeedMph != null) Text("Moving ${Geo.compass(s.moveDirDeg.toDouble())} at ${s.moveSpeedMph} mph", style = MaterialTheme.typography.bodySmall)
                                    Text("Advisory ${s.advisoryNumber ?: "—"} · ${s.advisoryIssuedMs?.let { Units.dayClock(it, place?.timeZone) } ?: "time unknown"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Filled.ChevronRight, null)
                            }
                            Text("Open the storm report for ${place?.name ?: "your place"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            st.storms?.fetchedAtMs?.let { SourceLine("National Hurricane Center (CurrentStorms.json)", it, place?.timeZone, Modifier.padding(horizontal = 16.dp), stale = st.storms?.stale == true) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StormDetailScreen(vm: AppViewModel, stormId: String, onBack: () -> Unit, onShowOnMap: (ActiveStorm) -> Unit, onOpenPremium: () -> Unit) {
    val st by vm.storms.collectAsState()
    val bundles by vm.bundles.collectAsState()
    val loading by vm.bundleLoading.collectAsState()
    val reports by vm.reports.collectAsState()
    val places by vm.places.collectAsState()
    val selected by vm.selected.collectAsState()
    val ent by vm.mon.state.collectAsState()
    val ctx = LocalContext.current
    var reportPlace by remember { mutableStateOf<Place?>(null) }
    val storm = st.storms?.value?.firstOrNull { it.id == stormId }
    val place = reportPlace ?: selected
    LaunchedEffect(Unit) { if (st.storms == null) vm.loadStorms() }
    LaunchedEffect(storm?.id) { storm?.let { vm.loadBundle(it) } }
    val bundle = bundles[stormId]
    LaunchedEffect(bundle?.fetchedAtMs, place?.id) { if (bundle != null && place != null) vm.loadReport(place, bundle) }
    val report = place?.let { reports["$stormId|${it.id}"] }
    var details by remember { mutableStateOf(false) }
    var pickPlace by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(storm?.let { "${it.classLabel} ${it.name}" } ?: "Storm") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { storm?.let { IconButton(onClick = { onShowOnMap(it) }) { Icon(Icons.Outlined.Map, "Show on map") } } }) }) { pad ->
        if (storm == null) {
            if (st.loading || st.storms == null) Loading(Modifier.padding(pad)) else EmptyState(Icons.Outlined.WbSunny, "This storm is no longer active", "NHC has stopped issuing advisories for it.", Modifier.padding(pad))
            return@Scaffold
        }
        val tz = place?.timeZone
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            // Header facts
            TwCard(Modifier.padding(16.dp)) {
                Text(listOfNotNull(ActiveStorm.saffir(storm.intensityKt), storm.intensityKt?.let { "${StormReportBuilder.ktToMph(it)} mph max winds" }, storm.pressureMb?.let { "$it mb" }).joinToString(" · ").ifBlank { storm.classLabel }, style = MaterialTheme.typography.titleMedium)
                Text("Center: %.1f°, %.1f°".format(storm.lat, storm.lon), style = MaterialTheme.typography.bodyMedium)
                if (storm.moveDirDeg != null && storm.moveSpeedMph != null) Text("Moving ${Geo.compass(storm.moveDirDeg.toDouble())} at ${storm.moveSpeedMph} mph", style = MaterialTheme.typography.bodyMedium)
                SourceLine("NHC advisory ${storm.advisoryNumber ?: ""}", storm.advisoryIssuedMs, tz, Modifier.padding(top = 6.dp))
            }

            // Place chooser
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Storm report for", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                AssistChip(onClick = { pickPlace = true }, label = { Text(place?.name ?: "Choose a place") }, trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) }, modifier = Modifier.heightIn(min = 48.dp))
            }

            if (place == null) EmptyState(Icons.Outlined.Place, "Add a place", "Storm reports are written for a specific place.")
            else if (report == null) { Loading(label = if (bundle == null) "Gathering NHC products…" else "Checking buoys, water gauges and aircraft data…") }
            else {
                Text(report.headline, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (!report.threatens) Text("Based on the official forecast, ${storm.name} is not currently expected to affect ${place.name}. Conditions can change; check each advisory.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                report.sections.forEach { sec ->
                    Section(sec.title) {
                        Text(sec.question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        if (sec.items.isEmpty()) Text(sec.emptyNote ?: "Nothing to report.", style = MaterialTheme.typography.bodyMedium)
                        sec.items.forEach { it -> ReportItemCard(it, tz); Spacer(Modifier.height(8.dp)) }
                    }
                }
                Section("Details & sources", action = { TextButton(onClick = { details = !details }) { Text(if (details) "Hide" else "Show") } }) {
                    if (details) TwCard { report.details.forEach { DetailRow(it.label, it.value) }; SourceLine("Report generated on this device", report.generatedMs, tz) }
                }
            }

            // Official forecast table
            bundle?.official?.let { t ->
                Section("NHC official forecast points") {
                    TwCard { t.points.forEach { p -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("+${p.tauHours} h", Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge)
                        Text(Units.dayClock(t.initMs + p.tauHours * 3_600_000L, tz), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(p.vmaxKt?.let { "${StormReportBuilder.ktToMph(it)} mph" } ?: "—", style = MaterialTheme.typography.bodyMedium)
                    } }
                    SourceLine("NHC official forecast (initialized ${Units.stamp(t.initMs, tz)})", t.initMs, tz) }
                }
            }

            // Model guidance (separate, labeled)
            Section("Model guidance — not the official forecast") {
                if (!ent.premium) {
                    TwCard { Text("Compare GFS and ECMWF tracks with the NHC forecast.", style = MaterialTheme.typography.titleSmall); Text("This is a premium feature (available with 4 ad views for 24 hours, the ad-free purchase, or the subscription).", style = MaterialTheme.typography.bodyMedium); TextButton(onClick = onOpenPremium) { Text("See options") } }
                } else {
                    ModelTrackCard("GFS (NOAA)", bundle?.gfs, bundle?.official, tz)
                    Spacer(Modifier.height(8.dp))
                    if (bundle?.ecmwf != null) ModelTrackCard("ECMWF (European)", bundle.ecmwf, bundle.official, tz)
                    else TwCard { Text("ECMWF (European)", style = MaterialTheme.typography.titleSmall); Text("No ECMWF track is available for this storm from the free open-data source right now, so none is shown. Nothing is estimated.", style = MaterialTheme.typography.bodyMedium) }
                    Text("Model tracks are single computer runs. They can differ from each other and from NHC's forecast, and they don't indicate consensus or certainty. Use the NHC forecast and your local NWS office for decisions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // NHC text
            bundle?.highlights?.takeIf { it.isNotEmpty() }?.let { hl ->
                Section("From the NHC public advisory") {
                    hl.take(5).forEach { (h, b) -> TwCard { Text(h, style = MaterialTheme.typography.titleSmall); Text(b, style = MaterialTheme.typography.bodyMedium) }; Spacer(Modifier.height(8.dp)) }
                }
            }
            Section("Official NHC products") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    storm.publicAdvisoryUrl?.let { LinkButton("Public advisory", it, Modifier.fillMaxWidth()) }
                    storm.discussionUrl?.let { LinkButton("Forecast discussion", it, Modifier.fillMaxWidth()) }
                    storm.graphicsUrl?.let { LinkButton("Forecast graphics", it, Modifier.fillMaxWidth()) }
                    storm.windProbUrl?.let { LinkButton("Wind speed probabilities", it, Modifier.fillMaxWidth()) }
                    LinkButton("Peak storm surge forecast", "https://www.nhc.noaa.gov/surge/", Modifier.fillMaxWidth())
                }
            }
            TwCard(Modifier.padding(16.dp)) {
                Text("About the cone", style = MaterialTheme.typography.titleSmall)
                Text("The NHC cone shows the probable track of the storm's CENTER. It does not show the storm's size, or where wind, rain, flooding and storm surge can occur — those can extend hundreds of miles from the center.", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickPlace) ModalBottomSheet(onDismissRequest = { pickPlace = false }) {
        Column(Modifier.padding(bottom = 24.dp)) {
            places.forEach { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text(listOfNotNull(if (p.isHome) "Home" else null, p.state).joinToString(" · ")) },
                modifier = Modifier.clickable { reportPlace = p; pickPlace = false }.heightIn(min = 56.dp)) }
        }
    }
}

@Composable
private fun ReportItemCard(item: ReportItem, tz: String?) {
    val ctx = LocalContext.current
    TwCard {
        Row(verticalAlignment = Alignment.CenterVertically) { KindChip(item.kind); Spacer(Modifier.width(8.dp)); Text(item.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
        Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        item.timeMs?.let { Text("As of ${Units.stamp(it, tz)} (${Units.ago(it)})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        item.link?.let { TextButton(onClick = { ctx.openUrl(it) }) { Text("Open official product") } }
    }
}

@Composable
private fun ModelTrackCard(title: String, track: TrackLine?, official: TrackLine?, tz: String?) {
    TwCard {
        Row(verticalAlignment = Alignment.CenterVertically) { KindChip(Kind.MODEL); Spacer(Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.titleSmall) }
        if (track == null) { Text("No track from this model is available for this storm right now.", style = MaterialTheme.typography.bodyMedium); return@TwCard }
        Text("Run initialized ${Units.stamp(track.initMs, tz)} (${Units.ago(track.initMs)}).", style = MaterialTheme.typography.bodySmall)
        val gap = official?.let { o -> val at = track.points.lastOrNull { it.tauHours <= (o.points.lastOrNull()?.tauHours ?: 0) } ; val o2 = at?.let { a -> o.points.minByOrNull { kotlin.math.abs(it.tauHours - a.tauHours) } }
            if (at != null && o2 != null && o2.tauHours == at.tauHours) Geo.distanceMiles(at.lat, at.lon, o2.lat, o2.lon).roundToInt() to at.tauHours else null }
        gap?.let { Text("At +${it.second} h this model's center is about ${it.first} mi from NHC's official forecast point.", style = MaterialTheme.typography.bodyMedium) }
        track.points.filter { it.tauHours % 24 == 0 }.forEach { p -> Text("+${p.tauHours} h · %.1f°, %.1f°${p.vmaxKt?.let { " · ${StormReportBuilder.ktToMph(it)} mph" } ?: ""}".format(p.lat, p.lon), style = MaterialTheme.typography.bodySmall) }
    }
}

