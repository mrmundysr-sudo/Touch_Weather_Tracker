package com.mundymorning.touchweathertracker.data

import android.content.Context
import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.domain.*
import com.mundymorning.touchweathertracker.source.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/** A value plus how it got here. [stale] is true when a refresh failed and the last good copy is being shown. */
data class Loaded<T>(val value: T?, val fetchedAtMs: Long?, val error: String? = null, val stale: Boolean = false)

enum class FeedScope { HOME, SAVED, FAVORITES, ALL }

data class StormBundle(
    val storm: ActiveStorm,
    val official: TrackLine?,
    val gfs: TrackLine?,
    val ecmwf: TrackLine?,
    val best: List<AtcfFix>,
    val graphics: ForecastGraphics?,
    val advisoryText: String?,
    val highlights: List<Pair<String, String>>,
    val fetchedAtMs: Long,
)

data class ResourceBundle(
    val shelters: List<Shelter>, val centers: List<RecoveryCenter>, val declarations: List<Declaration>,
    val sheltersError: String?, val centersError: String?, val declarationsError: String?, val fetchedAtMs: Long,
)

data class PowerBundle(
    val utilities: List<UtilityChoice>, val county: OutageCounty?, val countyError: String?, val utilitiesError: String?, val fetchedAtMs: Long,
)

class Repository(private val app: Context) {
    val store = Store(app)
    val prefs = Prefs(app)

    private val _places = MutableStateFlow(store.read("places", ListSerializer(Place.serializer())) ?: emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()
    private val _favorites = MutableStateFlow(store.read("favorites", ListSerializer(FavoriteArea.serializer())) ?: emptyList())
    val favorites: StateFlow<List<FavoriteArea>> = _favorites.asStateFlow()
    private val _selected = MutableStateFlow(store.read("selected", String.serializer()))
    val selectedId: StateFlow<String?> = _selected.asStateFlow()

    // -------------------- places --------------------
    fun home(): Place? = _places.value.firstOrNull { it.isHome }
    fun selected(): Place? = _places.value.firstOrNull { it.id == _selected.value } ?: _places.value.firstOrNull { it.isDefault } ?: home() ?: _places.value.firstOrNull()
    fun select(id: String) { _selected.value = id; store.write("selected", String.serializer(), id) }

    private fun savePlaces(list: List<Place>) { _places.value = list; store.write("places", ListSerializer(Place.serializer()), list) }

    /** Create a place from a search result or map pin; enriches with NWS county/time zone/state. First place becomes Home. */
    suspend fun addPlace(name: String, lat: Double, lon: Double, subtitle: String?, asHome: Boolean = false, device: Boolean = false): Place {
        val enriched = runCatching { NwsSource.point(lat, lon) }.getOrNull()
        val first = _places.value.isEmpty()
        val makeHome = asHome || first
        var p = Place(
            id = UUID.randomUUID().toString(), name = name, lat = lat, lon = lon, subtitle = subtitle,
            isHome = makeHome, isDefault = makeHome, state = enriched?.state, countyFips = enriched?.countyFips, timeZone = enriched?.timeZone,
            usesDeviceLocation = device,
            alertPrefs = if (makeHome) AlertPrefs(notify = true, warnings = true) else AlertPrefs(),
        )
        var list = _places.value
        if (makeHome) list = list.map { it.copy(isHome = false, isDefault = false, alertPrefs = if (it.isHome) it.alertPrefs.copy(notify = false) else it.alertPrefs) }
        savePlaces(list + p)
        if (makeHome || first) select(p.id)
        return p
    }

    fun updatePlace(p: Place) = savePlaces(_places.value.map { if (it.id == p.id) p else it })
    fun renamePlace(id: String, name: String) = _places.value.firstOrNull { it.id == id }?.let { updatePlace(it.copy(name = name)) }

    fun setHome(id: String) {
        savePlaces(_places.value.map {
            when {
                it.id == id -> it.copy(isHome = true, isDefault = true, alertPrefs = it.alertPrefs.copy(notify = true))
                it.isHome -> it.copy(isHome = false, isDefault = false)
                else -> it
            }
        })
    }

    fun setDefault(id: String) = savePlaces(_places.value.map { it.copy(isDefault = it.id == id) })

    fun removePlace(id: String) {
        val list = _places.value.filterNot { it.id == id }
        val fixed = if (list.isNotEmpty() && list.none { it.isHome }) list.mapIndexed { i, p -> if (i == 0) p.copy(isHome = true, isDefault = true, alertPrefs = p.alertPrefs.copy(notify = true)) else p } else list
        savePlaces(fixed)
        listOf("weather_$id", "alerts_$id", "resources_$id", "power_$id").forEach { store.delete(it) }
        if (_selected.value == id) fixed.firstOrNull()?.let { select(it.id) }
    }

    fun addFavorite(f: FavoriteArea) { if (_favorites.value.none { it.id == f.id }) saveFavs(_favorites.value + f) }
    fun updateFavorite(f: FavoriteArea) = saveFavs(_favorites.value.map { if (it.id == f.id) f else it })
    fun removeFavorite(id: String) = saveFavs(_favorites.value.filterNot { it.id == id })
    private fun saveFavs(l: List<FavoriteArea>) { _favorites.value = l; store.write("favorites", ListSerializer(FavoriteArea.serializer()), l) }

    // -------------------- weather --------------------
    private fun <T> fresh(c: Cached<T>?, ttlMs: Long) = c != null && System.currentTimeMillis() - c.fetchedAtMs < ttlMs

    fun cachedWeather(placeId: String): Cached<WeatherData>? = store.read("weather_$placeId", Cached.serializer(WeatherData.serializer()))

    suspend fun weather(place: Place, force: Boolean = false): Loaded<WeatherData> {
        val cached = cachedWeather(place.id)
        if (!force && fresh(cached, 10 * 60_000L)) return Loaded(cached!!.value, cached.fetchedAtMs)
        return try {
            val (data, pt) = NwsSource.weather(place, cached?.value)
            store.write("weather_${place.id}", Cached.serializer(WeatherData.serializer()), Cached(data, data.fetchedAtMs))
            // Keep place metadata (time zone, county) current without clobbering user edits.
            val cur = _places.value.firstOrNull { it.id == place.id }
            if (cur != null && (cur.timeZone != pt.timeZone || cur.countyFips != pt.countyFips || cur.state != pt.state)) {
                updatePlace(cur.copy(timeZone = pt.timeZone ?: cur.timeZone, countyFips = pt.countyFips ?: cur.countyFips, state = pt.state ?: cur.state))
            }
            Loaded(data, data.fetchedAtMs)
        } catch (e: Exception) {
            if (cached != null) Loaded(cached.value, cached.fetchedAtMs, e.message, stale = true) else Loaded(null, null, e.message ?: "Weather unavailable")
        }
    }

    // -------------------- alerts --------------------
    suspend fun alertsFor(place: Place, force: Boolean = false): Loaded<AlertSet> {
        val key = "alerts_${place.id}"
        val cached = store.read(key, AlertSet.serializer())
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAtMs < 90_000L) return Loaded(cached, cached.fetchedAtMs)
        return try {
            val set = AlertSet(System.currentTimeMillis(), NwsSource.alertsForPoint(place.lat, place.lon))
            store.write(key, AlertSet.serializer(), set)
            Loaded(set, set.fetchedAtMs)
        } catch (e: Exception) { if (cached != null) Loaded(cached, cached.fetchedAtMs, e.message, true) else Loaded(null, null, e.message) }
    }

    suspend fun alertsForFavorite(f: FavoriteArea): List<Alert> {
        val all = NwsSource.alertsForState(f.state)
        return if (f.countyFips != null) all.filter { a -> a.sameCodes.any { it.endsWith(f.countyFips) } } else all
    }

    /** Alerts feed for the requested scope; each alert tagged with the place/area that matched it. */
    suspend fun feed(scope: FeedScope, force: Boolean = false): Loaded<List<Pair<Alert, String>>> = coroutineScope {
        val key = "feed_${scope.name}"
        val cached = store.read(key, Cached.serializer(ListSerializer(AlertTag.serializer())))
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAtMs < 90_000L) return@coroutineScope Loaded(cached.value.map { it.alert to it.label }, cached.fetchedAtMs)
        try {
            val out = LinkedHashMap<String, Pair<Alert, String>>()
            when (scope) {
                FeedScope.HOME -> home()?.let { h -> NwsSource.alertsForPoint(h.lat, h.lon).forEach { out.putIfAbsent(it.id, it to h.name) } }
                FeedScope.SAVED -> _places.value.map { p -> async { p to runCatching { NwsSource.alertsForPoint(p.lat, p.lon) } } }
                    .map { it.await() }.also { r -> if (r.isNotEmpty() && r.all { it.second.isFailure }) throw r.first().second.exceptionOrNull()!! }
                    .forEach { (p, res) -> res.getOrNull()?.forEach { out.putIfAbsent(it.id, it to p.name) } }
                FeedScope.FAVORITES -> _favorites.value.map { f -> async { f to runCatching { alertsForFavorite(f) } } }
                    .map { it.await() }.also { r -> if (r.isNotEmpty() && r.all { it.second.isFailure }) throw r.first().second.exceptionOrNull()!! }
                    .forEach { (f, res) -> res.getOrNull()?.forEach { out.putIfAbsent(it.id, it to f.label) } }
                FeedScope.ALL -> NwsSource.alertsAll().forEach { out.putIfAbsent(it.id, it to "") }
            }
            val list = out.values.toList()
            store.write(key, Cached.serializer(ListSerializer(AlertTag.serializer())), Cached(list.map { AlertTag(it.first, it.second) }, System.currentTimeMillis()))
            Loaded(list, System.currentTimeMillis())
        } catch (e: Exception) {
            if (cached != null) Loaded(cached.value.map { it.alert to it.label }, cached.fetchedAtMs, e.message, true) else Loaded(null, null, e.message ?: "Alerts unavailable")
        }
    }

    // -------------------- storms --------------------
    suspend fun storms(force: Boolean = false): Loaded<List<ActiveStorm>> {
        val cached = store.read("storms", Cached.serializer(ListSerializer(StormSnapshot.serializer())))
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAtMs < 4 * 60_000L) return Loaded(cached.value.map { it.toStorm() }, cached.fetchedAtMs)
        return try {
            val list = NhcSource.activeStorms()
            store.write("storms", Cached.serializer(ListSerializer(StormSnapshot.serializer())), Cached(list.map { StormSnapshot.of(it) }, System.currentTimeMillis()))
            Loaded(list, System.currentTimeMillis())
        } catch (e: Exception) {
            if (cached != null) Loaded(cached.value.map { it.toStorm() }, cached.fetchedAtMs, e.message, true) else Loaded(null, null, e.message ?: "NHC unavailable")
        }
    }

    suspend fun stormBundle(storm: ActiveStorm): StormBundle = coroutineScope {
        val fixes = async { runCatching { NhcSource.aDeck(storm.id) }.getOrDefault(emptyList()) }
        val best = async { runCatching { NhcSource.bestTrack(storm.id) }.getOrDefault(emptyList()) }
        val gfx = async { runCatching { NhcSource.graphics(storm) }.getOrNull() }
        val text = async { storm.publicAdvisoryUrl?.let { runCatching { NhcSource.productText(it) }.getOrNull() } }
        val f = fixes.await(); val t = text.await()
        StormBundle(
            storm, Atcf.latestTrack(f, "OFCL"), Atcf.latestTrack(f, "AVNO"), ModelTracks.ecmwf(storm.id), best.await(), gfx.await(), t,
            t?.let { NhcSource.advisoryHighlights(it) }.orEmpty(), System.currentTimeMillis(),
        )
    }

    suspend fun stormReport(place: Place, bundle: StormBundle): StormReportData = coroutineScope {
        val buoys = async { runCatching { NdbcSource.nearby(place.lat, place.lon, 200.0, 240, 3) }.getOrDefault(emptyList()) }
        val gauges = async { runCatching { CoopsSource.nearby(place.lat, place.lon, 60.0, 2, 120) }.getOrDefault(emptyList()) }
        val recon = async { runCatching { ReconSource.summary(bundle.storm.id, bundle.storm.lat, bundle.storm.lon) }.getOrNull() }
        val alerts = async { alertsFor(place).value?.alerts.orEmpty() }
        val wx = cachedWeather(place.id)?.value ?: weather(place).value
        StormReportBuilder.build(StormInputs(
            place, bundle.storm, bundle.official, bundle.graphics?.cone?.flatMap { it.rings }.orEmpty(), bundle.highlights,
            alerts.await(), buoys.await(), gauges.await(), recon.await(), wx,
        ))
    }

    // -------------------- resources & power --------------------
    suspend fun resources(place: Place, force: Boolean = false): ResourceBundle = coroutineScope {
        val sh = async { runCatching { FemaSource.shelters(place.lat, place.lon) } }
        val dr = async { runCatching { FemaSource.recoveryCenters(place.lat, place.lon) } }
        val de = async { runCatching { if (place.state != null) FemaSource.declarations(place.state, place.countyFips) else emptyList() } }
        val s = sh.await(); val d = dr.await(); val c = de.await()
        ResourceBundle(s.getOrDefault(emptyList()), d.getOrDefault(emptyList()), c.getOrDefault(emptyList()),
            s.exceptionOrNull()?.message, d.exceptionOrNull()?.message, c.exceptionOrNull()?.message, System.currentTimeMillis())
    }

    suspend fun power(place: Place, force: Boolean = false): PowerBundle = coroutineScope {
        val cached = store.read("power_${place.id}", Cached.serializer(PowerCache.serializer()))
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAtMs < 11 * 60_000L) {
            return@coroutineScope PowerBundle(cached.value.utilities, cached.value.county, null, null, cached.fetchedAtMs)
        }
        val u = async { runCatching { UtilitySource.at(place.lat, place.lon) } }
        val o = async { runCatching { place.countyFips?.let { MassOutageSource.county(it) } } }
        val ur = u.await(); val or = o.await()
        val bundle = PowerBundle(ur.getOrDefault(emptyList()), or.getOrNull(), or.exceptionOrNull()?.message, ur.exceptionOrNull()?.message, System.currentTimeMillis())
        if (ur.isSuccess || or.isSuccess) store.write("power_${place.id}", Cached.serializer(PowerCache.serializer()), Cached(PowerCache(bundle.utilities, bundle.county), bundle.fetchedAtMs))
        bundle
    }

    suspend fun nationalOutages(): Loaded<OutageNational> {
        val cached = store.read("outage_national", Cached.serializer(OutageNational.serializer()))
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < 11 * 60_000L) return Loaded(cached.value, cached.fetchedAtMs)
        return try {
            val n = MassOutageSource.national() ?: throw IllegalStateException("No data")
            store.write("outage_national", Cached.serializer(OutageNational.serializer()), Cached(n, System.currentTimeMillis()))
            Loaded(n, System.currentTimeMillis())
        } catch (e: Exception) { if (cached != null) Loaded(cached.value, cached.fetchedAtMs, e.message, true) else Loaded(null, null, e.message) }
    }

    fun setUtility(place: Place, u: UtilityChoice?) = updatePlace(place.copy(utility = u))
}

@kotlinx.serialization.Serializable
data class AlertTag(val alert: Alert, val label: String)

@kotlinx.serialization.Serializable
data class PowerCache(val utilities: List<UtilityChoice>, val county: OutageCounty?)

@kotlinx.serialization.Serializable
data class StormSnapshot(
    val id: String, val bin: String, val name: String, val classification: String, val intensityKt: Int?, val pressureMb: Int?,
    val lat: Double, val lon: Double, val moveDirDeg: Int?, val moveSpeedMph: Int?, val lastUpdateMs: Long?, val advNum: String?, val advIssued: Long?,
    val pub: String?, val disc: String?, val gfx: String?, val fcst: String?, val prob: String?,
) {
    fun toStorm() = ActiveStorm(id, bin, name, classification, intensityKt, pressureMb, lat, lon, moveDirDeg, moveSpeedMph, lastUpdateMs, advNum, advIssued, pub, disc, gfx, fcst, prob)
    companion object {
        fun of(s: ActiveStorm) = StormSnapshot(s.id, s.bin, s.name, s.classification, s.intensityKt, s.pressureMb, s.lat, s.lon, s.moveDirDeg, s.moveSpeedMph,
            s.lastUpdateMs, s.advisoryNumber, s.advisoryIssuedMs, s.publicAdvisoryUrl, s.discussionUrl, s.graphicsUrl, s.forecastAdvisoryUrl, s.windProbUrl)
    }
}

/** ECMWF guidance provider. See docs/KNOWN_ISSUES.md: a free machine-readable ECMWF track feed is decoded here when available. */
object ModelTracks {
    suspend fun ecmwf(stormId: String): TrackLine? = null
}
