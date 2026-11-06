package com.mundymorning.touchweathertracker.source

import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Http
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** A single high-density observation (HDOB) from an aircraft. */
data class Hdob(
    val timeMs: Long, val lat: Double, val lon: Double, val flightLevelWindKt: Int?, val peakFlWindKt: Int?,
    val sfmrWindKt: Int?, val extrapSlpMb: Double?, val staticPressureMb: Double?,
)

data class VortexFix(
    val stormId: String, val timeMs: Long, val lat: Double, val lon: Double, val minSlpMb: Int?, val maxFlWindKt: Int?, val product: String = "Vortex Data Message",
)

data class DropsondeObs(val relMs: Long?, val lat: Double?, val lon: Double?, val mblWindDirDeg: Int?, val mblWindKt: Int?)

data class ReconSummary(
    val hdobCount: Int, val windowStartMs: Long, val latestObsMs: Long?,
    val maxSfmrKt: Int?, val maxSfmrAt: Hdob?, val maxFlWindKt: Int?, val minExtrapSlpMb: Double?, val minExtrapSlpAt: Hdob?,
    val vortex: VortexFix?, val dropsondes: List<DropsondeObs>, val sourceNote: String,
)

/**
 * Official NHC reconnaissance products. Live context comes from the public NHC recon archive tree
 * (https://www.nhc.noaa.gov/archive/recon/<year>/<WMO header>/) which NHC updates in near real time, and the
 * latest Vortex Data Message from tgftp.nws.noaa.gov. No third-party display is used.
 */
object ReconSource {
    private const val S = "NHC recon"
    private const val ROOT = "https://www.nhc.noaa.gov/archive/recon"

    private fun dirsForBasin(basin: String) = when (basin.lowercase()) {
        "al" -> listOf("AHONT1" to "REPNT3")
        "ep" -> listOf("AHOPN1" to "REPPN3")
        else -> listOf("AHOPA1" to "REPPA3")
    }

    private val tsFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    private fun listFiles(html: String, prefix: String): List<Pair<String, Long>> =
        Regex("href=\"(${prefix}-[A-Z]+\\.(\\d{12})\\.txt)\"").findAll(html).mapNotNull { m ->
            val ts = runCatching { LocalDateTime.parse(m.groupValues[2], tsFmt).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            m.groupValues[1] to ts
        }.toList()

    /** Parse an HDOB message body; [issued] gives the UTC date base. */
    fun parseHdob(text: String, issuedMs: Long): List<Hdob> {
        val lines = text.lines().map { it.trim() }
        val base = java.time.Instant.ofEpochMilli(issuedMs).atZone(ZoneOffset.UTC).toLocalDate()
        val out = mutableListOf<Hdob>()
        for (ln in lines) {
            val f = ln.split(Regex("\\s+"))
            if (f.size < 13 || !f[0].matches(Regex("\\d{6}")) || !f[1].matches(Regex("\\d{4}[NS]"))) continue
            fun coord(s: String, isLat: Boolean): Double? {
                val deg = s.substring(0, if (isLat) 2 else 3).toIntOrNull() ?: return null
                val min = s.substring(if (isLat) 2 else 3, if (isLat) 4 else 5).toIntOrNull() ?: return null
                val v = deg + min / 60.0
                return if (s.last() == 'S' || s.last() == 'W') -v else v
            }
            val lat = coord(f[1], true) ?: continue
            val lon = coord(f[2], false) ?: continue
            val hh = f[0].substring(0, 2).toInt(); val mm = f[0].substring(2, 4).toInt(); val ss = f[0].substring(4, 6).toInt()
            var d: LocalDate = base
            var t = d.atTime(hh, mm, ss).toInstant(ZoneOffset.UTC).toEpochMilli()
            if (t - issuedMs > 3 * 3_600_000L) t -= 86_400_000L // obs from the previous UTC day
            val wind = f[8] // dddsss
            val fl = if (wind.length == 6 && wind.all { it.isDigit() }) wind.substring(3).toInt() else null
            val peak = f[9].toIntOrNull()
            val sfmr = f[10].toIntOrNull()
            val staticP = f[3].toIntOrNull()?.let { if (it >= 1000) it / 10.0 else it / 10.0 + 1000.0 }
            val extrapRaw = f[5].toIntOrNull()?.let { if (it >= 5000) it / 10.0 else it / 10.0 + 1000.0 } // 4-digit tenths mb, leading 1 dropped for >=1000
            // Extrapolated surface pressure is only meaningful for low-level (roughly 700 mb) flight legs.
            val extrap = extrapRaw?.takeIf { it in 850.0..1050.0 && staticP != null && staticP in 600.0..850.0 }
            out += Hdob(t, lat, lon, fl, peak, sfmr, extrap, staticP)
        }
        return out
    }

    /** "A. 09/22:47:30Z", "B. 29.79 deg N 086.76 deg W", "D. 972 mb", "MAX FL WIND 128 KT" */
    fun parseVdm(text: String, nowMs: Long = System.currentTimeMillis()): VortexFix? {
        if (!text.contains("VORTEX DATA MESSAGE")) return null
        val id = Regex("VORTEX DATA MESSAGE\\s+([A-Z]{2}\\d{6})").find(text)?.groupValues?.get(1) ?: return null
        val a = Regex("A\\.\\s+(\\d{2})/(\\d{2}):(\\d{2}):(\\d{2})Z").find(text) ?: return null
        val b = Regex("B\\.\\s+([\\d.]+) deg ([NS])\\s+([\\d.]+) deg ([EW])").find(text) ?: return null
        val lat = b.groupValues[1].toDouble() * if (b.groupValues[2] == "S") -1 else 1
        val lon = b.groupValues[3].toDouble() * if (b.groupValues[4] == "W") -1 else 1
        // resolve month/year from now: pick the most recent past date with that day-of-month
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC)
        var dt = now.withDayOfMonth(1).toLocalDate()
        val day = a.groupValues[1].toInt()
        var cand = runCatching { now.toLocalDate().withDayOfMonth(day) }.getOrNull()
        if (cand == null || cand.isAfter(now.toLocalDate().plusDays(1))) cand = runCatching { now.toLocalDate().minusMonths(1).withDayOfMonth(day) }.getOrNull() ?: return null
        val ms = cand.atTime(a.groupValues[2].toInt(), a.groupValues[3].toInt(), a.groupValues[4].toInt()).toInstant(ZoneOffset.UTC).toEpochMilli()
        val slp = Regex("\\nD\\.\\s+(\\d{3,4}) mb").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val maxFl = Regex("MAX FL WIND\\s+(\\d+)\\s*KT").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return VortexFix(id.lowercase(), ms, lat, lon, slp, maxFl)
    }

    /** Dropsonde TEMP DROP: MBL wind group "MBL WND ddfff", REL/SPG position+time. */
    fun parseDrop(text: String): DropsondeObs? {
        if (!text.contains("XXAA")) return null
        val mbl = Regex("MBL WND (\\d{3})(\\d{2,3})").find(text)
        val rel = Regex("REL (\\d{4})([NS])(\\d{5})([EW]) (\\d{6})").find(text)
        var lat: Double? = null; var lon: Double? = null
        if (rel != null) {
            lat = rel.groupValues[1].let { it.take(2).toInt() + it.drop(2).toInt() / 60.0 } * if (rel.groupValues[2] == "S") -1 else 1
            lon = rel.groupValues[3].let { it.take(3).toInt() + it.drop(3).toInt() / 60.0 } * if (rel.groupValues[4] == "W") -1 else 1
        }
        return DropsondeObs(null, lat, lon, mbl?.groupValues?.get(1)?.toIntOrNull(), mbl?.groupValues?.get(2)?.toIntOrNull())
    }

    /**
     * Gather recent recon near a storm. Only observations within [radiusKm] of the storm's reported center and
     * within [hours] are used. Returns null when there is no usable recent product (we then omit recon entirely).
     */
    suspend fun summary(stormId: String, centerLat: Double, centerLon: Double, hours: Int = 12, radiusKm: Double = 450.0): ReconSummary? {
        val now = System.currentTimeMillis()
        val since = now - hours * 3_600_000L
        val year = java.time.Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
        val basin = stormId.take(2)
        val (hdobDir, dropDir) = dirsForBasin(basin).first()
        val hdobs = mutableListOf<Hdob>()
        val listing = runCatching { Http.text(S, "$ROOT/$year/$hdobDir/") }.getOrNull().orEmpty()
        val files = listFiles(listing, hdobDir).filter { it.second >= since }.sortedByDescending { it.second }.take(18)
        for ((name, ts) in files) {
            val body = runCatching { Http.text(S, "$ROOT/$year/$hdobDir/$name") }.getOrNull() ?: continue
            hdobs += parseHdob(body, ts)
        }
        val near = hdobs.filter { it.timeMs >= since && Geo.distanceKm(centerLat, centerLon, it.lat, it.lon) <= radiusKm }

        val vdm = runCatching { parseVdm(Http.text(S, "https://tgftp.nws.noaa.gov/data/raw/ur/urnt12.knhc..txt")) }.getOrNull()
            ?.takeIf { it.stormId.equals(stormId, true) && it.timeMs >= since }
        val dropListing = runCatching { Http.text(S, "$ROOT/$year/$dropDir/") }.getOrNull().orEmpty()
        val drops = mutableListOf<DropsondeObs>()
        for ((name, ts) in listFiles(dropListing, dropDir).filter { it.second >= since }.sortedByDescending { it.second }.take(8)) {
            val d = runCatching { parseDrop(Http.text(S, "$ROOT/$year/$dropDir/$name")) }.getOrNull() ?: continue
            if (d.lat != null && d.lon != null && Geo.distanceKm(centerLat, centerLon, d.lat, d.lon) > radiusKm) continue
            drops += d.copy(relMs = ts)
        }
        if (near.isEmpty() && vdm == null && drops.isEmpty()) return null
        val sfmr = near.filter { it.sfmrWindKt != null }.maxByOrNull { it.sfmrWindKt!! }
        val slp = near.filter { it.extrapSlpMb != null }.minByOrNull { it.extrapSlpMb!! }
        return ReconSummary(
            hdobCount = near.size, windowStartMs = since, latestObsMs = (near.maxOfOrNull { it.timeMs }),
            maxSfmrKt = sfmr?.sfmrWindKt, maxSfmrAt = sfmr,
            maxFlWindKt = near.mapNotNull { it.peakFlWindKt }.maxOrNull(),
            minExtrapSlpMb = slp?.extrapSlpMb, minExtrapSlpAt = slp, vortex = vdm, dropsondes = drops,
            sourceNote = "NHC aircraft reconnaissance products (HDOB, Vortex Data Message, dropsondes)",
        )
    }
}
