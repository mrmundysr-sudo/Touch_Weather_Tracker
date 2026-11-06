package com.mundymorning.touchweathertracker.source

import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.core.SourceException
import com.mundymorning.touchweathertracker.core.Units
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Locale

data class ActiveStorm(
    val id: String, val bin: String, val name: String, val classification: String,
    val intensityKt: Int?, val pressureMb: Int?, val lat: Double, val lon: Double,
    val moveDirDeg: Int?, val moveSpeedMph: Int?, val lastUpdateMs: Long?,
    val advisoryNumber: String?, val advisoryIssuedMs: Long?,
    val publicAdvisoryUrl: String?, val discussionUrl: String?, val graphicsUrl: String?, val forecastAdvisoryUrl: String?,
    val windProbUrl: String?,
) {
    val basinCode: String get() = id.take(2).lowercase(Locale.US)
    val isHurricaneStrength: Boolean get() = (intensityKt ?: 0) >= 64
    val classLabel: String get() = classificationLabel(classification, intensityKt)

    companion object {
        fun classificationLabel(c: String, kt: Int?): String = when (c.uppercase(Locale.US)) {
            "TD" -> "Tropical Depression"; "TS" -> "Tropical Storm"; "HU" -> "Hurricane"
            "STD" -> "Subtropical Depression"; "STS" -> "Subtropical Storm"; "PTC" -> "Potential Tropical Cyclone"
            "PC" -> "Post-Tropical Cyclone"; "EX" -> "Extratropical"; "LO" -> "Low"; "DB" -> "Disturbance"
            else -> if ((kt ?: 0) >= 64) "Hurricane" else c
        }
        fun saffir(kt: Int?): String? = when {
            kt == null -> null; kt >= 137 -> "Category 5"; kt >= 113 -> "Category 4"; kt >= 96 -> "Category 3"
            kt >= 83 -> "Category 2"; kt >= 64 -> "Category 1"; else -> null
        }
    }
}

/** One map feature from the NHC ArcGIS tropical service (coordinates are [lon,lat]). */
data class GeoFeature(val kind: String, val rings: List<List<List<Double>>>, val line: List<List<Double>>, val attrs: Map<String, String>)

data class ForecastGraphics(
    val cone: List<GeoFeature>, val track: List<GeoFeature>, val points: List<GeoFeature>,
    val warnings: List<GeoFeature>, val past: List<GeoFeature>, val windRadii: List<GeoFeature>,
)

object NhcSource {
    private const val S = "NHC"
    private const val CURRENT = "https://www.nhc.noaa.gov/CurrentStorms.json"
    private const val ATCF_AID = "https://ftp.nhc.noaa.gov/atcf/aid_public"
    private const val ATCF_BTK = "https://ftp.nhc.noaa.gov/atcf/btk"
    private const val MAPSVC = "https://mapservices.weather.noaa.gov/tropical/rest/services/tropical/NHC_tropical_weather/MapServer"

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.dbl() = (this as? JsonPrimitive)?.doubleOrNull

    suspend fun activeStorms(): List<ActiveStorm> {
        val root = Http.jsonElement(S, CURRENT).obj() ?: return emptyList()
        return parseActive(root)
    }

    fun parseActive(root: JsonObject): List<ActiveStorm> {
        val arr = root["activeStorms"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val lat = o["latitudeNumeric"].dbl() ?: return@mapNotNull null
            val lon = o["longitudeNumeric"].dbl() ?: return@mapNotNull null
            val adv = o["publicAdvisory"].obj()
            ActiveStorm(
                id = id, bin = o["binNumber"].str().orEmpty(), name = o["name"].str().orEmpty().ifBlank { "Unnamed" },
                classification = o["classification"].str().orEmpty(),
                intensityKt = o["intensity"].str()?.toIntOrNull(), pressureMb = o["pressure"].str()?.toIntOrNull(),
                lat = lat, lon = lon,
                moveDirDeg = o["movementDir"].dbl()?.toInt(), moveSpeedMph = o["movementSpeed"].dbl()?.toInt(),
                lastUpdateMs = Units.parseIso(o["lastUpdate"].str()),
                advisoryNumber = adv?.get("advNum").str(), advisoryIssuedMs = Units.parseIso(adv?.get("issuance").str()),
                publicAdvisoryUrl = adv?.get("url").str(), discussionUrl = o["forecastDiscussion"].obj()?.get("url").str(),
                graphicsUrl = o["forecastGraphics"].obj()?.get("url").str(), forecastAdvisoryUrl = o["forecastAdvisory"].obj()?.get("url").str(),
                windProbUrl = o["windSpeedProbabilities"].obj()?.get("url").str(),
            )
        }
    }

    // ---------- ATCF-based tracks (official forecast, GFS guidance, best track) ----------

    suspend fun aDeck(stormId: String): List<AtcfFix> {
        val sid = stormId.lowercase(Locale.US)
        val bytes = Http.bytes(S, "$ATCF_AID/a$sid.dat.gz")
        return Atcf.parse(Atcf.gunzip(bytes))
    }

    suspend fun bestTrack(stormId: String): List<AtcfFix> {
        val txt = Http.text(S, "$ATCF_BTK/b${stormId.lowercase(Locale.US)}.dat")
        return Atcf.bestTrack(Atcf.parse(txt))
    }

    // ---------- ArcGIS graphics: cone, official track, watches/warnings ----------

    @Volatile private var layerNames: Map<String, Int>? = null

    private suspend fun layerId(name: String): Int? {
        val cached = layerNames
        if (cached != null) return cached[name]
        val root = Http.jsonElement(S, "$MAPSVC?f=json").obj()
        val m = (root?.get("layers") as? JsonArray)?.mapNotNull { l ->
            val o = l.obj() ?: return@mapNotNull null
            (o["name"].str() ?: return@mapNotNull null) to ((o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null)
        }?.toMap() ?: emptyMap()
        layerNames = m
        return m[name]
    }

    private suspend fun query(layerName: String): List<GeoFeature> {
        val id = layerId(layerName) ?: return emptyList()
        val url = "$MAPSVC/$id/query?where=${URLEncoder.encode("1=1", "UTF-8")}&outFields=*&returnGeometry=true&outSR=4326&f=geojson"
        val root = Http.jsonElement(S, url).obj() ?: return emptyList()
        return parseGeoJson(layerName, root)
    }

    fun parseGeoJson(kind: String, root: JsonObject): List<GeoFeature> {
        val feats = root["features"] as? JsonArray ?: return emptyList()
        return feats.mapNotNull { f ->
            val o = f.obj() ?: return@mapNotNull null
            val g = o["geometry"].obj() ?: return@mapNotNull null
            val attrs = o["properties"].obj()?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap() ?: emptyMap()
            fun pt(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { it.dbl() }?.takeIf { it.size >= 2 }
            fun ring(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { pt(it) } ?: emptyList()
            val rings = mutableListOf<List<List<Double>>>(); var line = emptyList<List<Double>>()
            val c = g["coordinates"]
            when (g["type"].str()) {
                "Polygon" -> (c as? JsonArray)?.forEach { rings += ring(it) }
                "MultiPolygon" -> (c as? JsonArray)?.forEach { poly -> (poly as? JsonArray)?.forEach { rings += ring(it) } }
                "LineString" -> line = ring(c)
                "MultiLineString" -> (c as? JsonArray)?.forEach { rings += ring(it) } // draw each part as an open ring
                "Point" -> pt(c)?.let { line = listOf(it) }
                "MultiPoint" -> line = ring(c)
            }
            GeoFeature(kind, rings, line, attrs)
        }
    }

    /** Official NHC graphics for an active storm (cone, track, points, warnings) from the NHC ArcGIS service. */
    suspend fun graphics(storm: ActiveStorm): ForecastGraphics {
        val b = storm.bin.ifBlank { return ForecastGraphics(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) }
        suspend fun q(n: String) = runCatching { query("$b $n") }.getOrDefault(emptyList())
        return ForecastGraphics(
            cone = q("Forecast Cone"), track = q("Forecast Track"), points = q("Forecast Points"),
            warnings = q("Watch-Warning"), past = q("Past Track"), windRadii = q("Forecast Wind Radii"),
        )
    }

    /** Public advisory text as plain text (NHC .shtml pages wrap the product in <pre>). */
    suspend fun productText(url: String): String {
        val html = Http.text(S, url, "text/html")
        val pre = Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: html
        return pre.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim()
    }

    /** Pull the "SUMMARY OF ..." block and "KEY MESSAGES"/"WATCHES AND WARNINGS" paragraph from a public advisory. */
    fun advisoryHighlights(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val sections = listOf("WATCHES AND WARNINGS", "SUMMARY OF", "KEY MESSAGES", "HAZARDS AFFECTING LAND", "STORM SURGE", "WIND", "RAINFALL", "NEXT ADVISORY")
        val blocks = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            val head = b.lineSequence().firstOrNull().orEmpty().trim()
            if (head.endsWith("...") || head.startsWith("...")) { i++; continue }
            val isHead = sections.any { head.uppercase(Locale.US).startsWith(it) } && head.length < 70
            if (isHead) {
                val body = StringBuilder()
                var j = i + 1
                while (j < blocks.size && body.length < 900) {
                    val nh = blocks[j].lineSequence().first().trim()
                    if (nh == nh.uppercase(Locale.US) && nh.length in 4..60 && !nh.contains('.') && nh.any { it.isLetter() }) break
                    body.append(blocks[j].replace(Regex("\\s+"), " ")).append(' ')
                    j++
                }
                out += head.trimEnd('.', '-', ' ') to body.toString().trim()
                i = j
            } else i++
        }
        return out.filter { it.second.isNotBlank() }
    }

    fun urlOrNull(s: String?) = s?.takeIf { it.startsWith("https://") }

    fun requireStorm(list: List<ActiveStorm>, id: String) = list.firstOrNull { it.id.equals(id, true) } ?: throw SourceException(S, "Storm $id is no longer active")
}
