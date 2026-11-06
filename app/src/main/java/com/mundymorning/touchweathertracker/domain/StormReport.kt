package com.mundymorning.touchweathertracker.domain

import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.*
import com.mundymorning.touchweathertracker.source.*
import java.util.Locale
import kotlin.math.roundToInt

/** What kind of statement an item is. The UI shows this as a label so observations, forecasts, warnings and guidance are never blurred. */
enum class Kind(val label: String) { OBSERVED("Observed"), FORECAST("Forecast"), WARNING("Official warning"), MODEL("Model guidance"), SUMMARY("Summary") }

data class ReportItem(val text: String, val kind: Kind, val source: String, val timeMs: Long? = null, val link: String? = null)
data class ReportSection(val title: String, val question: String, val items: List<ReportItem>, val emptyNote: String? = null)
data class DetailRow(val label: String, val value: String)

data class StormInputs(
    val place: Place,
    val storm: ActiveStorm,
    val officialTrack: TrackLine?,
    val coneRings: List<List<List<Double>>>,
    val advisoryHighlights: List<Pair<String, String>>,
    val placeAlerts: List<Alert>,
    val buoys: List<BuoyObs>,
    val waterLevels: List<WaterLevelObs>,
    val recon: ReconSummary?,
    val weather: WeatherData?,
    val nowMs: Long = System.currentTimeMillis(),
)

data class StormReportData(
    val storm: ActiveStorm,
    val placeName: String,
    val headline: String,
    val sections: List<ReportSection>,
    val details: List<DetailRow>,
    val threatens: Boolean,
    val generatedMs: Long,
)

object StormReportBuilder {
    private val TROPICAL_ALERTS = Regex("Hurricane|Tropical Storm|Storm Surge|Typhoon|Extreme Wind|Flash Flood|Flood Warning|Coastal Flood|Tornado|High Wind|Rip Current|Surf", RegexOption.IGNORE_CASE)

    fun ktToMph(kt: Int): Int = ((kt * 1.15078) / 5.0).roundToInt() * 5

    fun threatens(i: StormInputs): Boolean {
        val d = Geo.distanceMiles(i.place.lat, i.place.lon, i.storm.lat, i.storm.lon)
        if (d <= 600) return true
        if (i.coneRings.any { Geo.inRing(i.place.lat, i.place.lon, it) }) return true
        val path = i.officialTrack?.points?.map { it.lat to it.lon }.orEmpty()
        if (Geo.minDistanceToPath(i.place.lat, i.place.lon, path)?.first?.let { it <= 400 } == true) return true
        return i.placeAlerts.any { it.event.contains("Hurricane", true) || it.event.contains("Tropical Storm", true) || it.event.contains("Storm Surge", true) }
    }

    fun build(i: StormInputs): StormReportData {
        val tz = i.place.timeZone
        val s = i.storm
        val dist = Geo.distanceMiles(i.place.lat, i.place.lon, s.lat, s.lon)
        val bearingFromPlace = Geo.compass(Geo.bearing(i.place.lat, i.place.lon, s.lat, s.lon))
        val sections = mutableListOf<ReportSection>()
        val details = mutableListOf<DetailRow>()

        // 1. What is happening ------------------------------------------------------------------
        val what = mutableListOf<ReportItem>()
        val windTxt = s.intensityKt?.let { " with maximum sustained winds near ${ktToMph(it)} mph" }.orEmpty()
        val cat = ActiveStorm.saffir(s.intensityKt)?.let { " ($it)" }.orEmpty()
        val move = if (s.moveDirDeg != null && s.moveSpeedMph != null) ", moving ${Geo.compass(s.moveDirDeg.toDouble())} at ${s.moveSpeedMph} mph" else ""
        what += ReportItem(
            "${s.classLabel} ${s.name}$cat is centered about ${dist.roundToInt()} mi $bearingFromPlace of ${i.place.name}$windTxt$move." +
                (s.pressureMb?.let { " Minimum central pressure ${it} mb." } ?: ""),
            Kind.OBSERVED, "NHC advisory", s.advisoryIssuedMs ?: s.lastUpdateMs, s.publicAdvisoryUrl,
        )
        sections += ReportSection("What is happening", "Where is the storm and how strong is it?", what)

        // 2. Forecast for this place --------------------------------------------------------------
        val fc = mutableListOf<ReportItem>()
        val inCone = i.coneRings.any { Geo.inRing(i.place.lat, i.place.lon, it) }
        if (i.coneRings.isNotEmpty()) {
            fc += ReportItem(
                if (inCone) "${i.place.name} is inside the NHC forecast cone. The cone shows uncertainty in the storm's CENTER track — not the size of the storm or where wind, rain or surge will occur."
                else "${i.place.name} is outside the current NHC forecast cone. The cone shows uncertainty in the CENTER track only; impacts can extend well outside it.",
                Kind.FORECAST, "NHC forecast cone", s.advisoryIssuedMs, s.graphicsUrl,
            )
        }
        val track = i.officialTrack
        val path = track?.points?.map { it.lat to it.lon }.orEmpty()
        val approach = Geo.minDistanceToPath(i.place.lat, i.place.lon, path)
        if (track != null && approach != null) {
            val (d, pos) = approach
            val idx = pos.toInt().coerceIn(0, track.points.size - 1)
            val next = track.points.getOrNull(idx + 1)
            val tau = track.points[idx].tauHours + ((next?.tauHours ?: track.points[idx].tauHours) - track.points[idx].tauHours) * (pos - idx)
            val whenMs = track.initMs + (tau * 3_600_000).toLong()
            fc += if (d <= 400) ReportItem(
                "NHC's official forecast track brings the storm's center to within about ${d.roundToInt()} mi of ${i.place.name} around ${Units.dayClock(whenMs, tz)}. This is the closest approach of the forecast center, not an arrival time for any hazard.",
                Kind.FORECAST, "NHC official forecast (ATCF OFCL)", track.initMs,
            ) else ReportItem(
                "NHC's official forecast track stays more than ${(d / 50.0).roundToInt() * 50} mi from ${i.place.name} through the ${(track.points.last().tauHours / 24)}-day forecast.",
                Kind.FORECAST, "NHC official forecast (ATCF OFCL)", track.initMs,
            )
        }
        val w = i.weather
        if (w != null) {
            val next48 = w.hourly.filter { it.timeMs >= i.nowMs - 3_600_000 && it.timeMs <= i.nowMs + 48 * 3_600_000L }
            val maxWind = next48.mapNotNull { it.windMph }.maxOrNull(); val maxGust = next48.mapNotNull { it.gustMph }.maxOrNull()
            val rain = next48.mapNotNull { it.precipIn }.sum()
            if (maxWind != null || maxGust != null) {
                fc += ReportItem(
                    "Local NWS forecast for the next 48 hours: sustained winds up to ${maxWind?.roundToInt() ?: "—"} mph" + (maxGust?.let { ", gusts up to ${it.roundToInt()} mph" } ?: "") +
                        (if (rain >= 0.1) "; about ${String.format(Locale.US, "%.1f", rain)} in of rain" else "") + ".",
                    Kind.FORECAST, "NWS ${w.gridId ?: ""} gridded forecast", w.forecastUpdatedMs ?: w.fetchedAtMs,
                )
            }
        }
        sections += ReportSection("Forecast for ${i.place.name}", "What is forecast here?", fc, emptyNote = "No forecast could be retrieved for this place right now.")

        // 3. What equipment has measured ---------------------------------------------------------
        val obs = mutableListOf<ReportItem>()
        for (b in i.buoys) {
            val parts = mutableListOf<String>()
            b.windMph?.let { parts += "wind ${it.roundToInt()} mph" + (b.gustMph?.let { g -> ", gusts ${g.roundToInt()}" } ?: "") + (b.windDirDeg?.let { d -> " from ${Geo.compass(d)}" } ?: "") }
            b.waveHeightFt?.let { parts += "waves ${String.format(Locale.US, "%.1f", it)} ft" + (b.wavePeriodSec?.let { p -> " (${p.roundToInt()} s)" } ?: "") }
            b.pressureInHg?.let { parts += "pressure ${String.format(Locale.US, "%.2f", it)} inHg" }
            b.waterTempF?.let { parts += "water ${it.roundToInt()}°F" }
            if (parts.isEmpty()) continue
            obs += ReportItem("NDBC buoy ${b.id}, ${b.distanceMi.roundToInt()} mi ${Geo.compass(b.bearingDeg)} of ${i.place.name}: ${parts.joinToString("; ")}. One buoy does not represent the whole coast.",
                Kind.OBSERVED, "NOAA NDBC buoy ${b.id}", b.timeMs)
        }
        for (g in i.waterLevels) {
            val dep = g.departureFt?.let { d -> " — ${String.format(Locale.US, "%.1f", kotlin.math.abs(d))} ft ${if (d >= 0) "above" else "below"} the predicted tide" }.orEmpty()
            obs += ReportItem("${g.name} water-level gauge, ${g.distanceMi.roundToInt()} mi away: ${String.format(Locale.US, "%.1f", g.levelFtMllw)} ft above MLLW$dep. This is the measured water level, not a storm-surge forecast.",
                Kind.OBSERVED, "NOAA CO-OPS ${g.id}" + if (g.preliminary) " (preliminary)" else "", g.timeMs)
        }
        i.recon?.let { r ->
            val bits = mutableListOf<String>()
            r.maxSfmrKt?.let { bits += "highest surface wind estimate (SFMR) ${it} kt (${ktToMph(it)} mph)" }
            r.maxFlWindKt?.let { bits += "highest flight-level wind ${it} kt" }
            r.minExtrapSlpMb?.let { bits += "lowest extrapolated surface pressure ${String.format(Locale.US, "%.1f", it)} mb" }
            if (bits.isNotEmpty() && r.latestObsMs != null) obs += ReportItem(
                "Aircraft reconnaissance in the last ${((i.nowMs - r.windowStartMs) / 3_600_000)} h: ${bits.joinToString("; ")}. Flight-level winds are not surface winds.",
                Kind.OBSERVED, "NHC recon (HDOB)", r.latestObsMs)
            r.vortex?.let { v ->
                obs += ReportItem("Latest aircraft center fix: ${String.format(Locale.US, "%.2f", v.lat)}°, ${String.format(Locale.US, "%.2f", v.lon)}°" +
                    (v.minSlpMb?.let { "; minimum sea-level pressure ${it} mb" } ?: "") + (v.maxFlWindKt?.let { "; max flight-level wind ${it} kt" } ?: "") + ".",
                    Kind.OBSERVED, "NHC Vortex Data Message", v.timeMs)
            }
            val mbl = r.dropsondes.mapNotNull { d -> d.mblWindKt?.let { d } }
            if (mbl.isNotEmpty()) obs += ReportItem("${mbl.size} dropsonde${if (mbl.size > 1) "s" else ""} released nearby; strongest lowest-500-m mean wind reported ${mbl.maxOf { it.mblWindKt!! }} kt.",
                Kind.OBSERVED, "NHC dropsondes", mbl.maxOfOrNull { it.relMs ?: 0L })
        }
        sections += ReportSection("What nearby equipment measured", "What has actually been observed?", obs,
            emptyNote = "No fresh buoy, water-gauge or aircraft readings are available near ${i.place.name}. Missing readings are not a sign of safety.")

        // 4. Warnings and actions ----------------------------------------------------------------
        val act = mutableListOf<ReportItem>()
        i.placeAlerts.filter { TROPICAL_ALERTS.containsMatchIn(it.event) }.sortedBy { it.level.ordinal }.forEach { a ->
            act += ReportItem("${a.event}" + (a.endMs?.let { " until ${Units.dayClock(it, tz)}" } ?: "") + ". ${a.headline ?: a.areaDesc}", Kind.WARNING, a.senderName.ifBlank { "NWS" }, a.sentMs)
        }
        i.advisoryHighlights.firstOrNull { it.first.contains("WATCHES", true) }?.let { (_, body) ->
            act += ReportItem(body.take(420), Kind.WARNING, "NHC public advisory", s.advisoryIssuedMs, s.publicAdvisoryUrl)
        }
        act += ReportItem("Storm-surge watches/warnings and inundation maps come only from NHC and your local NWS office. Open the official peak storm-surge map for the latest forecast.", Kind.SUMMARY, "NHC", null, "https://www.nhc.noaa.gov/surge/")
        sections += ReportSection("Warnings and actions", "What warnings or actions apply?", act)

        // Details panel -------------------------------------------------------------------------
        details += DetailRow("Advisory", "#${s.advisoryNumber ?: "—"} issued ${s.advisoryIssuedMs?.let { Units.stamp(it, tz) } ?: "—"}")
        details += DetailRow("Official forecast track", track?.let { "NHC OFCL initialized ${Units.stamp(it.initMs, tz)}" } ?: "Not available")
        details += DetailRow("Forecast cone", if (i.coneRings.isEmpty()) "Not available" else "NHC ArcGIS service")
        i.buoys.forEach { details += DetailRow("Buoy ${it.id}", "${it.distanceMi.roundToInt()} mi ${Geo.compass(it.bearingDeg)}; reported ${Units.ago(it.timeMs, i.nowMs)}") }
        i.waterLevels.forEach { details += DetailRow("Gauge ${it.id}", "${it.name}; ${it.distanceMi.roundToInt()} mi; reported ${Units.ago(it.timeMs, i.nowMs)}") }
        details += DetailRow("Recon", i.recon?.let { "${it.hdobCount} HDOB observations within range, latest ${it.latestObsMs?.let { t -> Units.ago(t, i.nowMs) } ?: "—"}" } ?: "No recent aircraft data near this storm")
        details += DetailRow("Definitions", "SFMR = stepped-frequency microwave radiometer surface-wind estimate. MLLW = mean lower low water datum. HDOB = high-density observation. Kt = knots (1 kt ≈ 1.15 mph).")

        val threat = threatens(i)
        val headline = when {
            i.placeAlerts.any { it.event.contains("Hurricane Warning", true) } -> "Hurricane Warning in effect for ${i.place.name}"
            i.placeAlerts.any { it.event.contains("Tropical Storm Warning", true) } -> "Tropical Storm Warning in effect for ${i.place.name}"
            i.placeAlerts.any { it.event.contains("Storm Surge", true) } -> "Storm Surge alert for ${i.place.name}"
            threat -> "${s.classLabel} ${s.name} — what it means for ${i.place.name}"
            else -> "${s.classLabel} ${s.name} is not currently forecast to affect ${i.place.name}"
        }
        return StormReportData(s, i.place.name, headline, sections, details, threat, i.nowMs)
    }
}
