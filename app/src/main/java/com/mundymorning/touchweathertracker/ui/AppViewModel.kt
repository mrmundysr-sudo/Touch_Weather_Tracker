package com.mundymorning.touchweathertracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mundymorning.touchweathertracker.TouchWeatherApp
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.domain.EventMode
import com.mundymorning.touchweathertracker.domain.EventSignal
import com.mundymorning.touchweathertracker.domain.StormReportData
import com.mundymorning.touchweathertracker.source.*
import com.mundymorning.touchweathertracker.work.EventActionReceiver
import com.mundymorning.touchweathertracker.work.EventModeController
import com.mundymorning.touchweathertracker.work.Scheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TodayState(
    val weather: Loaded<WeatherData>? = null, val alerts: Loaded<AlertSet>? = null, val outlook: NwsSource.Outlook? = null,
    val loading: Boolean = false,
)
data class FeedState(val scope: FeedScope = FeedScope.HOME, val data: Loaded<List<Pair<Alert, String>>>? = null, val loading: Boolean = false)
data class SearchState(val query: String = "", val results: List<GeoResult> = emptyList(), val loading: Boolean = false, val error: String? = null, val searched: Boolean = false)
data class StormsState(val storms: Loaded<List<ActiveStorm>>? = null, val loading: Boolean = false, val event: EventSignal? = null)
data class ResourcesState(val bundle: ResourceBundle? = null, val blueRoof: List<BlueRoofMission>? = null, val blueRoofError: String? = null, val loading: Boolean = false)
data class PowerState(val bundle: PowerBundle? = null, val national: Loaded<OutageNational>? = null, val loading: Boolean = false)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val tw = app as TouchWeatherApp
    val repo = tw.repo
    val mon = tw.monetization
    val prefs = repo.prefs

    val places = repo.places
    val favorites = repo.favorites
    val selected: StateFlow<Place?> = combine(repo.places, repo.selectedId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull { it.isDefault } ?: list.firstOrNull { it.isHome } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, repo.selected())

    val today = MutableStateFlow(TodayState())
    val feed = MutableStateFlow(FeedState())
    val search = MutableStateFlow(SearchState())
    val storms = MutableStateFlow(StormsState())
    val bundles = MutableStateFlow<Map<String, StormBundle>>(emptyMap())
    val bundleLoading = MutableStateFlow<Set<String>>(emptySet())
    val reports = MutableStateFlow<Map<String, StormReportData>>(emptyMap())
    val resources = MutableStateFlow(ResourcesState())
    val power = MutableStateFlow(PowerState())
    val prefsTick = prefs.changes
    val alertIndex = MutableStateFlow<Map<String, Alert>>(emptyMap())
    val message = MutableStateFlow<String?>(null)

    private var todayJob: Job? = null
    private var feedJob: Job? = null
    private var searchJob: Job? = null

    private fun index(alerts: Collection<Alert>) { alertIndex.update { it + alerts.associateBy { a -> a.id } } }

    // ------------------------------------------------------------------ Today
    fun refreshToday(force: Boolean = false) {
        // Read synchronously from the repository: `selected` (a derived StateFlow) lags a moment behind a just-changed selection.
        val place = repo.selected() ?: return
        todayJob?.cancel()
        todayJob = viewModelScope.launch {
            val cachedW = repo.cachedWeather(place.id)
            today.update { it.copy(weather = it.weather?.takeIf { w -> w.value?.placeId == place.id } ?: cachedW?.let { c -> Loaded(c.value, c.fetchedAtMs, stale = false) }, loading = true) }
            val w = async { repo.weather(place, force) }
            val a = async { repo.alertsFor(place, force) }
            val o = async { loadOutlook(place) }
            val wr = w.await(); val ar = a.await()
            ar.value?.alerts?.let { index(it) }
            today.value = TodayState(wr, ar, o.await(), false)
        }
    }

    private suspend fun loadOutlook(place: Place): NwsSource.Outlook? {
        val office = repo.cachedWeather(place.id)?.value?.forecastOffice ?: return null
        return runCatching { NwsSource.hazardousOutlook(office) }.getOrNull()
    }

    /**
     * Make [id] the Home place. Everything that shows Home switches immediately: Today (selection + refresh) and the widgets,
     * which are redrawn from cache at once and again after fresh data for the new Home arrives.
     */
    fun makeHome(id: String) {
        repo.setHome(id)
        repo.select(id)
        resources.value = ResourcesState(); power.value = PowerState()
        refreshToday(force = true)
        refreshHomeWidgets()
    }

    fun removePlace(id: String) {
        val wasHome = repo.places.value.firstOrNull { it.id == id }?.isHome == true
        repo.removePlace(id)
        resources.value = ResourcesState(); power.value = PowerState()
        refreshToday(force = true)
        if (wasHome) refreshHomeWidgets()
    }

    private fun refreshHomeWidgets() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            com.mundymorning.touchweathertracker.widget.WidgetUpdater.redraw(app)           // 1) instant, from cache
            repo.home()?.let { h -> kotlinx.coroutines.withTimeoutOrNull(25_000) { runCatching { repo.weather(h, true) }; runCatching { repo.alertsFor(h, true) } } }
            com.mundymorning.touchweathertracker.widget.WidgetUpdater.redraw(app)           // 2) with fresh data
        }
    }

    fun selectPlace(id: String) { repo.select(id); resources.value = ResourcesState(); power.value = PowerState(); refreshToday(); }

    // ------------------------------------------------------------------ Alerts feed
    fun loadFeed(scope: FeedScope = feed.value.scope, force: Boolean = false) {
        feedJob?.cancel()
        feed.update { it.copy(scope = scope, loading = true) }
        feedJob = viewModelScope.launch {
            val r = repo.feed(scope, force)
            r.value?.let { l -> index(l.map { it.first }) }
            feed.value = FeedState(scope, r, false)
        }
    }

    // ------------------------------------------------------------------ Places & search
    fun onSearchQuery(q: String) {
        search.update { it.copy(query = q, error = null) }
        searchJob?.cancel()
        if (q.trim().length < 3) { search.update { it.copy(results = emptyList(), searched = false, loading = false) }; return }
        searchJob = viewModelScope.launch {
            delay(450)
            search.update { it.copy(loading = true) }
            runCatching { CensusSource.search(q) }
                .onSuccess { r -> search.update { it.copy(results = r.filter { g -> com.mundymorning.touchweathertracker.core.Geo.inUsCoverage(g.lat, g.lon) }, loading = false, searched = true) } }
                .onFailure { e -> search.update { it.copy(loading = false, error = e.message ?: "Search failed", searched = true) } }
        }
    }

    fun clearSearch() { search.value = SearchState() }

    fun addFromResult(g: GeoResult, asHome: Boolean = false, onDone: (Place) -> Unit = {}) = viewModelScope.launch {
        val p = repo.addPlace(g.label, g.lat, g.lon, g.subtitle ?: g.kind, asHome)
        onDone(p); refreshToday()
    }

    fun addPinned(lat: Double, lon: Double, asHome: Boolean = false, onDone: (Place?) -> Unit = {}) = viewModelScope.launch {
        val rev = runCatching { CensusSource.reverse(lat, lon) }.getOrNull()
        if (rev == null) { message.value = "That spot is outside NWS coverage. Touch Weather Tracker covers the United States."; onDone(null); return@launch }
        val p = repo.addPlace(rev.first, lat, lon, listOfNotNull(rev.second).joinToString(), asHome)
        onDone(p); refreshToday()
    }

    fun addDevice(lat: Double, lon: Double, asHome: Boolean, onDone: (Place?) -> Unit = {}) = viewModelScope.launch {
        val rev = runCatching { CensusSource.reverse(lat, lon) }.getOrNull()
        if (rev == null) { message.value = "Your device location is outside NWS coverage."; onDone(null); return@launch }
        val p = repo.addPlace(rev.first.ifBlank { "My location" }, lat, lon, "Device location" + (rev.second?.let { " · $it" } ?: ""), asHome, device = true)
        onDone(p); refreshToday()
    }

    // ------------------------------------------------------------------ Storms
    fun loadStorms(force: Boolean = false) {
        viewModelScope.launch {
            storms.update { it.copy(loading = true) }
            val r = repo.storms(force)
            val hurr = runCatching { NwsSource.alertsAll().filter { it.event.startsWith("Hurricane") } }.getOrDefault(emptyList())
            val ev = r.value?.let { runCatching { EventMode.evaluate(it, hurr) }.getOrNull() }
            storms.value = StormsState(r, false, ev)
        }
    }

    fun loadBundle(storm: ActiveStorm, force: Boolean = false) {
        if (!force && bundles.value[storm.id] != null && System.currentTimeMillis() - bundles.value[storm.id]!!.fetchedAtMs < 5 * 60_000) return
        if (storm.id in bundleLoading.value) return
        viewModelScope.launch {
            bundleLoading.update { it + storm.id }
            val b = runCatching { repo.stormBundle(storm) }.getOrNull()
            if (b != null) bundles.update { it + (storm.id to b) }
            bundleLoading.update { it - storm.id }
        }
    }

    fun loadReport(place: Place, bundle: StormBundle) {
        viewModelScope.launch {
            val r = runCatching { repo.stormReport(place, bundle) }.getOrNull()
            if (r != null) reports.update { it + ("${bundle.storm.id}|${place.id}" to r) }
        }
    }

    // Event mode actions from the in-app card.
    fun eventAction(action: String, stormId: String) {
        EventModeController.apply(getApplication(), prefs, action, stormId)
        loadStorms()
    }

    // ------------------------------------------------------------------ Resources & power
    fun loadResources(force: Boolean = false) {
        val place = selected.value ?: return
        viewModelScope.launch {
            resources.update { it.copy(loading = true) }
            val b = repo.resources(place, force)
            val br = runCatching { BlueRoofSource.activeMissions(place.state) }
            resources.value = ResourcesState(b, br.getOrNull(), br.exceptionOrNull()?.message, false)
        }
    }

    fun loadPower(force: Boolean = false) {
        val place = selected.value ?: return
        viewModelScope.launch {
            power.update { it.copy(loading = true) }
            val b = repo.power(place, force)
            val n = repo.nationalOutages()
            power.value = PowerState(b, n, false)
        }
    }

    fun setUtility(place: Place, u: UtilityChoice?) = repo.setUtility(place, u)

    fun toast(msg: String?) { message.value = msg }
}
