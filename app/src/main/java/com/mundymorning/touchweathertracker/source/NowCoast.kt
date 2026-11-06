package com.mundymorning.touchweathertracker.source

import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.core.Units

/** NOAA nowCOAST WMS (GOES East/West imagery). */
object NowCoast {
    const val WMS = "https://nowcoast.noaa.gov/geoserver/satellite/wms"
    private const val CAPS = "$WMS?service=WMS&request=GetCapabilities"

    enum class Band(val label: String, val layer: String) {
        VISIBLE("Visible", "goes_visible_imagery"),
        INFRARED("Infrared", "goes_longwave_imagery"),
        WATER_VAPOR("Water vapor", "goes_water_vapor_imagery"),
    }

    @Volatile private var cachedAt = 0L
    @Volatile private var times: Map<String, Long> = emptyMap()

    /** Latest available image time per layer, from the WMS capabilities `time` dimension default. */
    suspend fun latestTimes(): Map<String, Long> {
        if (System.currentTimeMillis() - cachedAt < 5 * 60_000L && times.isNotEmpty()) return times
        val xml = Http.text("nowCOAST", CAPS)
        val out = mutableMapOf<String, Long>()
        for (b in Band.values()) {
            val i = xml.indexOf("<Name>${b.layer}</Name>")
            if (i < 0) continue
            val seg = xml.substring(i, minOf(xml.length, i + 6000))
            Regex("<Dimension name=\"time\"[^>]*default=\"([^\"]+)\"").find(seg)?.groupValues?.get(1)?.let { Units.parseIso(it) }?.let { out[b.layer] = it }
        }
        times = out; cachedAt = System.currentTimeMillis()
        return out
    }
}
