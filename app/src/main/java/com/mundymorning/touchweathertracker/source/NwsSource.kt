package com.mundymorning.touchweathertracker.source

import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.core.SourceException
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale

/** Resolved NWS grid/zone metadata for a point. Source: api.weather.gov /points (U.S. Government work, no key). */
data class NwsPoint(
    val lat: Double, val lon: Double,
    val gridId: String, val gridX: Int, val gridY: Int,
    val forecastUrl: String, val hourlyUrl: String, val gridDataUrl: String, val stationsUrl: String,
    val countyZoneId: String?, val forecastZoneId: String?,
    val timeZone: String?, val radarStation: String?,
    val city: String?, val state: String?,
    val sunriseMs: Long?, val sunsetMs: Long?,
    val countyFips: String?,
)

object NwsSource {
    private const val S = "NWS"
    private const val BASE = "https://api.weather.gov"

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject?.value(): Double? = this?.get("value").dbl()

    suspend fun point(lat: Double, lon: Double): NwsPoint {
        val url = "$BASE/points/${String.format(Locale.US, "%.4f,%.4f", lat, lon)}"
        val root = Http.jsonElement(S, url).obj() ?: throw SourceException(S, "Unexpected NWS point response")
        val p = root["properties"].obj() ?: throw SourceException(S, "NWS point missing properties")
        val rel = p["relativeLocation"].obj()?.get("properties").obj()
        val astro = p["astronomicalData"].obj()
        val countyUrl = p["county"].str()
        val countyCode = countyUrl?.substringAfterLast('/')       // e.g. TXC201
        val fips = countyCode?.takeIf { it.length == 6 && it[2] == 'C' }?.let { stateFips(it.substring(0, 2)) + it.substring(3) }
        return NwsPoint(
            lat, lon,
            gridId = p["gridId"].str() ?: throw SourceException(S, "No grid for this location"),
            gridX = p["gridX"].dbl()?.toInt() ?: 0, gridY = p["gridY"].dbl()?.toInt() ?: 0,
            forecastUrl = p["forecast"].str().orEmpty(), hourlyUrl = p["forecastHourly"].str().orEmpty(),
            gridDataUrl = p["forecastGridData"].str().orEmpty(), stationsUrl = p["observationStations"].str().orEmpty(),
            countyZoneId = countyCode, forecastZoneId = p["forecastZone"].str()?.substringAfterLast('/'),
            timeZone = p["timeZone"].str(), radarStation = p["radarStation"].str(),
            city = rel?.get("city").str(), state = rel?.get("state").str(),
            sunriseMs = Units.parseIso(astro?.get("sunrise").str()), sunsetMs = Units.parseIso(astro?.get("sunset").str()),
            countyFips = fips,
        )
    }

    /** NWS UGC state letters -> FIPS prefix for counties ("TXC201" => "48" + "201"). */
    fun stateFips(abbr: String): String = STATE_FIPS[abbr] ?: ""

    suspend fun weather(place: Place, existing: WeatherData?): Pair<WeatherData, NwsPoint> {
        val pt = point(place.lat, place.lon)
        val (daily, dailyUpdated) = runCatching { daily(pt.forecastUrl) }.getOrElse { if (existing?.daily.isNullOrEmpty()) throw it else existing!!.daily to existing.forecastUpdatedMs }
        var hourly = runCatching { hourly(pt.hourlyUrl) }.getOrElse { if (existing?.hourly.isNullOrEmpty()) throw it else existing!!.hourly }
        val extra = runCatching { gridExtras(pt.gridDataUrl) }.getOrNull()
        if (extra != null) hourly = hourly.map { h -> extra.apply(h) }
        val cond = runCatching { currentConditions(pt) }.getOrNull() ?: existing?.conditions
        val data = WeatherData(
            placeId = place.id, fetchedAtMs = System.currentTimeMillis(), gridId = pt.gridId, forecastOffice = pt.gridId,
            radarStation = pt.radarStation, forecastUpdatedMs = dailyUpdated,
            conditions = cond, hourly = hourly, daily = daily, sun = SunTimes(pt.sunriseMs, pt.sunsetMs),
        )
        return data to pt
    }

    private fun parsePeriods(root: JsonObject): Pair<List<JsonObject>, Long?> {
        val p = root["properties"].obj()
        val periods = (p?.get("periods") as? JsonArray)?.mapNotNull { it.obj() } ?: emptyList()
        return periods to Units.parseIso(p?.get("updateTime").str() ?: p?.get("generatedAt").str())
    }

    private fun tempF(o: JsonObject): Double? {
        val v = o["temperature"].let { (it as? JsonPrimitive)?.doubleOrNull ?: it.obj().value() } ?: return null
        return if (o["temperatureUnit"].str() == "C") Units.cToF(v) else v
    }

    private fun windMph(s: String?): Double? = s?.let { Regex("\\d+").findAll(it).map { m -> m.value.toDouble() }.maxOrNull() }

    suspend fun hourly(url: String): List<HourlyPoint> {
        val root = Http.jsonElement(S, url).obj() ?: return emptyList()
        val (periods, _) = parsePeriods(root)
        return periods.mapNotNull { o ->
            val t = Units.parseIso(o["startTime"].str()) ?: return@mapNotNull null
            HourlyPoint(
                timeMs = t, tempF = tempF(o),
                precipChance = o["probabilityOfPrecipitation"].obj().value(),
                windMph = windMph(o["windSpeed"].str()), windDir = o["windDirection"].str(),
                humidityPct = o["relativeHumidity"].obj().value(),
                summary = o["shortForecast"].str(), isDay = (o["isDaytime"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }.take(120)
    }

    suspend fun daily(url: String): Pair<List<DailyPoint>, Long?> {
        val root = Http.jsonElement(S, url).obj() ?: return emptyList<DailyPoint>() to null
        val (periods, updated) = parsePeriods(root)
        // Pair day/night periods: NWS returns alternating periods. Build one DailyPoint per period but merge hi/lo by day.
        val out = mutableListOf<DailyPoint>()
        for (o in periods) {
            val start = Units.parseIso(o["startTime"].str()) ?: continue
            val isDay = (o["isDaytime"] as? JsonPrimitive)?.booleanOrNull ?: true
            val t = tempF(o)
            out += DailyPoint(
                name = o["name"].str().orEmpty(), startMs = start,
                highF = if (isDay) t else null, lowF = if (!isDay) t else null,
                summary = o["shortForecast"].str().orEmpty(), detail = o["detailedForecast"].str().orEmpty(),
                precipChance = o["probabilityOfPrecipitation"].obj().value(),
                windText = listOfNotNull(o["windDirection"].str(), o["windSpeed"].str()).joinToString(" ").ifBlank { null },
            )
        }
        return out to updated
    }

    // ---- gridpoint extras: feels-like, gusts, precip amount (hourly series) ----
    class GridExtras(
        private val feels: List<Triple<Long, Long, Double>>,
        private val gust: List<Triple<Long, Long, Double>>,
        private val qpf: List<Triple<Long, Long, Double>>,
    ) {
        private fun at(list: List<Triple<Long, Long, Double>>, t: Long) = list.firstOrNull { t >= it.first && t < it.second }?.third
        fun apply(h: HourlyPoint): HourlyPoint {
            val f = at(feels, h.timeMs)?.let { Units.cToF(it) }
            val g = at(gust, h.timeMs)?.let { Units.kmhToMph(it) }
            // QPF values are per validTime interval (usually 6 h); spread evenly across the hour as an approximation we do not display as hourly.
            val q = qpf.firstOrNull { h.timeMs >= it.first && h.timeMs < it.second }?.let { Units.mmToIn(it.third) / (((it.second - it.first) / 3_600_000.0).coerceAtLeast(1.0)) }
            return h.copy(feelsLikeF = f ?: h.feelsLikeF, gustMph = g ?: h.gustMph, precipIn = q ?: h.precipIn)
        }
    }

    private fun series(layer: JsonElement?): List<Triple<Long, Long, Double>> {
        val vals = (layer.obj()?.get("values") as? JsonArray) ?: return emptyList()
        return vals.mapNotNull { v ->
            val o = v.obj() ?: return@mapNotNull null
            val value = o["value"].dbl() ?: return@mapNotNull null
            val vt = o["validTime"].str() ?: return@mapNotNull null
            val (startS, durS) = vt.split("/").let { it.getOrNull(0) to it.getOrNull(1) }
            val start = Units.parseIso(startS) ?: return@mapNotNull null
            val dur = try { Duration.parse(durS ?: "PT1H").toMillis() } catch (_: Exception) { 3_600_000L }
            Triple(start, start + dur, value)
        }
    }

    suspend fun gridExtras(url: String): GridExtras {
        val root = Http.jsonElement(S, url).obj() ?: throw SourceException(S, "bad grid")
        val p = root["properties"].obj() ?: throw SourceException(S, "bad grid")
        return GridExtras(series(p["apparentTemperature"]), series(p["windGust"]), series(p["quantitativePrecipitation"]))
    }

    // ---- current conditions from nearest reporting station ----
    suspend fun currentConditions(pt: NwsPoint): Conditions? {
        val stations = Http.jsonElement(S, pt.stationsUrl).obj()?.get("features") as? JsonArray ?: return null
        for (f in stations.take(5)) {
            val props = f.obj()?.get("properties").obj() ?: continue
            val id = props["stationIdentifier"].str() ?: continue
            val name = props["name"].str()
            val obs = runCatching { Http.jsonElement(S, "$BASE/stations/$id/observations/latest?require_qc=false").obj() }.getOrNull() ?: continue
            val p = obs["properties"].obj() ?: continue
            val t = p["temperature"].obj().value()
            if (t == null) continue // station not reporting temperature: try next
            val observed = Units.parseIso(p["timestamp"].str())
            if (observed != null && System.currentTimeMillis() - observed > 3 * 3_600_000L) continue // stale: try next
            return Conditions(
                tempF = Units.cToF(t),
                feelsLikeF = (p["heatIndex"].obj().value() ?: p["windChill"].obj().value())?.let { Units.cToF(it) },
                summary = p["textDescription"].str()?.ifBlank { null },
                windMph = p["windSpeed"].obj().value()?.let { Units.kmhToMph(it) },
                gustMph = p["windGust"].obj().value()?.let { Units.kmhToMph(it) },
                windDirDeg = p["windDirection"].obj().value(),
                humidityPct = p["relativeHumidity"].obj().value(),
                dewpointF = p["dewpoint"].obj().value()?.let { Units.cToF(it) },
                pressureInHg = p["barometricPressure"].obj().value()?.let { Units.paToInHg(it) },
                visibilityMi = p["visibility"].obj().value()?.let { Units.mToMiles(it) },
                precipLastHourIn = p["precipitationLastHour"].obj().value()?.let { Units.mmToIn(it) },
                stationId = id, stationName = name, observedAtMs = observed,
            )
        }
        return null
    }

    // ---------------- Alerts ----------------

    suspend fun alertsForPoint(lat: Double, lon: Double): List<Alert> =
        parseAlerts(Http.jsonElement(S, "$BASE/alerts/active?point=${String.format(Locale.US, "%.4f,%.4f", lat, lon)}"))

    suspend fun alertsForState(state: String): List<Alert> =
        parseAlerts(Http.jsonElement(S, "$BASE/alerts/active?area=${URLEncoder.encode(state, "UTF-8")}"))

    suspend fun alertsAll(): List<Alert> = parseAlerts(Http.jsonElement(S, "$BASE/alerts/active?status=actual"))

    fun parseAlerts(root: JsonElement): List<Alert> {
        val feats = (root.obj()?.get("features") as? JsonArray) ?: return emptyList()
        return feats.mapNotNull { f -> parseAlert(f.obj() ?: return@mapNotNull null) }
    }

    fun parseAlert(f: JsonObject): Alert? {
        val p = f["properties"].obj() ?: return null
        val id = p["id"].str() ?: f["id"].str() ?: return null
        if (p["status"].str()?.equals("Actual", true) == false && p["status"].str() != null) return null // exclude Test/Exercise
        val event = p["event"].str() ?: return null
        val severity = p["severity"].str() ?: "Unknown"
        val geocode = p["geocode"].obj()
        val same = (geocode?.get("SAME") as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
        val ugc = (geocode?.get("UGC") as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
        val states = (ugc.map { it.take(2) } + same.mapNotNull { fipsToState(it.drop(1).take(2)) }).distinct()
        val polygons = mutableListOf<List<List<Double>>>()
        val geom = f["geometry"].obj()
        if (geom != null) {
            val type = geom["type"].str()
            val coords = geom["coordinates"] as? JsonArray
            fun ring(r: JsonElement?): List<List<Double>> = (r as? JsonArray)?.mapNotNull { pt -> (pt as? JsonArray)?.mapNotNull { it.dbl() }?.takeIf { it.size >= 2 } } ?: emptyList()
            if (type == "Polygon") coords?.firstOrNull()?.let { polygons += ring(it) }
            if (type == "MultiPolygon") coords?.forEach { poly -> (poly as? JsonArray)?.firstOrNull()?.let { polygons += ring(it) } }
        }
        val level = levelOf(event)
        val params = p["parameters"].obj()
        val threat = (params?.get("tornadoDamageThreat") as? JsonArray)?.firstOrNull().str()
        return Alert(
            id = id, event = event, level = level, severity = severity,
            urgency = p["urgency"].str() ?: "Unknown", certainty = p["certainty"].str() ?: "Unknown",
            headline = p["headline"].str(), description = p["description"].str().orEmpty(), instruction = p["instruction"].str(),
            areaDesc = p["areaDesc"].str().orEmpty(), states = states, sameCodes = same,
            sentMs = Units.parseIso(p["sent"].str()), effectiveMs = Units.parseIso(p["effective"].str()),
            onsetMs = Units.parseIso(p["onset"].str()), expiresMs = Units.parseIso(p["expires"].str()), endsMs = Units.parseIso(p["ends"].str()),
            senderName = p["senderName"].str().orEmpty(), messageType = p["messageType"].str() ?: "Alert",
            response = p["response"].str(), lifeSafety = isLifeSafety(event, severity, threat), polygons = polygons,
        )
    }

    fun levelOf(event: String): AlertLevel = when {
        event.endsWith("Warning", true) || event.contains("Emergency", true) -> AlertLevel.WARNING
        event.endsWith("Watch", true) -> AlertLevel.WATCH
        event.endsWith("Advisory", true) -> AlertLevel.ADVISORY
        else -> AlertLevel.STATEMENT
    }

    private val LIFE_SAFETY_EVENTS = setOf(
        "Tornado Warning", "Flash Flood Warning", "Hurricane Warning", "Storm Surge Warning", "Extreme Wind Warning",
        "Tsunami Warning", "Tornado Emergency", "Flash Flood Emergency", "Typhoon Warning", "Dust Storm Warning",
        "Severe Thunderstorm Warning", "Blizzard Warning", "Ice Storm Warning", "Civil Emergency Message", "Evacuation - Immediate",
        "Shelter In Place Warning", "Earthquake Warning", "Volcano Warning", "Avalanche Warning",
    )

    /** "Urgent life-safety" alerts that should notify even when a place only enables warnings. Never invented: based on event name and official severity. */
    fun isLifeSafety(event: String, severity: String, tornadoThreat: String?): Boolean =
        severity.equals("Extreme", true) || event in LIFE_SAFETY_EVENTS || tornadoThreat.equals("CATASTROPHIC", true)

    private val STATE_FIPS = mapOf(
        "AL" to "01","AK" to "02","AZ" to "04","AR" to "05","CA" to "06","CO" to "08","CT" to "09","DE" to "10","DC" to "11","FL" to "12",
        "GA" to "13","HI" to "15","ID" to "16","IL" to "17","IN" to "18","IA" to "19","KS" to "20","KY" to "21","LA" to "22","ME" to "23",
        "MD" to "24","MA" to "25","MI" to "26","MN" to "27","MS" to "28","MO" to "29","MT" to "30","NE" to "31","NV" to "32","NH" to "33",
        "NJ" to "34","NM" to "35","NY" to "36","NC" to "37","ND" to "38","OH" to "39","OK" to "40","OR" to "41","PA" to "42","RI" to "44",
        "SC" to "45","SD" to "46","TN" to "47","TX" to "48","UT" to "49","VT" to "50","VA" to "51","WA" to "53","WV" to "54","WI" to "55",
        "WY" to "56","AS" to "60","GU" to "66","MP" to "69","PR" to "72","VI" to "78",
    )
    private val FIPS_STATE = STATE_FIPS.entries.associate { it.value to it.key }
    fun fipsToState(fips: String): String? = FIPS_STATE[fips]
    fun stateName(abbr: String): String = STATE_NAMES[abbr] ?: abbr
    val allStates: List<Pair<String, String>> get() = STATE_NAMES.entries.sortedBy { it.value }.map { it.key to it.value }
    private val STATE_NAMES = mapOf(
        "AL" to "Alabama","AK" to "Alaska","AZ" to "Arizona","AR" to "Arkansas","CA" to "California","CO" to "Colorado","CT" to "Connecticut",
        "DE" to "Delaware","DC" to "District of Columbia","FL" to "Florida","GA" to "Georgia","HI" to "Hawaii","ID" to "Idaho","IL" to "Illinois",
        "IN" to "Indiana","IA" to "Iowa","KS" to "Kansas","KY" to "Kentucky","LA" to "Louisiana","ME" to "Maine","MD" to "Maryland",
        "MA" to "Massachusetts","MI" to "Michigan","MN" to "Minnesota","MS" to "Mississippi","MO" to "Missouri","MT" to "Montana","NE" to "Nebraska",
        "NV" to "Nevada","NH" to "New Hampshire","NJ" to "New Jersey","NM" to "New Mexico","NY" to "New York","NC" to "North Carolina",
        "ND" to "North Dakota","OH" to "Ohio","OK" to "Oklahoma","OR" to "Oregon","PA" to "Pennsylvania","RI" to "Rhode Island","SC" to "South Carolina",
        "SD" to "South Dakota","TN" to "Tennessee","TX" to "Texas","UT" to "Utah","VT" to "Vermont","VA" to "Virginia","WA" to "Washington",
        "WV" to "West Virginia","WI" to "Wisconsin","WY" to "Wyoming","PR" to "Puerto Rico","GU" to "Guam","VI" to "U.S. Virgin Islands",
        "AS" to "American Samoa","MP" to "Northern Mariana Islands",
    )
}
