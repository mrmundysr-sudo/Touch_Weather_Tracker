package com.mundymorning.touchweathertracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mundymorning.touchweathertracker.data.FeedScope
import com.mundymorning.touchweathertracker.work.Scheduler

/** One-shot request from outside the UI (notification / widget taps). */
data class LaunchRequest(val tab: String? = null, val alertId: String? = null, val placeId: String? = null, val eventStorm: String? = null, val nonce: Long = System.nanoTime())

private enum class Tab(val key: String, val label: String, val icon: ImageVector, val selected: ImageVector) {
    TODAY("today", "Today", Icons.Outlined.WbSunny, Icons.Filled.WbSunny),
    MAP("map", "Map", Icons.Outlined.Map, Icons.Filled.Map),
    ALERTS("alerts", "Alerts", Icons.Outlined.NotificationsNone, Icons.Filled.Notifications),
    STORMS("storms", "Storms", Icons.Outlined.Cyclone, Icons.Filled.Cyclone),
    RESOURCES("resources", "Resources", Icons.Outlined.VolunteerActivism, Icons.Filled.VolunteerActivism),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwApp(request: LaunchRequest?, onRequestHandled: () -> Unit) {
    val vm: AppViewModel = viewModel()
    val nav = rememberNavController()
    var onboarded by remember { mutableStateOf(vm.prefs.onboarded) }
    val places by vm.places.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY.key) }
    var mapFocus by remember { mutableStateOf<MapFocus?>(null) }
    val snack = remember { SnackbarHostState() }
    val msg by vm.message.collectAsState()
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }

    // Finish onboarding only when a Home exists.
    if (!onboarded || places.isEmpty()) {
        OnboardingScreen(vm) { vm.prefs.onboarded = true; onboarded = true; Scheduler.schedule(vm.getApplication(), false); vm.refreshToday() }
        Box(Modifier.fillMaxSize()) { SnackbarHost(snack, Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) }
        return
    }

    // The whole app is locked until the user has an entitlement (4 ads = 24 h, ad-free purchase, or subscription).
    val ent by vm.mon.state.collectAsState()
    LaunchedEffect(ent.premium, ent.unlockUntilMs) {
        // Re-evaluate against the clock so a 24-hour unlock locks the app again when it expires.
        while (true) { kotlinx.coroutines.delay(15_000); vm.mon.refresh() }
    }
    if (!ent.premium) {
        PaywallScreen(vm, onBack = null)
        Box(Modifier.fillMaxSize()) { SnackbarHost(snack, Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) }
        return
    }

    LaunchedEffect(request?.nonce) {
        val r = request ?: return@LaunchedEffect
        r.placeId?.let { vm.selectPlace(it) }
        r.tab?.let { tab = it }
        r.alertId?.let { nav.navigate("alert/${java.net.URLEncoder.encode(it, "UTF-8")}") }
        r.eventStorm?.let { tab = "storms" }
        onRequestHandled()
    }

    NavHost(nav, startDestination = "main") {
        composable("main") {
            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                    NavigationBar {
                        Tab.values().forEach { t ->
                            NavigationBarItem(selected = tab == t.key, onClick = { tab = t.key }, icon = { Icon(if (tab == t.key) t.selected else t.icon, null) }, label = { Text(t.label, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, fontSize = 11.sp) })
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(bottom = pad.calculateBottomPadding()).fillMaxSize()) {
                    when (tab) {
                        "today" -> TodayScreen(vm,
                            onOpenAlert = { nav.navigate("alert/${java.net.URLEncoder.encode(it, "UTF-8")}") }, onOpenPlaces = { nav.navigate("places") }, onOpenSettings = { nav.navigate("settings") },
                            onOpenStorms = { tab = "storms" }, onOpenAlerts = { tab = "alerts" })
                        "map" -> MapScreen(vm, mapFocus, { mapFocus = null }, onOpenAlert = { nav.navigate("alert/${java.net.URLEncoder.encode(it, "UTF-8")}") }, onOpenStorm = { nav.navigate("storm/$it") })
                        "alerts" -> AlertsScreen(vm, onOpenAlert = { nav.navigate("alert/${java.net.URLEncoder.encode(it, "UTF-8")}") }, onManageAreas = { nav.navigate("places") })
                        "storms" -> StormsScreen(vm, onOpenStorm = { nav.navigate("storm/$it") })
                        "resources" -> ResourcesScreen(vm, onOpenPower = { nav.navigate("power") })
                    }
                }
            }
        }
        composable("alert/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            val id = java.net.URLDecoder.decode(e.arguments?.getString("id").orEmpty(), "UTF-8")
            AlertDetailScreen(vm, id, onBack = { nav.popBackStack() }, onShowOnMap = { a ->
                val ring = a.polygons.firstOrNull()
                if (ring != null) { mapFocus = MapFocus(ring.map { it[1] }.average(), ring.map { it[0] }.average(), 9.0, a.id); tab = "map"; nav.popBackStack("main", false) }
            })
        }
        composable("places") { PlacesScreen(vm, onBack = { nav.popBackStack() }, onAdd = { home -> nav.navigate("search/$home") }, onFavorites = { nav.navigate("favorites") }, onOpenPlace = { tab = "today"; nav.popBackStack("main", false) }) }
        composable("search/{home}", arguments = listOf(navArgument("home") { type = NavType.BoolType })) { e ->
            SearchPlaceScreen(vm, e.arguments?.getBoolean("home") ?: false, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() }, onPinOnMap = { tab = "map"; vm.toast("Long-press the map to drop a pin."); nav.popBackStack("main", false) })
        }
        composable("favorites") { FavoritesPickerScreen(vm) { nav.popBackStack() } }
        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }, onPlaces = { nav.navigate("places") }, onPremium = { nav.navigate("premium") }, onSources = { nav.navigate("sources") }) }
        composable("premium") { PaywallScreen(vm) { nav.popBackStack() } }
        composable("sources") { SourcesScreen { nav.popBackStack() } }
        composable("power") { PowerScreen(vm) { nav.popBackStack() } }
        composable("storm/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            StormDetailScreen(vm, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() },
                onShowOnMap = { s -> mapFocus = MapFocus(s.lat, s.lon, 6.0); tab = "map"; nav.popBackStack("main", false) })
        }
    }
}
