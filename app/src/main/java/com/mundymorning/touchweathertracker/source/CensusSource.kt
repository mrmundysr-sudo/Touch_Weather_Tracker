package com.mundymorning.touchweathertracker.source

import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.core.SourceException
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Locale

data class GeoResult(val label: String, val subtitle: String?, val lat: Double, val lon: Double, val state: String?, val kind: String)

/**
 * Location search using only free U.S. Census services (no paid geocoder):
 *  - Census Geocoder for street addresses
 *  - TIGERweb for ZIP codes (ZCTA) and city/town names (incorporated places + CDPs)
 */
object CensusSource {
    private const val S = "U.S. Census"
    private const val GEOCODER = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress"
    private const val TIGER = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer"

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Layer ids in TIGERweb change between releases, so resolve them by name once per process. */
    @Volatile private var layerIds: Map<String, Int>? = null

    private suspend fun layer(name: String): Int {
        val cached = layerIds
        if (cached != null) cached[name]?.let { return it }
        val root = Http.jsonElement(S, "$TIGER?f=json").obj()
        val layers = (root?.get("layers") as? JsonArray)?.mapNotNull { l ->
            val o = l.obj() ?: return@mapNotNull null
            val n = o["name"].str() ?: return@mapNotNull null
            n to (o["id"] as JsonPrimitive).int
        }?.toMap() ?: emptyMap()
        layerIds = layers
        return layers[name] ?: throw SourceException(S, "Census layer '$name' not found")
    }

    fun classify(q: String): String {
        val t = q.trim()
        return when {
            Regex("^\\d{5}(-\\d{4})?$").matches(t) -> "zip"
            Regex("^\\d+\\s+\\S+.*").matches(t) && t.any { it.isLetter() } -> "address"
            else -> "place"
        }
    }

    suspend fun search(query: String): List<GeoResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return when (classify(q)) {
            "zip" -> zip(q.take(5))
            "address" -> address(q)
            else -> {
                val p = place(q)
                if (p.isNotEmpty()) p else address(q) // "Main St, Springfield, IL"-style input without a number still tries geocoder
            }
        }
    }

    suspend fun address(q: String): List<GeoResult> {
        val url = "$GEOCODER?address=${URLEncoder.encode(q, "UTF-8")}&benchmark=Public_AR_Current&format=json"
        val root = Http.jsonElement(S, url).obj()
        val matches = (root?.get("result").obj()?.get("addressMatches") as? JsonArray) ?: return emptyList()
        return matches.mapNotNull { m ->
            val o = m.obj() ?: return@mapNotNull null
            val c = o["coordinates"].obj() ?: return@mapNotNull null
            val x = (c["x"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val y = (c["y"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val comp = o["addressComponents"].obj()
            val matched = o["matchedAddress"].str() ?: q
            GeoResult(matched.substringBefore(",").titleCase(), matched.substringAfter(",", "").trim().ifBlank { null },
                y, x, comp?.get("state").str(), "Address")
        }.take(5)
    }

    suspend fun zip(zip5: String): List<GeoResult> {
        val id = layer("2020 Census ZIP Code Tabulation Areas")
        val url = "$TIGER/$id/query?where=${URLEncoder.encode("ZCTA5='$zip5'", "UTF-8")}&outFields=ZCTA5,INTPTLAT,INTPTLON&returnGeometry=false&f=json"
        val feats = Http.jsonElement(S, url).obj()?.get("features") as? JsonArray ?: return emptyList()
        return feats.mapNotNull { f ->
            val a = f.obj()?.get("attributes").obj() ?: return@mapNotNull null
            val lat = a["INTPTLAT"].str()?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = a["INTPTLON"].str()?.toDoubleOrNull() ?: return@mapNotNull null
            GeoResult("ZIP $zip5", null, lat, lon, null, "ZIP code")
        }
    }

    suspend fun place(query: String): List<GeoResult> {
        // Accept "Houston", "Houston, TX", "Houston TX".
        val parts = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var name = parts.first()
        var stateAbbr: String? = parts.getOrNull(1)?.takeIf { it.length == 2 }?.uppercase(Locale.US)
        if (stateAbbr == null) {
            val m = Regex("^(.*\\S)\\s+([A-Za-z]{2})$").find(name)
            if (m != null && NwsSource.stateFips(m.groupValues[2].uppercase(Locale.US)).isNotEmpty()) {
                name = m.groupValues[1]; stateAbbr = m.groupValues[2].uppercase(Locale.US)
            }
        }
        val safe = name.replace("'", "''").replace("%", "")
        val out = mutableListOf<GeoResult>()
        for (layerName in listOf("Incorporated Places", "Census Designated Places")) {
            val id = layer(layerName)
            var where = "BASENAME LIKE '$safe%'"
            if (stateAbbr != null) NwsSource.stateFips(stateAbbr).takeIf { it.isNotEmpty() }?.let { where += " AND STATE='$it'" }
            val url = "$TIGER/$id/query?where=${URLEncoder.encode(where, "UTF-8")}&outFields=NAME,BASENAME,STATE,INTPTLAT,INTPTLON&returnGeometry=false&resultRecordCount=12&f=json"
            val feats = runCatching { Http.jsonElement(S, url).obj()?.get("features") as? JsonArray }.getOrNull() ?: continue
            for (f in feats) {
                val a = f.obj()?.get("attributes").obj() ?: continue
                val lat = a["INTPTLAT"].str()?.toDoubleOrNull() ?: continue
                val lon = a["INTPTLON"].str()?.toDoubleOrNull() ?: continue
                val st = NwsSource.fipsToState(a["STATE"].str().orEmpty())
                out += GeoResult(cleanPlaceName(a["NAME"].str() ?: continue), st?.let { NwsSource.stateName(it) }, lat, lon, st, "Place")
            }
        }
        // Exact base-name matches first, then alphabetical.
        return out.sortedWith(compareBy({ !it.label.substringBefore(",").equals(name, true) && !it.label.startsWith("$name ", true) }, { it.label }))
            .distinctBy { it.label + it.state }.take(10)
    }

    /** Resolve a tapped map point to a friendly name using NWS (U.S. only). */
    suspend fun reverse(lat: Double, lon: Double): Pair<String, String?>? {
        if (!Geo.inUsCoverage(lat, lon)) return null
        val pt = NwsSource.point(lat, lon)
        return (pt.city ?: "Dropped pin") to pt.state
    }

    /** U.S. states (USPS abbreviations) that a polyline of (lat,lon) vertices crosses or touches – used for the NHC landfall test. */
    suspend fun statesCrossed(path: List<Pair<Double, Double>>): List<String> {
        if (path.size < 2) return emptyList()
        val id = layer("States")
        val paths = path.joinToString(",", "[[", "]]") { "[${it.second},${it.first}]" }
        val geom = "{\"paths\":$paths,\"spatialReference\":{\"wkid\":4326}}"
        val url = "$TIGER/$id/query?geometry=${URLEncoder.encode(geom, "UTF-8")}&geometryType=esriGeometryPolyline&inSR=4326&spatialRel=esriSpatialRelIntersects&outFields=STUSAB,STATE&returnGeometry=false&f=json"
        val feats = Http.jsonElement(S, url).obj()?.get("features") as? JsonArray ?: return emptyList()
        return feats.mapNotNull { it.obj()?.get("attributes").obj()?.get("STUSAB").str() }
    }

    /** Find counties in a state for the "favorite county" picker. */
    suspend fun counties(stateAbbr: String): List<Pair<String, String>> {
        val id = layer("Counties")
        val fips = NwsSource.stateFips(stateAbbr).ifBlank { return emptyList() }
        val url = "$TIGER/$id/query?where=${URLEncoder.encode("STATE='$fips'", "UTF-8")}&outFields=NAME,GEOID&returnGeometry=false&orderByFields=NAME&resultRecordCount=500&f=json"
        val feats = Http.jsonElement(S, url).obj()?.get("features") as? JsonArray ?: return emptyList()
        return feats.mapNotNull { f ->
            val a = f.obj()?.get("attributes").obj() ?: return@mapNotNull null
            (a["GEOID"].str() ?: return@mapNotNull null) to (a["NAME"].str() ?: return@mapNotNull null)
        }
    }

    /** "Houston city" -> "Houston"; "Spring CDP" -> "Spring". */
    fun cleanPlaceName(n: String) = n.replace(Regex("\\s+(city|town|village|borough|municipality|CDP|city and borough|urban county)$", RegexOption.IGNORE_CASE), "")

    private fun String.titleCase() = lowercase(Locale.US).split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
