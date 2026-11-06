package com.mundymorning.touchweathertracker.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.mundymorning.touchweathertracker.BuildConfig
import com.mundymorning.touchweathertracker.billing.Monetization
import com.mundymorning.touchweathertracker.billing.PremiumSource
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.work.EventActionReceiver
import com.mundymorning.touchweathertracker.work.Notifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onPlaces: () -> Unit, onPremium: () -> Unit, onSources: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val ent by vm.mon.state.collectAsState()
    val tick by vm.prefsTick.collectAsState()
    var offers by remember(tick) { mutableStateOf(vm.prefs.eventModeOffers) }
    val activeStorm = remember(tick) { vm.prefs.eventModeActiveStorm }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Section("Places") {
                SettingRow(Icons.Outlined.EditLocationAlt, "Places & favorite areas", "Home, family, work, travel; favorite states and counties", onPlaces)
            }
            Section("Notifications") {
                SettingRow(Icons.Outlined.Notifications, "Notification categories", "Sound and vibration for urgent alerts, warnings, watches and advisories are controlled by Android per category.") {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                SettingRow(Icons.Outlined.NotificationsActive, "Send a test notification", "Confirms Android will show notifications from this app.") {
                    if (!Notifier.postTest(ctx)) vm.toast("Notifications are turned off for this app in Android settings.")
                }
                TwCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("Wireless Emergency Alerts", style = MaterialTheme.typography.titleSmall)
                    Text("Keep government Wireless Emergency Alerts enabled in your phone's settings (Settings → Notifications → Wireless emergency alerts). App notifications supplement them and may be delayed by Android power management.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Section("Hurricane event mode") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                    Column(Modifier.weight(1f)) { Text("Offer event mode", style = MaterialTheme.typography.titleSmall); Text("When NHC's official forecast shows a hurricane expected to make U.S. landfall within 72 hours, offer temporary event mode.", style = MaterialTheme.typography.bodySmall) }
                    Switch(offers, { offers = it; vm.prefs.eventModeOffers = it })
                }
                if (activeStorm != null) {
                    TwCard {
                        Text("Event mode is on for ${activeStorm.uppercase()}", style = MaterialTheme.typography.titleSmall)
                        Text("Background refresh runs about every 15 minutes (Android may delay it) for your saved places' storm information and urgent app alerts. No continuous GPS tracking is used.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.eventAction(EventActionReceiver.ACTION_END, activeStorm) }) { Text("Return to normal") }
                    }
                } else Text("Event mode is off. Normal background refresh runs about every 30 minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("Unlock") {
                val src = when (ent.source) { PremiumSource.AD_UNLOCK -> "Unlocked for 24 hours until ${Units.stamp(ent.unlockUntilMs)}"; PremiumSource.AD_FREE_PURCHASE -> "Ad-free purchase active"; PremiumSource.SUBSCRIPTION -> "Subscription active"; PremiumSource.NONE -> "Locked" }
                SettingRow(Icons.Outlined.WorkspacePremium, "Unlock status", src, onPremium)
            }
            Section("Privacy & data") {
                if (activity != null && vm.mon.privacyOptionsRequired(ctx)) SettingRow(Icons.Outlined.PrivacyTip, "Ad privacy choices", "Review or change your ad consent.") { vm.mon.showPrivacyOptions(activity) }
                SettingRow(Icons.Outlined.Info, "Data sources & attribution", "Every source, its refresh rate and terms.", onSources)
                Text("Your places stay on this device. Network requests go directly from your phone to U.S. government and other public data services; this app has no account system and no server of its own. Approximate location is used only when you tap “Use my location”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            Section("About") {
                Text("Touch Weather Tracker ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text("Build for owner testing. Not for store release until the owner's full review is complete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The unlock screen. With [onBack] == null it is the app's gate: nothing else in the app is reachable until the user watches
 * four ads (24 hours of use), buys the ad-free version, or subscribes. With a back action it is the status page opened from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val ent by vm.mon.state.collectAsState()
    val ctx = LocalContext.current
    val activity = ctx as Activity
    LaunchedEffect(Unit) { vm.mon.startBilling(); vm.mon.loadRewarded() }
    val canAds = vm.mon.canRequestAds(ctx)
    Scaffold(topBar = {
        if (onBack != null) TopAppBar(title = { Text("Unlock status") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (onBack == null) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Text("Unlock Touch Weather Tracker", style = MaterialTheme.typography.headlineMedium)
                Text("Choose how to use the app. Every option unlocks the same complete app.", style = MaterialTheme.typography.bodyLarge)
            }
            TwCard(container = if (ent.premium) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                Text(if (ent.premium) "Touch Weather Tracker is unlocked" else "Locked", style = MaterialTheme.typography.titleMedium)
                Text(when (ent.source) {
                    PremiumSource.AD_UNLOCK -> "Unlocked with ads until ${Units.stamp(ent.unlockUntilMs)}."
                    PremiumSource.AD_FREE_PURCHASE -> "Thanks for buying the app — it's unlocked permanently on this Google account."
                    PremiumSource.SUBSCRIPTION -> "Thanks — your subscription is active."
                    PremiumSource.NONE -> "Watch ${Monetization.ADS_REQUIRED} short ads for 24 hours of full use, or buy the app, or subscribe to all Mundy Morning apps."
                }, style = MaterialTheme.typography.bodyMedium)
            }
            if (!ent.premium) {
                TwCard {
                    Text("Watch ${Monetization.ADS_REQUIRED} ads → 24 hours of full use", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { ent.adViews / ent.adsRequired.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    Text("${ent.adViews} of ${ent.adsRequired} ads watched", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.mon.watchAd(activity) }, enabled = ent.adReady, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        if (ent.adLoading && !ent.adReady) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(if (ent.adReady) "Watch an ad" else if (ent.adLoading) "Loading an ad…" else "No ad available")
                    }
                    if (!canAds) Text("Ads can't be requested until ad consent is completed. Review your choices below, or buy / subscribe instead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (!ent.adReady && !ent.adLoading) TextButton(onClick = { vm.mon.startAds(activity); vm.mon.loadRewarded() }) { Text("Try loading an ad again") }
                }
                TwCard {
                    Text("Buy the app", style = MaterialTheme.typography.titleSmall)
                    Text("One-time purchase. No ads, unlocked permanently on this Google account.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.mon.buyAdFree(activity) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(ent.adFreePrice?.let { "Buy · $it" } ?: "Buy the app") }
                }
                TwCard {
                    Text("Subscribe to all apps", style = MaterialTheme.typography.titleSmall)
                    Text("One subscription that unlocks this app and the owner's other apps that support it.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.mon.subscribe(activity) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(ent.subscriptionPrice?.let { "Subscribe · $it" } ?: "Subscribe") }
                }
            }
            OutlinedButton(onClick = { vm.mon.restore() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Restore purchases") }
            if (vm.mon.privacyOptionsRequired(ctx)) TextButton(onClick = { vm.mon.showPrivacyOptions(activity) }, modifier = Modifier.fillMaxWidth()) { Text("Ad privacy choices") }
            ent.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            if (!ent.billingReady) Text("Google Play billing is not connected. Purchases only work on builds installed from Google Play with the products set up (see docs/MONETIZATION.md).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onBack == null) Text("While the app is locked it does not refresh data or send notifications, so keep Wireless Emergency Alerts turned on in your phone's settings — those government alerts do not depend on this app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}

data class SourceInfo(val name: String, val what: String, val refresh: String, val terms: String, val stale: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sources = listOf(
        SourceInfo("NOAA / National Weather Service API (api.weather.gov)", "Forecasts, hourly forecasts, observations, alerts, Hazardous Weather Outlook, point → zone/county lookup.", "On open and pull-to-refresh; background about every 30 min.", "U.S. Government work, no key; requires a User-Agent. Not an endorsement by NWS.", "Last forecast is kept and labeled 'not current'; alerts warn that they could not be refreshed."),
        SourceInfo("National Hurricane Center (CurrentStorms.json, ATCF a-deck/best track, public advisories, ArcGIS tropical service)", "Active storms, official forecast track (OFCL), GFS guidance (AVNO), cone, watches/warnings, advisory text.", "About every 5 minutes while the Storms tab is open.", "U.S. Government work. Official NHC forecast is authoritative; model tracks are shown separately as guidance.", "Storms are hidden if the feed cannot be reached; saved copy labeled with its time."),
        SourceInfo("NHC aircraft reconnaissance archive + tgftp.nws.noaa.gov", "HDOB observations, Vortex Data Messages, dropsonde reports near an active storm.", "Fetched when a storm report is opened; last 12 hours only.", "U.S. Government products. No third-party live displays are used.", "Omitted from the report when none is recent or in range."),
        SourceInfo("NOAA NDBC latest observations", "Buoy wind, gust, pressure, wave height/period, water temperature.", "On storm report; stations older than 3 h are ignored.", "NOAA public data.", "Omitted; shown as unavailable in details."),
        SourceInfo("NOAA CO-OPS Tides & Currents", "Observed water level and departure from predicted tide.", "On storm report; readings older than 2 h are ignored.", "NOAA public data. Water level is never presented as storm surge.", "Omitted."),
        SourceInfo("NOAA MRMS radar via NWS RIDGE2 map service (mapservices.weather.noaa.gov)", "Base reflectivity mosaic on the Map.", "Roughly every 10 minutes at the source; the service does not publish a scan time.", "NOAA public data.", "Tiles show blank if unreachable."),
        SourceInfo("NOAA nowCOAST GOES satellite imagery", "GOES East/West visible, longwave (infrared) and water vapor imagery.", "Source updates every few minutes; app shows the service's latest time.", "NOAA public data.", "Layer is skipped if unreachable."),
        SourceInfo("USGS The National Map basemap", "Basemap tiles (topographic and imagery).", "Static, cached on device.", "USGS public domain; attribution shown on the map.", "Cached tiles; blank if never loaded."),
        SourceInfo("U.S. Census Bureau Geocoder and TIGERweb", "Address, ZIP code and city lookup; county lists; state-landfall test for event mode.", "On search.", "U.S. Government public services; search text is sent to the Census Bureau.", "Search shows an error; saved places keep working."),
        SourceInfo("FEMA (OpenFEMA, ESF-6 shelters, Disaster Recovery Centers) ", "Declarations and assistance programs, open shelters (incl. reported pet policy), recovery centers, USACE mission assignments.", "On opening Resources.", "FEMA public data. Pet policy is shown only when the feed reports it; otherwise 'not reported'.", "Listings are shown with their source and never as verified-open unless the feed says open."),
        SourceInfo("Electric retail service territories (HIFLD data, public domain; mirrored on ArcGIS Online)", "Likely local electric utility, phone and website for a location.", "11-minute cache.", "Public-domain HIFLD data via a third-party-hosted mirror; boundaries are approximate so you can correct the utility manually.", "Manual utility selection remains available."),
        SourceInfo("MassOutage (massoutage.com)", "Unofficial, supplemental county and national outage overview.", "No more than once every 11 minutes; cached; honors rate limits.", "CC BY 4.0 — “MassOutage (massoutage.com)”. Unofficial and supplemental; never a substitute for your utility.", "Shows data age; missing coverage is shown as 'not tracked', never as zero."),
    )
    Scaffold(topBar = { TopAppBar(title = { Text("Data sources") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No paid weather API, map tile service or server is used. Government sources are not endorsing this app.", style = MaterialTheme.typography.bodyMedium)
            sources.forEach { s ->
                TwCard {
                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                    DetailRow("Provides", s.what); DetailRow("Refresh", s.refresh); DetailRow("Terms / attribution", s.terms); DetailRow("If stale or unavailable", s.stale)
                }
            }
            LinkButton("MassOutage (massoutage.com)", "https://massoutage.com")
            LinkButton("NOAA National Hurricane Center", "https://www.nhc.noaa.gov")
            Spacer(Modifier.height(24.dp))
        }
    }
}
