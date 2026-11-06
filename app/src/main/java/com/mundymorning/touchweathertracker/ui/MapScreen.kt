package com.mundymorning.touchweathertracker.ui

import android.graphics.Color as AColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mundymorning.touchweathertracker.billing.PremiumFeature
import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.source.*
import kotlinx.coroutines.delay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.*
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker

data class MapFocus(val lat: Double, val lon: Double, val zoom: Double = 8.0, val alertId: String? = null)

private fun gp(p: List<Double>) = GeoPoint(p[1], p[0])
private fun dot(ctx: android.content.Context, color: Int, sizeDp: Int = 18): GradientDrawable {
    val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
    return GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke((2 * ctx.resources.displayMetrics.density).toInt(), AColor.WHITE); setSize(px, px) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(vm: AppViewModel, focus: MapFocus?, onFocusConsumed: () -> Unit, onOpenAlert: (String) -> Unit, onOpenStorm: (String) -> Unit, onOpenPremium: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = vm.prefs
    val place by vm.selected.collectAsState()
    val places by vm.places.collectAsState()
    val storms by vm.storms.collectAsState()
    val bundles by vm.bundles.collectAsState()
    val feed by vm.feed.collectAsState()
    val ent by vm.mon.state.collectAsState()
    var radar by remember { mutableStateOf(prefs.mapRadar) }
    var sat by remember { mutableStateOf(prefs.mapSatellite) }
    var warn by remember { mutableStateOf(prefs.mapWarnings) }
    var tracks by remember { mutableStateOf(prefs.mapTracks) }
    var obs by remember { mutableStateOf(prefs.mapObservations) }
    var gfs by remember { mutableStateOf(prefs.mapGfs) }
    var ecmwf by remember { mutableStateOf(prefs.mapEcmwf) }
    var imagery by remember { mutableStateOf(prefs.mapImagery) }
    var sheet by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<GeoPoint?>(null) }
    var satTimes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var buoys by remember { mutableStateOf<List<BuoyObs>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }   // bumps every 10 minutes to refresh radar/satellite tiles
    val mapRef = remember { mutableStateOf<MapView?>(null) }

    LaunchedEffect(Unit) { vm.loadStorms(); if (feed.data == null || feed.scope != FeedScope.ALL) vm.loadFeed(FeedScope.ALL) }
    LaunchedEffect(Unit) { while (true) { delay(10 * 60_000L); tick++ } }
    LaunchedEffect(sat, tick) { if (sat != "off") satTimes = runCatching { NowCoast.latestTimes() }.getOrDefault(emptyMap()) }
    LaunchedEffect(obs) { if (obs) buoys = runCatching { NdbcSource.all() }.getOrDefault(emptyList()) }
    LaunchedEffect(storms.storms?.value) { storms.storms?.value?.forEach { vm.loadBundle(it) } }

    // Lifecycle for osmdroid
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obsv = LifecycleEventObserver { _, e -> when (e) { Lifecycle.Event.ON_RESUME -> mapRef.value?.onResume(); Lifecycle.Event.ON_PAUSE -> mapRef.value?.onPause(); else -> {} } }
        lifecycle.addObserver(obsv); onDispose { lifecycle.removeObserver(obsv); mapRef.value?.onDetach() }
    }

    // Focus requests (e.g. "show on map" from an alert or a storm)
    LaunchedEffect(focus) {
        val m = mapRef.value
        if (focus != null && m != null) { m.controller.setZoom(focus.zoom); m.controller.animateTo(GeoPoint(focus.lat, focus.lon)); onFocusConsumed() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            MapView(c).apply {
                setTileSource(UsgsTileSource("usgs-topo", "USGSTopo"))
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 3.0; maxZoomLevel = 14.0
                isTilesScaledToDpi = true
                val start = focus?.let { GeoPoint(it.lat, it.lon) } ?: place?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(39.0, -96.0)
                controller.setZoom(focus?.zoom ?: if (place != null) 8.0 else 4.0); controller.setCenter(start)
                mapRef.value = this
            }
        })

        // Rebuild overlays whenever any input changes.
        val alertsForMap = feed.data?.value?.map { it.first }.orEmpty()
        LaunchedEffect(radar, sat, warn, tracks, obs, gfs, ecmwf, imagery, tick, places, bundles, alertsForMap, buoys, ent.premium) {
            val m = mapRef.value ?: return@LaunchedEffect
            m.setTileSource(if (imagery) UsgsTileSource("usgs-imagery", "USGSImageryTopo") else UsgsTileSource("usgs-topo", "USGSTopo"))
            m.overlays.clear()
            m.overlays += MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                override fun longPressHelper(p: GeoPoint?): Boolean { pin = p; return true }
            })
            val bucket = System.currentTimeMillis() / 600_000L
            val fade = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.78f, 0f)))
            fun tiles(src: org.osmdroid.tileprovider.tilesource.ITileSource) = TilesOverlay(MapTileProviderBasic(ctx, src), ctx).apply { loadingBackgroundColor = AColor.TRANSPARENT; loadingLineColor = AColor.TRANSPARENT; setColorFilter(fade) }
            if (sat != "off") {
                val band = when (sat) { "visible" -> NowCoast.Band.VISIBLE; "watervapor" -> NowCoast.Band.WATER_VAPOR; else -> NowCoast.Band.INFRARED }
                m.overlays += tiles(WmsTileSource("goes-${band.name}-$bucket", NowCoast.WMS, band.layer))
            }
            if (radar) m.overlays += tiles(RadarTileSource("radar-$bucket"))

            if (warn) alertsForMap.filter { it.level == AlertLevel.WARNING || it.level == AlertLevel.WATCH }.sortedBy { -it.level.ordinal }.forEach { a ->
                val col = if (a.level == AlertLevel.WARNING) AColor.rgb(211, 47, 47) else AColor.rgb(245, 124, 0)
                a.polygons.forEach { ring ->
                    if (ring.size < 3) return@forEach
                    m.overlays += Polygon(m).apply {
                        points = ring.map(::gp); fillPaint.color = (col and 0x00FFFFFF) or 0x33000000; outlinePaint.color = col; outlinePaint.strokeWidth = if (a.level == AlertLevel.WARNING) 5f else 3f
                        title = "${a.event} (${a.level.name.lowercase()})"; snippet = a.headline
                        setOnClickListener { _, _, _ -> onOpenAlert(a.id); true }
                    }
                }
            }

            if (tracks) bundles.values.forEach { b ->
                b.graphics?.let { g ->
                    g.cone.forEach { f -> f.rings.forEach { r -> m.overlays += Polygon(m).apply { points = r.map(::gp); fillPaint.color = 0x2AFFFFFF; outlinePaint.color = AColor.WHITE; outlinePaint.strokeWidth = 3f; title = "NHC forecast cone — uncertainty in the center track only" } } }
                    g.warnings.forEach { f ->
                        val label = f.attrs.values.joinToString(" ").uppercase()
                        val col = when { "HURRICANE WARNING" in label || label.contains("HWRN") -> AColor.rgb(220, 20, 60); "HURRICANE WATCH" in label || label.contains("HWCH") -> AColor.rgb(255, 105, 180); "TROPICAL STORM WARNING" in label || label.contains("TWRN") -> AColor.rgb(30, 100, 255); "TROPICAL STORM WATCH" in label || label.contains("TWCH") -> AColor.rgb(255, 215, 0); else -> AColor.GRAY }
                        val pts = if (f.line.isNotEmpty()) listOf(f.line) else f.rings
                        pts.forEach { l -> m.overlays += Polyline(m).apply { setPoints(l.map(::gp)); outlinePaint.color = col; outlinePaint.strokeWidth = 12f } }
                    }
                    g.past.forEach { f -> (if (f.line.isNotEmpty()) listOf(f.line) else f.rings).forEach { l -> m.overlays += Polyline(m).apply { setPoints(l.map(::gp)); outlinePaint.color = AColor.rgb(120, 120, 120); outlinePaint.strokeWidth = 5f; outlinePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f) } } }
                    g.track.forEach { f -> (if (f.line.isNotEmpty()) listOf(f.line) else f.rings).forEach { l -> m.overlays += Polyline(m).apply { setPoints(l.map(::gp)); outlinePaint.color = AColor.rgb(11, 42, 74); outlinePaint.strokeWidth = 7f; title = "Official NHC forecast track" } } }
                }
                // Official forecast track from ATCF if the ArcGIS graphics are unavailable.
                if (b.graphics?.track.isNullOrEmpty()) b.official?.let { t -> m.overlays += Polyline(m).apply { setPoints(t.points.map { GeoPoint(it.lat, it.lon) }); outlinePaint.color = AColor.rgb(11, 42, 74); outlinePaint.strokeWidth = 7f; title = "Official NHC forecast track" } }
                b.official?.points?.forEach { p -> m.overlays += Marker(m).apply { position = GeoPoint(p.lat, p.lon); icon = dot(ctx, AColor.rgb(11, 42, 74), 12); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "NHC forecast +${p.tauHours} h"; snippet = p.vmaxKt?.let { "Max wind ${StormMph(it)} mph" } } }
                if (gfs && ent.premium) b.gfs?.let { t -> m.overlays += Polyline(m).apply { setPoints(t.points.map { GeoPoint(it.lat, it.lon) }); outlinePaint.color = AColor.rgb(123, 31, 162); outlinePaint.strokeWidth = 5f; outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f); title = "GFS model guidance (not official)"; snippet = "Initialized ${t.initDtg}Z" } }
                if (ecmwf && ent.premium) b.ecmwf?.let { t -> m.overlays += Polyline(m).apply { setPoints(t.points.map { GeoPoint(it.lat, it.lon) }); outlinePaint.color = AColor.rgb(0, 150, 136); outlinePaint.strokeWidth = 5f; outlinePaint.pathEffect = DashPathEffect(floatArrayOf(6f, 10f), 0f); title = "ECMWF model guidance (not official)" } }
                m.overlays += Marker(m).apply { position = GeoPoint(b.storm.lat, b.storm.lon); icon = dot(ctx, AColor.rgb(213, 0, 0), 24); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "${b.storm.classLabel} ${b.storm.name}"; snippet = "Tap for the storm report"
                    setOnMarkerClickListener { mk, _ -> mk.showInfoWindow(); onOpenStorm(b.storm.id); true } }
            }

            if (obs) buoys.filter { Geo.distanceMiles(m.mapCenter.latitude, m.mapCenter.longitude, it.lat, it.lon) < 600 }.take(120).forEach { b ->
                m.overlays += Marker(m).apply { position = GeoPoint(b.lat, b.lon); icon = dot(ctx, AColor.rgb(0, 121, 107), 12); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "NDBC buoy ${b.id}"; snippet = listOfNotNull(b.windMph?.let { "Wind ${it.toInt()} mph" }, b.gustMph?.let { "gust ${it.toInt()}" }, b.waveHeightFt?.let { "waves ${"%.1f".format(it)} ft" }, "reported ${Units.ago(b.timeMs)}").joinToString(" · ") } }

            places.forEach { p -> m.overlays += Marker(m).apply { position = GeoPoint(p.lat, p.lon); icon = dot(ctx, if (p.isHome) AColor.rgb(11, 95, 165) else AColor.rgb(0, 105, 92), 20); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = p.name; snippet = if (p.isHome) "Home" else "Saved place" } }
            pin?.let { pp -> m.overlays += Marker(m).apply { position = pp; icon = dot(ctx, AColor.rgb(245, 124, 0), 22); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "Dropped pin" } }
            m.invalidate()
        }

        // Top controls
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallFloatingActionButton(onClick = { sheet = true }) { Icon(Icons.Outlined.Layers, "Map layers") }
            SmallFloatingActionButton(onClick = { place?.let { mapRef.value?.controller?.animateTo(GeoPoint(it.lat, it.lon)); mapRef.value?.controller?.setZoom(8.0) } }) { Icon(Icons.Filled.MyLocation, "Center on ${place?.name ?: "selected place"}") }
        }
        // Source/time/attribution card
        Surface(Modifier.align(Alignment.BottomStart).padding(8.dp).widthIn(max = 340.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f)) {
            Column(Modifier.padding(8.dp)) {
                if (radar) Text("Radar: NOAA MRMS base reflectivity — refreshes ~every 10 min; scan time not published by the service.", style = MaterialTheme.typography.labelSmall)
                if (sat != "off") {
                    val band = when (sat) { "visible" -> NowCoast.Band.VISIBLE; "watervapor" -> NowCoast.Band.WATER_VAPOR; else -> NowCoast.Band.INFRARED }
                    Text("Satellite (${band.label}): NOAA GOES${satTimes[band.layer]?.let { " — latest image ${Units.stamp(it)}" } ?: ""}", style = MaterialTheme.typography.labelSmall)
                }
                if (tracks && bundles.isNotEmpty()) Text("Solid dark line = official NHC forecast. Dashed lines = model guidance, not official.", style = MaterialTheme.typography.labelSmall)
                if (warn) Text("Red = warnings, orange = watches (NWS).", style = MaterialTheme.typography.labelSmall)
                Text("Basemap: USGS The National Map · Data: NOAA/NWS/NHC. Not an endorsement by any agency. Long-press to drop a pin.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Map layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            LayerSwitch("Radar", "NOAA MRMS base reflectivity", radar) { radar = it; prefs.mapRadar = it }
            Text("Satellite (NOAA GOES)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("off" to "Off", "visible" to "Visible", "infrared" to "Infrared", "watervapor" to "Water vapor").forEach { (k, l) -> FilterChip(selected = sat == k, onClick = { sat = k; prefs.mapSatellite = k }, label = { Text(l) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            LayerSwitch("NWS warnings & watches", "Warned polygons; tap for detail", warn) { warn = it; prefs.mapWarnings = it }
            LayerSwitch("Hurricane tracks", "NHC official forecast, cone, watches/warnings — over any layer, including satellite", tracks) { tracks = it; prefs.mapTracks = it }
            LayerSwitch("Observed conditions", "NOAA NDBC buoys", obs) { obs = it; prefs.mapObservations = it }
            LayerSwitch("Satellite-style basemap", "USGS imagery instead of topographic", imagery) { imagery = it; prefs.mapImagery = it }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Model guidance (not the official forecast)", style = MaterialTheme.typography.titleSmall)
            if (!ent.premium) { Text("GFS and ECMWF comparison is a premium feature.", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { sheet = false; onOpenPremium() }) { Text("See premium options") } }
            LayerSwitch("GFS track", "Dashed purple · NOAA model guidance", gfs && ent.premium, enabled = ent.premium) { gfs = it; prefs.mapGfs = it }
            LayerSwitch("ECMWF track", "Dashed teal · European model guidance", ecmwf && ent.premium, enabled = ent.premium) { ecmwf = it; prefs.mapEcmwf = it }
            Text("Models can disagree. Don't treat any model line as a forecast — the NHC track and cone are the official forecast.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    pin?.let { p ->
        AlertDialog(onDismissRequest = { pin = null }, title = { Text("Dropped pin") }, text = { Text("%.4f, %.4f".format(p.latitude, p.longitude) + "\nAdd this spot as a saved place?") },
            confirmButton = { TextButton(onClick = { vm.addPinned(p.latitude, p.longitude) { r -> if (r != null) vm.toast("Added ${r.name}") }; pin = null }) { Text("Add place") } },
            dismissButton = { TextButton(onClick = { pin = null }) { Text("Cancel") } })
    }
}

private fun StormMph(kt: Int) = com.mundymorning.touchweathertracker.domain.StormReportBuilder.ktToMph(kt)

@Composable
private fun LayerSwitch(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, enabled = enabled)
    }
}
