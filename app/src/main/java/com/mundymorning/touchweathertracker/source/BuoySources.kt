package com.mundymorning.touchweathertracker.source

import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.core.Units
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BuoyObs(
    val id: String, val name: String?, val lat: Double, val lon: Double, val distanceMi: Double, val bearingDeg: Double,
    val timeMs: Long, val windMph: Double?, val gustMph: Double?, val windDirDeg: Double?,
    val pressureInHg: Double?, val waveHeightFt: Double?, val wavePeriodSec: Double?, val waterTempF: Double?, val airTempF: Double?,
)

data class WaterLevelObs(
    val id: String, val name: String, val lat: Double, val lon: Double, val distanceMi: Double,
    val timeMs: Long, val levelFtMllw: Double, val predictedFtMllw: Double?, val departureFt: Double?, val preliminary: Boolean,
)

/** NOAA NDBC latest observations (all stations in one text file) – https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt */
object NdbcSource {
    private const val S = "NOAA NDBC"
    private const val LATEST = "https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt"

    private fun num(s: String?): Double? = s?.takeIf { it != "MM" }?.toDoubleOrNull()

    fun parse(text: String, nowMs: Long = System.currentTimeMillis()): List<BuoyObs> {
        val out = mutableListOf<BuoyObs>()
        for (ln in text.lineSequence()) {
            if (ln.startsWith("#") || ln.isBlank()) continue
            val f = ln.trim().split(Regex("\\s+"))
            if (f.size < 21) continue
            val lat = num(f[1]) ?: continue; val lon = num(f[2]) ?: continue
            val t = runCatching { LocalDateTime.of(f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toInt()).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: continue
            out += BuoyObs(
                id = f[0], name = null, lat = lat, lon = lon, distanceMi = 0.0, bearingDeg = 0.0, timeMs = t,
                windDirDeg = num(f[8]), windMph = num(f[9])?.let { Units.mpsToMph(it) }, gustMph = num(f[10])?.let { Units.mpsToMph(it) },
                waveHeightFt = num(f[11])?.let { Units.mToFeet(it) }, wavePeriodSec = num(f[12]),
                pressureInHg = num(f[15])?.let { Units.hpaToInHg(it) },
                airTempF = num(f[17])?.let { Units.cToF(it) }, waterTempF = num(f[18])?.let { Units.cToF(it) },
            )
        }
        return out
    }

    /** Nearby buoys with a report no older than [maxAgeMin] and at least one meaningful reading. */
    suspend fun nearby(lat: Double, lon: Double, radiusMi: Double = 150.0, maxAgeMin: Int = 180, limit: Int = 4): List<BuoyObs> {
        val now = System.currentTimeMillis()
        return parse(Http.text(S, LATEST)).asSequence()
            .filter { now - it.timeMs <= maxAgeMin * 60_000L }
            .filter { it.windMph != null || it.waveHeightFt != null || it.pressureInHg != null }
            .map { it.copy(distanceMi = Geo.distanceMiles(lat, lon, it.lat, it.lon), bearingDeg = Geo.bearing(lat, lon, it.lat, it.lon)) }
            .filter { it.distanceMi <= radiusMi }
            .sortedBy { it.distanceMi }.take(limit).toList()
    }
}

/** NOAA CO-OPS water levels – https://api.tidesandcurrents.noaa.gov */
object CoopsSource {
    private const val S = "NOAA CO-OPS"
    private const val MD = "https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations.json?type=waterlevels"
    private const val DATA = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter"
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm", Locale.US)

    private data class Stn(val id: String, val name: String, val lat: Double, val lon: Double)
    @Volatile private var stations: List<Stn>? = null

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

    private suspend fun stations(): List<Stn> {
        stations?.let { return it }
        val root = Http.jsonElement(S, MD) as? JsonObject
        val list = (root?.get("stations") as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Stn(o["id"].str() ?: return@mapNotNull null, o["name"].str().orEmpty(), (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                (o["lng"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null)
        } ?: emptyList()
        stations = list
        return list
    }

    suspend fun nearby(lat: Double, lon: Double, radiusMi: Double = 60.0, limit: Int = 3, maxAgeMin: Int = 90): List<WaterLevelObs> {
        val now = System.currentTimeMillis()
        val near = stations().map { it to Geo.distanceMiles(lat, lon, it.lat, it.lon) }.filter { it.second <= radiusMi }.sortedBy { it.second }.take(limit + 3)
        val out = mutableListOf<WaterLevelObs>()
        for ((s, d) in near) {
            val obs = runCatching { latest(s, d) }.getOrNull() ?: continue
            if (now - obs.timeMs > maxAgeMin * 60_000L) continue
            out += obs
            if (out.size >= limit) break
        }
        return out
    }

    private suspend fun latest(s: Stn, distMi: Double): WaterLevelObs? {
        val end = LocalDateTime.now(ZoneOffset.UTC); val begin = end.minusHours(3)
        val common = "station=${s.id}&datum=MLLW&units=english&time_zone=gmt&format=json&application=TouchWeatherTracker"
        val wl = Http.jsonElement(S, "$DATA?product=water_level&begin_date=${fmt.format(begin).replace(" ", "%20")}&end_date=${fmt.format(end).replace(" ", "%20")}&$common") as? JsonObject ?: return null
        val rows = (wl["data"] as? JsonArray) ?: return null
        val last = rows.lastOrNull { (it as? JsonObject)?.get("v").str()?.toDoubleOrNull() != null } as? JsonObject ?: return null
        val t = last["t"].str() ?: return null
        val level = last["v"].str()!!.toDouble()
        val tMs = LocalDateTime.parse(t.replace(" ", "T")).toInstant(ZoneOffset.UTC).toEpochMilli()
        val pred = runCatching {
            val p = Http.jsonElement(S, "$DATA?product=predictions&begin_date=${fmt.format(begin).replace(" ", "%20")}&end_date=${fmt.format(end.plusHours(1)).replace(" ", "%20")}&interval=6&$common") as? JsonObject
            val preds = (p?.get("predictions") as? JsonArray)?.mapNotNull { it as? JsonObject }
            preds?.firstOrNull { it["t"].str() == t }?.get("v").str()?.toDoubleOrNull()
        }.getOrNull()
        val flags = last["f"].str().orEmpty()
        return WaterLevelObs(s.id, s.name, s.lat, s.lon, distMi, tMs, level, pred, pred?.let { level - it }, (last["q"].str() ?: "p") != "v" || flags.isBlank())
    }
}
