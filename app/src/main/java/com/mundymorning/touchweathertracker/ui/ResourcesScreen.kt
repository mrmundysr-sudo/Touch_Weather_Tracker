package com.mundymorning.touchweathertracker.ui

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
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.Place
import com.mundymorning.touchweathertracker.data.UtilityChoice
import com.mundymorning.touchweathertracker.source.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcesScreen(vm: AppViewModel, onOpenPower: () -> Unit) {
    val place by vm.selected.collectAsState()
    val st by vm.resources.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(place?.id) { vm.loadResources() }
    val p = place
    PullToRefreshBox(isRefreshing = st.loading, onRefresh = { vm.loadResources(true) }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
            Text("Resources", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
            if (p == null) { EmptyState(Icons.Outlined.Place, "Add a place", "Resources are shown for the place you select."); return@Column }
            Text("Help near ${p.name}${p.state?.let { ", $it" } ?: ""}. Listings come from official sources and are only shown as open when the source says so — always call to confirm.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))

            // ---- Power ----
            Section("Power outage") { TwCard(onClick = onOpenPower) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.PowerOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("Your electric utility, outage map and report-an-outage", style = MaterialTheme.typography.titleSmall); Text(p.utility?.let { "Using ${it.name}" } ?: "We'll identify the likely utility for ${p.name}.", style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Filled.ChevronRight, null) }
            } }

            if (st.loading && st.bundle == null) Loading(label = "Looking up FEMA and shelter information…")

            val b = st.bundle
            // ---- FEMA assistance ----
            Section("FEMA disaster assistance") {
                when {
                    b == null -> {}
                    b.declarationsError != null -> ErrorState(b.declarationsError, { vm.loadResources(true) })
                    b.declarations.isEmpty() -> TwCard {
                        Text("No open federal disaster declaration found for ${p.countyName ?: p.name}${p.state?.let { ", $it" } ?: ""}.", style = MaterialTheme.typography.titleSmall)
                        Text("FEMA assistance requires a presidential declaration for your area. If you were affected by a recent disaster, check with your state or local emergency management and your insurer.", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> b.declarations.take(6).forEach { d -> DeclarationCard(d, p); Spacer(Modifier.height(8.dp)) }
                }
                TwCard {
                    Text("How to apply", style = MaterialTheme.typography.titleSmall)
                    Text("Individuals and households can apply for FEMA assistance when their county is designated for Individual Assistance: online at DisasterAssistance.gov, by phone at 1-800-621-3362, or at a Disaster Recovery Center. Eligibility, programs and deadlines depend on the declaration and your circumstances — FEMA makes the determination.", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { LinkButton("DisasterAssistance.gov", "https://www.disasterassistance.gov"); OutlinedButton(onClick = { ctx.dial("18006213362") }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Call FEMA") } }
                }
                b?.let { SourceLine("OpenFEMA declarations", it.fetchedAtMs, p.timeZone) }
            }

            // ---- Shelters ----
            Section("Shelters") {
                if (b == null) {} else if (b.sheltersError != null) ErrorState(b.sheltersError, { vm.loadResources(true) })
                else if (b.shelters.isEmpty()) TwCard {
                    Text("No open shelters reported within 75 miles.", style = MaterialTheme.typography.titleSmall)
                    Text("This feed lists shelters opened through FEMA/American Red Cross reporting. Local and state shelters may not be listed — call 211 or your county emergency management office.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { ctx.dial("211") }, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) { Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Call 211") }
                } else {
                    PetLegend()
                    b.shelters.take(15).forEach { s -> ShelterCard(s); Spacer(Modifier.height(8.dp)) }
                    if (b.shelters.size > 15) Text("Showing the nearest 15 of ${b.shelters.size}.", style = MaterialTheme.typography.bodySmall)
                }
                b?.let { SourceLine("FEMA ESF-6 shelter feed (reported status; list may be incomplete)", it.fetchedAtMs, p.timeZone) }
            }

            // ---- Recovery centers ----
            Section("Disaster Recovery Centers") {
                if (b == null) {} else if (b.centersError != null) ErrorState(b.centersError, { vm.loadResources(true) })
                else if (b.centers.isEmpty()) TwCard { Text("No open Disaster Recovery Centers within 100 miles.", style = MaterialTheme.typography.titleSmall); Text("Recovery centers open only after a declaration. Check DisasterAssistance.gov for new locations.", style = MaterialTheme.typography.bodyMedium) }
                else b.centers.take(10).forEach { c -> CenterCard(c); Spacer(Modifier.height(8.dp)) }
                b?.let { SourceLine("FEMA Disaster Recovery Center feed", it.fetchedAtMs, p.timeZone) }
            }

            // ---- Food / water / charging ----
            Section("Food, water & charging") {
                TwCard {
                    Text("Not verified in this app", style = MaterialTheme.typography.titleSmall)
                    Text("There is no single official, up-to-date nationwide feed of distribution points, so Touch Weather Tracker does not list individual sites as open. Shelters and Disaster Recovery Centers above often provide supplies and charging. To find current sites, use these and call ahead:", style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ctx.dial("211") }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Dial 211 — local help line") }
                        LinkButton("Find a food bank (Feeding America)", "https://www.feedingamerica.org/find-your-local-foodbank", Modifier.fillMaxWidth())
                        LinkButton("American Red Cross: find help", "https://www.redcross.org/get-help.html", Modifier.fillMaxWidth())
                        LinkButton("Ready.gov: after a disaster", "https://www.ready.gov", Modifier.fillMaxWidth())
                    }
                }
            }

            // ---- Blue Roof ----
            Section("USACE Operation Blue Roof") {
                TwCard {
                    val ms = st.blueRoof
                    if (st.blueRoofError != null) Text("Couldn't check for active missions right now.", style = MaterialTheme.typography.titleSmall)
                    else if (ms == null) Text("Checking for an active mission…", style = MaterialTheme.typography.titleSmall)
                    else if (ms.isEmpty()) {
                        Text("No active Blue Roof mission found for ${p.state ?: "your state"}.", style = MaterialTheme.typography.titleSmall)
                        Text("Operation Blue Roof installs free temporary plastic sheeting on storm-damaged roofs, but only after FEMA assigns the U.S. Army Corps of Engineers a mission for a declared disaster. No crew will respond unless a mission is active for your county.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("A FEMA mission for temporary roofing exists in ${ms.first().state}.", style = MaterialTheme.typography.titleSmall)
                        ms.take(3).forEach { m -> Text("DR-${m.disasterNumber} ${m.incident}${m.endMs?.let { " · period through ${Units.stamp(it).substringBefore(',')}" } ?: ""}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                        Text("This shows a mission assignment exists — not that your county or home is eligible. Confirm eligibility and sign up with USACE; apply for FEMA assistance too.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinkButton("USACE Blue Roof", "https://www.usace.army.mil/Missions/Emergency-Operations/Blue-Roof/")
                        OutlinedButton(onClick = { ctx.dial("18886978322") }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("1-888-ROOF-BLU") }
                    }
                    Text("Source: OpenFEMA mission assignments (USACE). The USACE site is the authority on eligibility and the sign-up process.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PetLegend() {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { PetBadge(PetStatus.ALLOWED); Text("Pets allowed", style = MaterialTheme.typography.labelSmall) }
        Row(verticalAlignment = Alignment.CenterVertically) { PetBadge(PetStatus.NOT_ALLOWED); Text("Not allowed", style = MaterialTheme.typography.labelSmall) }
        Row(verticalAlignment = Alignment.CenterVertically) { PetBadge(PetStatus.UNKNOWN); Text("Not reported", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun ShelterCard(s: Shelter) {
    val ctx = LocalContext.current
    TwCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall)
                Text(listOf(s.address, s.city, s.state, s.zip).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                Text("${"%.1f".format(s.distanceMi)} mi · Reported status: ${s.status.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodySmall)
                s.org?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (s.population != null || s.capacity != null) Text(listOfNotNull(s.population?.let { "Occupants: $it" }, s.capacity?.let { "Capacity: $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                s.wheelchair?.let { Text(if (it) "Wheelchair accessible (reported)" else "Wheelchair access not reported as accessible", style = MaterialTheme.typography.bodySmall) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { PetBadge(s.pet); Text(when (s.pet) { PetStatus.ALLOWED -> "Pets OK"; PetStatus.NOT_ALLOWED -> "No pets"; else -> "Pets: not reported" }, style = MaterialTheme.typography.labelSmall) }
        }
        Text("Availability can change quickly — call to confirm before traveling. Shelter hours and capacity are as last reported to FEMA.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { ctx.navigateTo(s.lat, s.lon, s.name) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Directions") }
            if (s.phone != null) OutlinedButton(onClick = { ctx.dial(s.phone) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Call") }
            else Text("No phone listed — call 211", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun CenterCard(c: RecoveryCenter) {
    val ctx = LocalContext.current
    TwCard {
        Text(c.name, style = MaterialTheme.typography.titleSmall)
        Text(listOf(c.address, c.city, c.state).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        Text("${"%.1f".format(c.distanceMi)} mi · Reported status: ${c.status}${c.disasterNumber?.let { " · DR-$it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        c.hours?.let { Text("Hours: $it", style = MaterialTheme.typography.bodySmall) }
        c.updatedMs?.let { Text("Last reported ${Units.ago(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { ctx.navigateTo(c.lat, c.lon, c.name) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Directions") }
            OutlinedButton(onClick = { ctx.dial("18006213362") }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Call FEMA to confirm") }
        }
    }
}

@Composable
private fun DeclarationCard(d: Declaration, p: Place) {
    TwCard {
        Text("${d.declarationString} · ${d.title.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.titleSmall)
        Text("${d.area} · declared ${d.declaredMs?.let { Units.stamp(it).substringBefore(',') } ?: "—"}", style = MaterialTheme.typography.bodySmall)
        val programs = listOfNotNull(if (d.individualAssistance) "Individual Assistance" else null, if (d.householdProgram) "Individuals & Households Program" else null, if (d.publicAssistance) "Public Assistance (governments & nonprofits)" else null, if (d.hazardMitigation) "Hazard Mitigation" else null)
        Text(if (programs.isEmpty()) "Programs for this area haven't been announced yet." else "Declared programs: ${programs.joinToString(" · ")}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        d.iaDeadlineMs?.takeIf { d.individualAssistance || d.householdProgram }?.let { Text("Registration deadline: ${Units.stamp(it).substringBefore(',')}", style = MaterialTheme.typography.titleSmall, color = Tw.colors.watch, modifier = Modifier.padding(top = 4.dp)) }
        Text("Eligibility is decided by FEMA and varies by declaration, county and your situation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerScreen(vm: AppViewModel, onBack: () -> Unit) {
    val place by vm.selected.collectAsState()
    val st by vm.power.collectAsState()
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(place?.id) { vm.loadPower() }
    val p = place
    Scaffold(topBar = { TopAppBar(title = { Text("Power outages") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        if (p == null) { EmptyState(Icons.Outlined.Place, "Add a place", "Power information is shown for a place.", Modifier.padding(pad)); return@Scaffold }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val b = st.bundle
            val util = p.utility ?: b?.utilities?.firstOrNull()
            TwCard {
                Text("Your electric utility", style = MaterialTheme.typography.titleMedium)
                if (st.loading && b == null) Loading()
                else if (util == null) {
                    Text(if (b?.utilitiesError != null) "Couldn't look up the utility for ${p.name}." else "We couldn't identify a utility for ${p.name}.", style = MaterialTheme.typography.bodyMedium)
                    Text("Enter it yourself so you can reach the right outage map and phone number.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(util.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (util.manual) "Chosen by you." else "Likely provider based on public service-area data. Territory boundaries are approximate — if this looks wrong, correct it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        util.website?.let { LinkButton("Utility website / outage map", it, Modifier.fillMaxWidth()) }
                        util.phone?.let { OutlinedButton(onClick = { ctx.dial(it) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Call $it") } }
                        LinkButton("Search “${util.name} report outage”", "https://www.google.com/search?q=" + java.net.URLEncoder.encode("${util.name} report power outage", "UTF-8"), Modifier.fillMaxWidth(), Icons.Outlined.Search)
                    }
                    if (util.website == null || util.phone == null) Text("Some contact details aren't published in the service-area data. Check your latest electric bill for your utility's outage line.", style = MaterialTheme.typography.bodySmall)
                }
                Row { if ((b?.utilities?.size ?: 0) > 1) TextButton(onClick = { pick = true }) { Text("Not right? Choose another") }; TextButton(onClick = { manual = true }) { Text("Enter manually") }; if (p.utility != null) TextButton(onClick = { vm.setUtility(p, null) }) { Text("Reset") } }
                Text("Always report outages and get restoration estimates from your utility — it is the primary source.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                b?.let { SourceLine("Electric retail service territories (HIFLD)", it.fetchedAtMs, p.timeZone) }
            }

            // ---- MassOutage county ----
            TwCard {
                Text("County outage overview (unofficial)", style = MaterialTheme.typography.titleMedium)
                val c = b?.county
                if (b == null) Loading()
                else if (c == null) Text(if (b.countyError != null) "Couldn't reach MassOutage. Use your utility's outage map above." else "MassOutage doesn't have county coverage for this location. That doesn't mean there are no outages — use your utility's outage map.", style = MaterialTheme.typography.bodyMedium)
                else {
                    val coverage = c.customersTracked > 0
                    if (!coverage) Text("No utility feeds are tracked for this county — outages can't be shown here. This is missing coverage, not zero outages.", style = MaterialTheme.typography.bodyMedium)
                    else {
                        Text("${"%,d".format(c.customersOut)} customers out in ${p.countyName ?: "this county"}", style = MaterialTheme.typography.titleLarge)
                        Text("${"%.2f".format(c.pct)}% of ${"%,d".format(c.customersTracked)} tracked customers · ${c.incidents} incident${if (c.incidents == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
                        c.utilities.forEach { (n, out) -> Text("• $n: ${"%,d".format(out)} out", style = MaterialTheme.typography.bodySmall) }
                    }
                    val status = c.liveStatus
                    Text("Data as of ${c.asOfMs?.let { Units.stamp(it, p.timeZone) } ?: "unknown"}${c.behindMin?.let { " · feed ~$it min behind" } ?: ""}${if (status != null && status != "ok") " · feed status: $status — may be stale" else ""}", style = MaterialTheme.typography.bodySmall, color = if (status != null && status != "ok") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Unofficial and supplemental. Not a substitute for your utility.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { ctx.openUrl("https://massoutage.com") }) { Text("MassOutage (massoutage.com) · CC BY 4.0") }
            }

            // ---- National ----
            TwCard {
                Text("Nationwide overview (unofficial)", style = MaterialTheme.typography.titleMedium)
                val n = st.national
                if (n == null) Loading() else if (n.value == null) Text("Couldn't load the national overview.", style = MaterialTheme.typography.bodyMedium)
                else {
                    val v = n.value
                    if (n.stale) Text("Showing saved data — not current.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    Text("${"%,d".format(v.customersOut)} customers out across ${v.counties} counties", style = MaterialTheme.typography.titleLarge)
                    Text("Tracking ${v.sourcesOk} of ${v.sources} utility feeds (${"%,d".format(v.trackedCustomers)} customers). ${"%,d".format(v.notLocalizedOut)} outages can't be assigned to a county.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    v.states.take(8).forEach { s -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("${"%,d".format(s.customersOut)}", style = MaterialTheme.typography.titleSmall) } }
                    Text("Top 8 states by customers out. States with no tracked feeds are not shown as zero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SourceLine("MassOutage (massoutage.com)", v.asOfMs, p.timeZone, stale = v.stale)
                }
            }

            if (p.state == "TX") TwCard {
                Text("Texas grid conditions (ERCOT)", style = MaterialTheme.typography.titleMedium)
                Text("ERCOT reports statewide power-grid supply and demand. It is not a source for household outages — use your utility for those.", style = MaterialTheme.typography.bodyMedium)
                LinkButton("ERCOT grid and market conditions", "https://www.ercot.com/gridmktinfo/dashboards", Modifier.padding(top = 6.dp))
            }
        }
    }
    if (pick) AlertDialog(onDismissRequest = { pick = false }, title = { Text("Choose your utility") }, text = {
        Column { st.bundle?.utilities.orEmpty().forEach { u -> ListItem(headlineContent = { Text(u.name) }, supportingContent = { Text(u.phone ?: "") }, modifier = Modifier.heightIn(min = 56.dp).then(Modifier.clickable2 { vm.setUtility(p!!, u.copy(manual = true)); pick = false })) } }
    }, confirmButton = { TextButton(onClick = { pick = false }) { Text("Close") } })
    if (manual) {
        var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }; var web by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { manual = false }, title = { Text("Enter your utility") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Utility name") }, singleLine = true); OutlinedTextField(phone, { phone = it }, label = { Text("Outage phone (optional)") }, singleLine = true); OutlinedTextField(web, { web = it }, label = { Text("Website (optional)") }, singleLine = true) } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.setUtility(p!!, UtilityChoice(name.trim(), phone.trim().ifBlank { null }, web.trim().ifBlank { null }?.let { if (it.startsWith("http")) it else "https://$it" }, p.state, true)); manual = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } })
    }
}

private fun Modifier.clickable2(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
