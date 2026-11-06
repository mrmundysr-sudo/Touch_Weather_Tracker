package com.mundymorning.touchweathertracker.source

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** One row of an ATCF a-deck / best-track file for a given technique. */
data class AtcfFix(
    val basin: String, val cyclone: Int, val dtg: String, val tech: String, val tauHours: Int,
    val lat: Double, val lon: Double, val vmaxKt: Int?, val mslpMb: Int?, val type: String?,
)

/** A forecast/model track at a single initialisation time. */
data class TrackLine(val tech: String, val initDtg: String, val initMs: Long, val points: List<AtcfFix>)

object Atcf {
    /** "342N" -> 34.2 ; "713W" -> -71.3 */
    fun coord(s: String): Double? {
        val t = s.trim()
        if (t.length < 2) return null
        val v = t.dropLast(1).toDoubleOrNull() ?: return null
        val h = t.last().uppercaseChar()
        val d = v / 10.0
        return when (h) { 'N', 'E' -> d; 'S', 'W' -> -d; else -> null }
    }

    fun gunzip(bytes: ByteArray): String = GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }

    fun parse(text: String): List<AtcfFix> = text.lineSequence().mapNotNull { parseLine(it) }.toList()

    fun parseLine(line: String): AtcfFix? {
        val f = line.split(",")
        if (f.size < 10) return null
        val lat = coord(f[6]) ?: return null
        val lon = coord(f[7]) ?: return null
        val vmax = f[8].trim().toIntOrNull()?.takeIf { it > 0 }
        val mslp = f[9].trim().toIntOrNull()?.takeIf { it > 0 }
        return AtcfFix(
            basin = f[0].trim(), cyclone = f[1].trim().toIntOrNull() ?: return null, dtg = f[2].trim(), tech = f[4].trim(),
            tauHours = f[5].trim().toIntOrNull() ?: return null, lat = lat, lon = lon, vmaxKt = vmax, mslpMb = mslp,
            type = f.getOrNull(10)?.trim()?.ifBlank { null },
        )
    }

    fun dtgToMs(dtg: String): Long = try {
        java.time.LocalDateTime.parse(dtg, java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHH")).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    } catch (_: Exception) { 0L }

    /** Latest initialised track for [tech] (one point per tau; wind-radii duplicate rows collapsed). */
    fun latestTrack(fixes: List<AtcfFix>, tech: String): TrackLine? {
        val mine = fixes.filter { it.tech.equals(tech, true) }
        val dtg = mine.maxOfOrNull { it.dtg } ?: return null
        val pts = mine.filter { it.dtg == dtg }.distinctBy { it.tauHours }.sortedBy { it.tauHours }
        if (pts.isEmpty()) return null
        return TrackLine(tech, dtg, dtgToMs(dtg), pts)
    }

    /** Best-track (BEST) points in time order, de-duplicated by time. */
    fun bestTrack(fixes: List<AtcfFix>): List<AtcfFix> =
        fixes.filter { it.tech.equals("BEST", true) }.distinctBy { it.dtg }.sortedBy { it.dtg }
}
