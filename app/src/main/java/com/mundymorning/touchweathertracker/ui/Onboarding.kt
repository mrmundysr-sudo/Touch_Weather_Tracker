package com.mundymorning.touchweathertracker.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler

@Composable
fun OnboardingScreen(vm: AppViewModel, onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val places by vm.places.collectAsState()
    if (step == 0 && places.isNotEmpty()) step = 2
    BackHandler(enabled = step > 0) { step -= 1 }
    when (step) {
        0 -> Welcome { step = 1 }
        1 -> SearchPlaceScreen(vm, asHome = true, onBack = { step = 0 }, onDone = { step = 2 }, onOpenPaywall = {}, onPinOnMap = { vm.toast("You can drop a pin from the Map tab after setup. Search by address, ZIP or city for now.") })
        2 -> NotificationsStep(vm, onNext = { step = 3 })
        else -> Ready(vm, onFinished)
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val c = Tw.colors
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.skyDeep, Color(0xFF1B5B94)))).statusBarsPadding().navigationBarsPadding().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.padding(top = 48.dp)) {
            Icon(Icons.Filled.Cloud, null, tint = Color.White, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("Touch Weather Tracker", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text("Everyday weather and severe-weather information for the United States — from official sources, explained plainly.", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.height(28.dp))
            listOf(Icons.Outlined.Today to "Forecasts and conditions for home and the places you care about",
                Icons.Outlined.NotificationsActive to "Official NWS warnings, with clear notification choices",
                Icons.Outlined.Cyclone to "Hurricane tracking and a plain-language storm report",
                Icons.Outlined.VolunteerActivism to "Shelters, FEMA help and power-outage information near you").forEach { (i, t) ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(i, null, tint = Color.White); Spacer(Modifier.width(14.dp)); Text(t, color = Color.White, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        Column {
            Text("Information comes from NOAA, the National Weather Service, FEMA and other U.S. government sources. This app is not a government product and does not replace official emergency alerts.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = c.skyDeep)) { Text("Get started") }
        }
    }
}

@Composable
private fun NotificationsStep(vm: AppViewModel, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.prefs.notificationsAsked = true; onNext() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text("Get warnings for Home", style = MaterialTheme.typography.headlineMedium)
        Text("Touch Weather Tracker can notify you when the National Weather Service issues a new warning or urgent life-safety alert for your Home location.", style = MaterialTheme.typography.bodyLarge)
        TwCard {
            Text("By default we only notify for new warnings at Home.", style = MaterialTheme.typography.titleSmall)
            Text("Watches, advisories, and other places are off until you choose them in Places → Alerts.", style = MaterialTheme.typography.bodyMedium)
        }
        TwCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
            Text("Keep Wireless Emergency Alerts on", style = MaterialTheme.typography.titleSmall)
            Text("Government WEA messages arrive on their own and don't depend on this app. App notifications are a supplement: Android may delay background updates, so delivery is never instant or guaranteed.", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else { vm.prefs.notificationsAsked = true; onNext() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Turn on warning notifications") }
        TextButton(onClick = { vm.prefs.notificationsAsked = true; onNext() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Not now") }
    }
}

@Composable
private fun Ready(vm: AppViewModel, onFinished: () -> Unit) {
    val home by vm.selected.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CheckCircle, null, tint = Tw.colors.observed, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("You're set", style = MaterialTheme.typography.headlineMedium)
        Text("Home is ${home?.name ?: "set"}. You can add family, friends, work or travel places any time, and enable hurricane event mode when a storm threatens.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
        Text("Location, notifications and the map ask for permission only when you use them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFinished, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Show my weather") }
    }
}
