package com.mundymorning.touchweathertracker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.source.NwsSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(vm: AppViewModel, onOpenAlert: (String) -> Unit, onManageAreas: () -> Unit) {
    val st by vm.feed.collectAsState()
    val place by vm.selected.collectAsState()
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf<AlertLevel?>(null) }
    LaunchedEffect(Unit) { vm.loadFeed(st.scope) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Alerts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
        Text("Official National Weather Service warnings, watches and advisories.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(FeedScope.HOME to "Home", FeedScope.SAVED to "Saved places", FeedScope.FAVORITES to "Favorite areas", FeedScope.ALL to "All active").forEach { (s, label) ->
                FilterChip(selected = st.scope == s, onClick = { vm.loadFeed(s) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AlertLevel.values().forEach { l ->
                FilterChip(selected = level == l, onClick = { level = if (level == l) null else l }, label = { Text(l.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    leadingIcon = { Icon(sevStyle(l).icon, null, Modifier.size(16.dp)) }, modifier = Modifier.heightIn(min = 40.dp))
            }
        }
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Filter by place, county or hazard") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))

        val data = st.data
        val tz = place?.timeZone
        PullToRefreshBox(isRefreshing = st.loading, onRefresh = { vm.loadFeed(st.scope, true) }, modifier = Modifier.weight(1f)) {
            when {
                data == null && st.loading -> Loading()
                data?.value == null -> ErrorState(data?.error ?: "Alerts are unavailable.", { vm.loadFeed(st.scope, true) })
                else -> {
                    val all = data.value
                    val list = all.filter { (a, label) ->
                        (level == null || a.level == level) &&
                            (query.isBlank() || listOf(a.event, a.areaDesc, label, a.headline.orEmpty()).any { it.contains(query, true) })
                    }.sortedWith(compareBy({ it.first.level.ordinal }, { it.first.endMs ?: Long.MAX_VALUE }))
                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (data.stale) item { StaleBanner(data.error ?: "Couldn't refresh alerts.", data.fetchedAtMs, tz) { vm.loadFeed(st.scope, true) } }
                        if (st.scope == FeedScope.FAVORITES && vm.favorites.value.isEmpty()) item {
                            EmptyState(Icons.Outlined.StarOutline, "No favorite areas yet", "Follow a state or county — useful for keeping an eye on family and friends.") { Button(onClick = onManageAreas) { Text("Add a favorite area") } }
                        } else if (list.isEmpty()) item {
                            EmptyState(Icons.Outlined.CheckCircle, if (all.isEmpty()) "No active alerts found" else "Nothing matches your filters",
                                if (all.isEmpty()) "The National Weather Service has no active alerts for this selection right now. That does not mean a warning can never be issued — keep Wireless Emergency Alerts on."
                                else "Clear the filters to see all ${all.size} alerts.")
                        }
                        items(list, key = { it.first.id }) { (a, label) ->
                            AlertRow(a, onClick = { onOpenAlert(a.id) }, tz = tz, area = if (st.scope == FeedScope.ALL) a.states.joinToString(", ") else label)
                        }
                        item { SourceLine("National Weather Service", data.fetchedAtMs, tz, stale = data.stale); Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit, onShowOnMap: (Alert) -> Unit) {
    val idx by vm.alertIndex.collectAsState()
    val place by vm.selected.collectAsState()
    val a = idx[id]
    val tz = place?.timeZone
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Alert detail") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        if (a == null) { EmptyState(Icons.Outlined.EventBusy, "Alert no longer available", "This alert has expired, been cancelled, or replaced. Check the Alerts tab for current alerts.", Modifier.padding(pad)); return@Scaffold }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { SeverityChip(a.level); if (a.lifeSafety) { Spacer(Modifier.width(8.dp)); Text("Life-safety", style = MaterialTheme.typography.labelMedium, color = Tw.colors.warning) } }
            Text(a.event, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            a.headline?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))
            TwCard {
                DetailRow("Area", a.areaDesc)
                DetailRow("Issued", a.sentMs?.let { Units.stamp(it, tz) + " (${Units.ago(it)})" } ?: "—")
                a.onsetMs?.let { DetailRow("Begins", Units.stamp(it, tz)) }
                DetailRow("Expires", a.endMs?.let { Units.stamp(it, tz) + " (${Units.until(it)})" } ?: "Not specified")
                DetailRow("Severity", "${a.severity} · urgency ${a.urgency.lowercase()} · certainty ${a.certainty.lowercase()}")
                a.response?.takeIf { it != "None" }?.let { DetailRow("Recommended response", it) }
                DetailRow("Status", if (a.messageType == "Update") "Updated message" else a.messageType)
                DetailRow("Issued by", a.senderName)
            }
            if (a.instruction != null) { Section("What to do") { TwCard(container = MaterialTheme.colorScheme.tertiaryContainer) { Text(a.instruction, style = MaterialTheme.typography.bodyLarge) } } }
            Section("Official description") { Text(a.description.replace(Regex("(?<!\\n)\\n(?!\\n)"), " "), style = MaterialTheme.typography.bodyLarge) }
            if (a.polygons.isNotEmpty()) FilledTonalButton(onClick = { onShowOnMap(a) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Map, null); Spacer(Modifier.width(8.dp)); Text("Show warned area on map") }
            Spacer(Modifier.height(12.dp))
            SourceLine("National Weather Service (api.weather.gov)", a.sentMs, tz)
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
