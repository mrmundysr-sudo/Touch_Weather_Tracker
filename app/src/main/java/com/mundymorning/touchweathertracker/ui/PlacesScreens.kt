package com.mundymorning.touchweathertracker.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.source.CensusSource
import com.mundymorning.touchweathertracker.source.NwsSource
import kotlinx.coroutines.launch

@Composable
fun rememberLocationAction(vm: AppViewModel, asHome: Boolean, onResult: (Place?) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val go: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                val loc = LocationHelper.current(ctx)
                if (loc == null) { vm.toast("Couldn't get your location. Check that location is on, or search for an address instead."); busy = false; onResult(null) }
                else vm.addDevice(loc.latitude, loc.longitude, asHome) { busy = false; onResult(it) }
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) go() else vm.toast("Location permission was declined. You can still add places by address, ZIP or city.")
    }
    return { launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPlaceScreen(vm: AppViewModel, asHome: Boolean, onBack: () -> Unit, onDone: (Place) -> Unit, onPinOnMap: () -> Unit) {
    val s by vm.search.collectAsState()
    val places by vm.places.collectAsState()
    DisposableEffect(Unit) { onDispose { vm.clearSearch() } }
    val useLocation = rememberLocationAction(vm, asHome) { p -> if (p != null) onDone(p) }
    Scaffold(topBar = { TopAppBar(title = { Text(if (asHome) "Set Home" else "Add a place") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            OutlinedTextField(s.query, vm::onSearchQuery, singleLine = true, label = { Text("Address, ZIP code or city") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                supportingText = { Text("Examples: 77002 · Miami, FL · 1600 Pennsylvania Ave NW, Washington DC") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = useLocation, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Use my location") }
                OutlinedButton(onClick = onPinOnMap, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.PinDrop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Drop a pin") }
            }
            when {
                s.loading -> Loading(label = "Searching U.S. Census data…")
                s.error != null -> ErrorState(s.error!!, { vm.onSearchQuery(s.query) })
                s.searched && s.results.isEmpty() -> EmptyState(Icons.Outlined.SearchOff, "No U.S. matches", "Check the spelling, add the state (e.g. “Springfield, IL”), or try a ZIP code. Touch Weather Tracker covers U.S. locations.")
                else -> LazyColumn {
                    items(s.results) { g ->
                        ListItem(
                            headlineContent = { Text(g.label) },
                            supportingContent = { Text(listOfNotNull(g.kind, g.subtitle).joinToString(" · ")) },
                            leadingContent = { Icon(when (g.kind) { "Address" -> Icons.Outlined.Home; "ZIP code" -> Icons.Outlined.Mail; else -> Icons.Outlined.LocationCity }, null) },
                            modifier = Modifier.clickable() { vm.addFromResult(g, asHome, onDone) }.heightIn(min = 56.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Searches use free U.S. Census Bureau services. Your search text is sent to the Census Bureau; nothing is sent to any other party.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(vm: AppViewModel, onBack: () -> Unit, onAdd: (Boolean) -> Unit, onFavorites: () -> Unit, onOpenPlace: () -> Unit) {
    val places by vm.places.collectAsState()
    val favs by vm.favorites.collectAsState()
    var edit by remember { mutableStateOf<Place?>(null) }
    var rename by remember { mutableStateOf<Place?>(null) }
    var remove by remember { mutableStateOf<Place?>(null) }
    var pickUtil by remember { mutableStateOf<Place?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Places") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { onAdd(false) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add place") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Home and places of interest", style = MaterialTheme.typography.titleMedium) }
            items(places, key = { it.id }) { p ->
                TwCard(onClick = { vm.selectPlace(p.id); onOpenPlace() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Empty home = not Home (tap to make it Home); filled home = this is Home. The app and widgets follow Home automatically.
                        IconButton(onClick = { if (!p.isHome) { vm.makeHome(p.id); vm.toast("${p.cityState} is now your Home") } }, modifier = Modifier.size(48.dp)) {
                            Icon(if (p.isHome) Icons.Filled.Home else Icons.Outlined.Home, if (p.isHome) "${p.name} is your Home" else "Make ${p.name} your Home",
                                tint = if (p.isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(p.subtitle, p.countyName).joinToString(" · ").ifBlank { p.state.orEmpty() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (p.isHome) AssistChip(onClick = {}, label = { Text("Home") }); if (p.isDefault && !p.isHome) AssistChip(onClick = {}, label = { Text("Default") })
                                if (p.usesDeviceLocation) AssistChip(onClick = {}, label = { Text("Device location") }, leadingIcon = { Icon(Icons.Filled.MyLocation, null, Modifier.size(16.dp)) })
                                if (p.alertPrefs.notify) AssistChip(onClick = {}, label = { Text("Notifications on") }, leadingIcon = { Icon(Icons.Filled.NotificationsActive, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { edit = p }) { Icon(Icons.Outlined.Notifications, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Alerts") }
                        TextButton(onClick = { rename = p }) { Text("Rename") }
                        if (!p.isDefault && !p.isHome) TextButton(onClick = { vm.repo.setDefault(p.id) }) { Text("Default") }
                    }
                    Row { TextButton(onClick = { remove = p }) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
                }
            }
            item { Text("Tap the home icon on any place to make it your Home. Your forecast, alerts and Home widgets switch to it right away.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item {
                Text("Favorite states & counties", style = MaterialTheme.typography.titleMedium)
                Text("Follow broader areas — handy for keeping an eye on family and friends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(favs, key = { it.id }) { f ->
                TwCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(12.dp))
                        Text(f.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.repo.removeFavorite(f.id) }) { Icon(Icons.Outlined.Delete, "Remove ${f.label}") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Notify me", Modifier.weight(1f)); Switch(f.alertPrefs.notify, { vm.repo.updateFavorite(f.copy(alertPrefs = f.alertPrefs.copy(notify = it))) })
                    }
                }
            }
            item { OutlinedButton(onClick = onFavorites, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.StarOutline, null); Spacer(Modifier.width(8.dp)); Text("Add a state or county") } }
        }
    }
    edit?.let { p -> AlertPrefsDialog(p.name, p.alertPrefs, { edit = null }) { vm.repo.updatePlace(p.copy(alertPrefs = it)); edit = null } }
    rename?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename place") }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.repo.renamePlace(p.id, name.trim()); rename = null }) { Text("Save") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    remove?.let { p ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${p.name}?") }, text = { Text("Its saved forecast and notification settings will be deleted from this device.") },
            confirmButton = { TextButton(onClick = { vm.removePlace(p.id); remove = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
}

@Composable
fun AlertPrefsDialog(name: String, prefs: AlertPrefs, onDismiss: () -> Unit, onSave: (AlertPrefs) -> Unit) {
    var p by remember { mutableStateOf(prefs) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Notifications for $name") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Send notifications", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Switch(p.notify, { p = p.copy(notify = it) }) }
            Text("Choose which official NWS alert types can notify you for this place. Your phone's notification settings control sound and vibration for each category.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            listOf(
                Triple("Warnings & urgent life-safety alerts", p.warnings) { v: Boolean -> p = p.copy(warnings = v) },
                Triple("Watches", p.watches) { v: Boolean -> p = p.copy(watches = v) },
                Triple("Advisories", p.advisories) { v: Boolean -> p = p.copy(advisories = v) },
                Triple("Statements & other notices", p.statements) { v: Boolean -> p = p.copy(statements = v) },
            ).forEach { (label, on, set) -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) { Text(label, Modifier.weight(1f)); Switch(on, set, enabled = p.notify) } }
            Text("App notifications supplement — and never replace — Wireless Emergency Alerts from the government. Keep those enabled in your phone's settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = { TextButton(onClick = { onSave(p) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesPickerScreen(vm: AppViewModel, onBack: () -> Unit) {
    var state by remember { mutableStateOf<String?>(null) }
    var counties by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val favs by vm.favorites.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text(if (state == null) "Choose a state" else NwsSource.stateName(state!!)) }, navigationIcon = { IconButton(onClick = { if (state != null) { state = null; counties = null } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            if (state == null) {
                items(NwsSource.allStates) { (abbr, name) ->
                    ListItem(headlineContent = { Text(name) }, trailingContent = { Icon(Icons.Filled.ChevronRight, null) }, modifier = Modifier.clickable { state = abbr; counties = null; err = null
                        scope.launch { runCatching { CensusSource.counties(abbr) }.onSuccess { counties = it }.onFailure { err = it.message ?: "Couldn't load counties" } } }.heightIn(min = 52.dp))
                    HorizontalDivider()
                }
            } else {
                item {
                    val st = state!!
                    ListItem(headlineContent = { Text("Follow all of ${NwsSource.stateName(st)}") }, leadingContent = { Icon(Icons.Filled.Star, null) },
                        supportingContent = { if (favs.any { it.id == "ST:$st" }) Text("Already following") },
                        modifier = Modifier.clickable { vm.repo.addFavorite(FavoriteArea("ST:$st", NwsSource.stateName(st), st)); vm.toast("Following ${NwsSource.stateName(st)}"); onBack() }.heightIn(min = 56.dp))
                    HorizontalDivider()
                }
                if (counties == null && err == null) item { Loading() }
                err?.let { e -> item { ErrorState(e, { val st = state!!; err = null; scope.launch { runCatching { CensusSource.counties(st) }.onSuccess { counties = it }.onFailure { x -> err = x.message } } }) } }
                items(counties.orEmpty()) { (fips, name) ->
                    ListItem(headlineContent = { Text(name) }, modifier = Modifier.clickable {
                        val st = state!!
                        vm.repo.addFavorite(FavoriteArea("CO:$fips", "$name, $st", st, fips)); vm.toast("Following $name, $st"); onBack()
                    }.heightIn(min = 52.dp))
                    HorizontalDivider()
                }
            }
        }
    }
}
