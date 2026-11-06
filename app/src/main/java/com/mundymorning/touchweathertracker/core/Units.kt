package com.mundymorning.touchweathertracker.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Units {
    fun cToF(c: Double) = c * 9.0 / 5.0 + 32.0
    fun fToC(f: Double) = (f - 32.0) * 5.0 / 9.0
    fun mpsToMph(v: Double) = v * 2.236936
    fun kmhToMph(v: Double) = v * 0.621371
    fun knToMph(v: Double) = v * 1.150779
    fun mToMiles(v: Double) = v / 1609.344
    fun mToFeet(v: Double) = v * 3.28084
    fun mmToIn(v: Double) = v / 25.4
    fun paToInHg(v: Double) = v * 0.0002953
    fun hpaToInHg(v: Double) = v * 0.02953

    fun temp(f: Double?): String = f?.let { "${it.roundToInt()}°" } ?: "—"
    fun mph(v: Double?): String = v?.let { "${it.roundToInt()} mph" } ?: "—"
    fun pct(v: Double?): String = v?.let { "${it.roundToInt()}%" } ?: "—"
    fun miles(v: Double?): String = v?.let { if (it >= 10) "${it.roundToInt()} mi" else String.format(Locale.US, "%.1f mi", it) } ?: "—"
    fun inches(v: Double?): String = v?.let { String.format(Locale.US, "%.2f in", it) } ?: "—"

    // ---- time helpers (always render in the place's own time zone when known) ----
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayTimeFmt = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a z", Locale.US)

    fun zone(id: String?): ZoneId = try { if (id.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(id) } catch (_: Exception) { ZoneId.systemDefault() }

    fun at(epochMs: Long, zoneId: String?): ZonedDateTime = Instant.ofEpochMilli(epochMs).atZone(zone(zoneId))
    fun clock(epochMs: Long, zoneId: String? = null): String = timeFmt.format(at(epochMs, zoneId))
    fun dayClock(epochMs: Long, zoneId: String? = null): String = dayTimeFmt.format(at(epochMs, zoneId))
    fun dayName(epochMs: Long, zoneId: String? = null): String = dayFmt.format(at(epochMs, zoneId))
    fun stamp(epochMs: Long, zoneId: String? = null): String = dateTimeFmt.format(at(epochMs, zoneId))

    fun parseIso(s: String?): Long? = if (s.isNullOrBlank()) null else try {
        ZonedDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) { try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { null } }

    /** "12 min ago", "3 h ago" etc. */
    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((now - epochMs) / 60000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 60 * 36 -> "${m / 60} h ago"
            else -> "${m / 1440} d ago"
        }
    }

    fun until(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((epochMs - now) / 60000)
        return when {
            m < 0 -> "expired"
            m < 60 -> "in $m min"
            m < 60 * 36 -> "in ${m / 60} h"
            else -> "in ${m / 1440} d"
        }
    }
}
