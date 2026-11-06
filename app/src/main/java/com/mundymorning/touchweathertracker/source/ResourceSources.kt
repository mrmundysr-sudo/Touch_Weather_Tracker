package com.mundymorning.touchweathertracker.source

import com.mundymorning.touchweathertracker.core.Geo
import com.mundymorning.touchweathertracker.core.Http
import com.mundymorning.touchweathertracker.core.SourceException
import com.mundymorning.touchweathertracker.core.Units
import com.mundymorning.touchweathertracker.data.UtilityChoice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Locale

enum class PetStatus { ALLOWED, NOT_ALLOWED, UNKNOWN }

@Serializable
data class Shelter(
    val id: String, val name: String, val address: String, val city: String, val state: String, val zip: String,
    val lat: Double, val lon: Double, val distanceMi: Double, val status: String,
    val phone: String?, val org: String?, val pet: PetStatus, val petText: String?,
    val population: Int?, val capacity: Int?, val wheelchair: Boolean?, val updatedMs: Long?,
)

@Serializable
data class RecoveryCenter(
    val id: String, val name: String, val address: String, val city: String, val state: String,
    val lat: Double, val lon: Double, val distanceMi: Double, val status: String, val hours: String?,
    val disasterNumber: Int?, val updatedMs: Long?, val notes: String?,
)

@Serializable
data class Declaration(
    val disasterNumber: Int, val declarationString: String, val title: String, val incidentType: String,
    val state: String, val area: String, val declaredMs: Long?, val incidentBeginMs: Long?, val incidentEndMs: Long?,
    val individualAssistance: Boolean, val householdProgram: Boolean, val publicAssistance: Boolean, val hazardMitigation: Boolean,
    val iaDeadlineMs: Long?, val closedOut: Boolean,
)

private fun JsonElement?.o() = this as? JsonObject
private fun JsonElement?.s() = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
private fun JsonElement?.d() = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.b() = (this as? JsonPrimitive)?.booleanOrNull

/** FEMA ESF-6 shelter feed (NSS), Disaster Recovery Centers, and OpenFEMA declarations. All public FEMA services. */
object FemaSource {
    private const val S = "FEMA"
    private const val GIS = "https://gis.fema.gov/arcgis/rest/services"
    private const val OPENFEMA = "https://www.fema.gov/api/open/v2/DisasterDeclarationsSummaries"

    fun petStatus(code: String?, desc: String?): PetStatus {
        val t = listOfNotNull(code, desc).joinToString(" ").trim().uppercase(Locale.US)
        if (t.isBlank()) return PetStatus.UNKNOWN
        return when {
            Regex("\\b(NOT ALLOWED|NO PETS|NONE|NOT ACCEPT|NOT PERMITTED|PROHIBIT)").containsMatchIn(t) || t == "N" || t == "NO" -> PetStatus.NOT_ALLOWED
            Regex("\\b(ALLOWED|ACCEPT|PETS OK|YES|SERVICE ANIMALS?|CO-?LOCAT|PET FRIENDLY)").containsMatchIn(t) || t == "Y" -> PetStatus.ALLOWED
            else -> PetStatus.UNKNOWN // never infer pet acceptance from missing/ambiguous data
        }
    }

    private suspend fun spatial(layer: String, lat: Double, lon: Double, radiusMi: Double, where: String, extra: String = ""): List<JsonObject> {
        val url = "$GIS/$layer/query?where=${URLEncoder.encode(where, "UTF-8")}&geometry=$lon,$lat&geometryType=esriGeometryPoint&inSR=4326" +
            "&spatialRel=esriSpatialRelIntersects&distance=$radiusMi&units=esriSRUnit_StatuteMile&outFields=*&returnGeometry=true&outSR=4326&resultRecordCount=200&f=json$extra"
        val root = Http.jsonElement(S, url).o() ?: return emptyList()
        if (root["error"] != null) throw SourceException(S, "FEMA service error")
        return (root["features"] as? JsonArray)?.mapNotNull { it.o() } ?: emptyList()
    }

    suspend fun shelters(lat: Double, lon: Double, radiusMi: Double = 75.0): List<Shelter> {
        val feats = spatial("NSS/FEMA_NSS/FeatureServer/0", lat, lon, radiusMi, "1=1")
        return feats.mapNotNull { f ->
            val a = f["attributes"].o() ?: return@mapNotNull null
            val g = f["geometry"].o()
            val sLat = g?.get("y").d() ?: a["latitude"].d() ?: return@mapNotNull null
            val sLon = g?.get("x").d() ?: a["longitude"].d() ?: return@mapNotNull null
            val status = (a["shelter_status"].s() ?: "OPEN")
            val pet = petStatus(a["pet_accommodations_code"].s(), a["pet_accommodations_desc"].s())
            Shelter(
                id = a["shelter_id"].s() ?: return@mapNotNull null, name = a["shelter_name"].s() ?: "Shelter",
                address = a["address_1"].s() ?: a["address"].s().orEmpty(), city = a["city"].s().orEmpty(), state = a["state"].s().orEmpty(), zip = a["zip"].s().orEmpty(),
                lat = sLat, lon = sLon, distanceMi = Geo.distanceMiles(lat, lon, sLat, sLon), status = status,
                phone = a["org_main_phone"].s() ?: a["org_hotline_phone"].s() ?: a["org_other_phone"].s(), org = a["org_organization_name"].s() ?: a["org_name"].s(),
                pet = pet, petText = listOfNotNull(a["pet_accommodations_code"].s(), a["pet_accommodations_desc"].s()).joinToString(" – ").ifBlank { null },
                population = a["total_population"].d()?.toInt(), capacity = (a["evacuation_capacity"].d() ?: a["post_impact_capacity"].d())?.toInt(),
                wheelchair = a["wheelchair_accessible"].s()?.let { it.equals("Y", true) || it.equals("YES", true) },
                updatedMs = null,
            )
        }.filter { it.status.equals("OPEN", true) || it.status.equals("OPEN/ACTIVE", true) || it.status.equals("STANDBY", true) || it.status.equals("ACTIVE", true) || it.status.equals("FULL", true) }
            .sortedBy { it.distanceMi }.take(40)
    }

    suspend fun recoveryCenters(lat: Double, lon: Double, radiusMi: Double = 100.0): List<RecoveryCenter> {
        val feats = spatial("FEMA/DRC/FeatureServer/0", lat, lon, radiusMi, "1=1")
        return feats.mapNotNull { f ->
            val a = f["attributes"].o() ?: return@mapNotNull null
            val g = f["geometry"].o()
            val cLat = g?.get("y").d() ?: a["latitude"].d() ?: return@mapNotNull null
            val cLon = g?.get("x").d() ?: a["longitude"].d() ?: return@mapNotNull null
            RecoveryCenter(
                id = a["drc_id"].s() ?: a["objectid"].s() ?: return@mapNotNull null, name = a["drc_name"].s() ?: "Disaster Recovery Center",
                address = listOfNotNull(a["street_1"].s(), a["street_2"].s()).joinToString(", ").replace("&#43;", "+"),
                city = a["city"].s().orEmpty().lowercase(Locale.US).split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
                state = a["state"].s().orEmpty(), lat = cLat, lon = cLon, distanceMi = Geo.distanceMiles(lat, lon, cLat, cLon),
                status = a["status"].s() ?: "Unknown",
                hours = listOfNotNull(a["days_open"].s(), a["hours"].s()).joinToString(" · ").ifBlank { null },
                disasterNumber = a["primary_disaster"].d()?.toInt(), updatedMs = a["last_report_date"].d()?.toLong(), notes = a["notes"].s(),
            )
        }.sortedBy { it.distanceMi }.take(30)
    }

    /** Declarations touching a county (by FIPS) – most recent first, closed-out ones excluded. */
    suspend fun declarations(state: String, countyFips: String?): List<Declaration> {
        val filter = buildString {
            append("state eq '$state'")
            if (countyFips != null && countyFips.length == 5) append(" and (fipsCountyCode eq '${countyFips.substring(2)}' or fipsCountyCode eq '000')")
            append(" and declarationDate ge '${java.time.LocalDate.now().minusYears(2)}T00:00:00.000z'")
        }
        val url = "$OPENFEMA?\$filter=${URLEncoder.encode(filter, "UTF-8").replace("+", "%20")}&\$orderby=declarationDate%20desc&\$top=60"
        val root = Http.jsonElement(S, url).o() ?: return emptyList()
        val arr = root["DisasterDeclarationsSummaries"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e.o() ?: return@mapNotNull null
            Declaration(
                disasterNumber = o["disasterNumber"].d()?.toInt() ?: return@mapNotNull null,
                declarationString = o["femaDeclarationString"].s().orEmpty(), title = o["declarationTitle"].s().orEmpty(),
                incidentType = o["incidentType"].s().orEmpty(), state = o["state"].s().orEmpty(), area = o["designatedArea"].s().orEmpty(),
                declaredMs = Units.parseIso(o["declarationDate"].s()), incidentBeginMs = Units.parseIso(o["incidentBeginDate"].s()),
                incidentEndMs = Units.parseIso(o["incidentEndDate"].s()),
                individualAssistance = o["iaProgramDeclared"].b() == true, householdProgram = o["ihProgramDeclared"].b() == true,
                publicAssistance = o["paProgramDeclared"].b() == true, hazardMitigation = o["hmProgramDeclared"].b() == true,
                iaDeadlineMs = Units.parseIso(o["lastIAFilingDate"].s()), closedOut = o["disasterCloseoutDate"].s() != null,
            )
        }.filter { !it.closedOut && it.declarationString.startsWith("DR") || it.declarationString.startsWith("EM") }
            .distinctBy { it.disasterNumber to it.area }.take(20)
    }
}

@Serializable
data class BlueRoofMission(val disasterNumber: Int, val incident: String, val state: String, val agency: String, val receivedMs: Long?, val endMs: Long?, val summary: String)

/**
 * Operation Blue Roof status. The USACE website blocks automated access, so we use OpenFEMA mission assignments:
 * a USACE (COE-*) mission assignment whose statement of work mentions temporary roofing and whose period of performance is current.
 * This shows that a mission EXISTS for a state/disaster – county eligibility and sign-up are always confirmed with USACE.
 */
object BlueRoofSource {
    private const val S = "OpenFEMA"
    suspend fun activeMissions(state: String?): List<BlueRoofMission> {
        val since = java.time.LocalDate.now().minusDays(180)
        val filter = "startswith(agencyId,'COE') and dateReceived ge '${since}T00:00:00.000z'" + (state?.let { " and stt eq '$it'" } ?: "")
        val url = "https://www.fema.gov/api/open/v2/MissionAssignments?\$filter=${URLEncoder.encode(filter, "UTF-8").replace("+", "%20")}&\$orderby=dateReceived%20desc&\$top=400"
        val arr = Http.jsonElement(S, url).o()?.get("MissionAssignments") as? JsonArray ?: return emptyList()
        val now = System.currentTimeMillis()
        return arr.mapNotNull { e ->
            val o = e.o() ?: return@mapNotNull null
            val text = (o["statementOfWork"].s().orEmpty() + " " + o["assistanceRequested"].s().orEmpty())
            if (!Regex("roof", RegexOption.IGNORE_CASE).containsMatchIn(text)) return@mapNotNull null
            val end = Units.parseIso(o["maPopEndDate"].s() ?: o["popEndDate"].s())
            if (end != null && end < now - 14L * 86_400_000L) return@mapNotNull null
            BlueRoofMission(o["disasterNumber"].d()?.toInt() ?: return@mapNotNull null, o["incidentName"].s().orEmpty(), o["stt"].s().orEmpty(), o["agency"].s().orEmpty(),
                Units.parseIso(o["dateReceived"].s()), end, o["statementOfWork"].s().orEmpty().take(260))
        }.distinctBy { it.disasterNumber to it.state }
    }
}

/** Likely local electric utility from public electric retail service-territory polygons (HIFLD data; public domain). */
object UtilitySource {
    private const val S = "Utility territories"
    // Third-party-hosted copy of the HIFLD "Electric Retail Service Territories" layer (the HIFLD open site is retired). If it goes away the app falls back to manual utility entry.
    private const val LAYER = "https://services3.arcgis.com/OYP7N6mAJJCyH6hd/arcgis/rest/services/Electric_Retail_Service_Territories_HIFLD/FeatureServer/0"

    suspend fun at(lat: Double, lon: Double): List<UtilityChoice> {
        val url = "$LAYER/query?geometry=$lon,$lat&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects" +
            "&outFields=NAME,STATE,TELEPHONE,WEBSITE,TYPE,CUSTOMERS&returnGeometry=false&f=json"
        val root = Http.jsonElement(S, url).o() ?: return emptyList()
        return (root["features"] as? JsonArray)?.mapNotNull { e ->
            val a = e.o()?.get("attributes").o() ?: return@mapNotNull null
            val name = a["NAME"].s() ?: return@mapNotNull null
            val web = a["WEBSITE"].s()?.takeIf { it.uppercase(Locale.US) != "NOT AVAILABLE" }
            UtilityChoice(
                name = name.lowercase(Locale.US).split(" ").joinToString(" ") { w -> if (w.length <= 3 && w.all { it.isLetter() } && w != "of" && w != "the") w.uppercase(Locale.US) else w.replaceFirstChar { it.uppercase() } },
                phone = a["TELEPHONE"].s()?.takeIf { it.uppercase(Locale.US) != "NOT AVAILABLE" },
                website = web?.let { if (it.startsWith("http", true)) it else "https://$it" }, state = a["STATE"].s(),
            ) to (a["CUSTOMERS"].d() ?: 0.0)
        }?.sortedByDescending { it.second }?.map { it.first } ?: emptyList()
    }
}

@Serializable
data class OutageCounty(
    val fips: String, val customersOut: Int, val customersTracked: Int, val pct: Double, val incidents: Int,
    val utilities: List<Pair<String, Int>>, val updatedMs: Long?, val asOfMs: Long?, val liveStatus: String?, val behindMin: Int?,
)

@Serializable
data class OutageState(val abbr: String, val name: String, val customersOut: Int, val countiesAffected: Int)

@Serializable
data class OutageNational(
    val customersOut: Int, val incidents: Int, val counties: Int, val updatedMs: Long?, val asOfMs: Long?, val stale: Boolean,
    val sourcesOk: Int, val sources: Int, val trackedCustomers: Long, val notLocalizedOut: Int, val states: List<OutageState>,
)

/** MassOutage API (CC BY 4.0, unofficial & supplemental). Attribution: "MassOutage (massoutage.com)". */
object MassOutageSource {
    private const val S = "MassOutage"
    private const val BASE = "https://massoutage.com/api/v1"

    suspend fun county(fips: String): OutageCounty? {
        val root = Http.jsonElement(S, "$BASE/live/counties/$fips").o() ?: return null
        val d = root["data"].o() ?: return null
        val live = root["meta"].o()?.get("live").o()
        return OutageCounty(
            fips = fips, customersOut = d["customers_out"].d()?.toInt() ?: 0, customersTracked = d["customers_tracked"].d()?.toInt() ?: 0,
            pct = d["pct"].d() ?: 0.0, incidents = d["incident_count"].d()?.toInt() ?: 0,
            utilities = (d["utilities"] as? JsonArray)?.mapNotNull { u -> u.o()?.let { (it["name"].s() ?: return@mapNotNull null) to (it["customers_out"].d()?.toInt() ?: 0) } } ?: emptyList(),
            updatedMs = Units.parseIso(d["updated_at"].s()), asOfMs = Units.parseIso(d["as_of"].s()),
            liveStatus = live?.get("status").s(), behindMin = live?.get("behind_min").d()?.toInt(),
        )
    }

    suspend fun national(): OutageNational? {
        val root = Http.jsonElement(S, "$BASE/live/summary").o() ?: return null
        val d = root["data"].o() ?: return null
        val n = d["national"].o() ?: return null
        val c = d["coverage"].o()
        return OutageNational(
            customersOut = n["customers_out"].d()?.toInt() ?: 0, incidents = n["incident_count"].d()?.toInt() ?: 0, counties = n["county_count"].d()?.toInt() ?: 0,
            updatedMs = Units.parseIso(n["updated_at"].s()), asOfMs = Units.parseIso(n["as_of"].s()), stale = n["stale"].b() == true,
            sourcesOk = c?.get("sources_ok").d()?.toInt() ?: 0, sources = c?.get("sources").d()?.toInt() ?: 0,
            trackedCustomers = c?.get("tracked_customers").d()?.toLong() ?: 0, notLocalizedOut = n["unmapped_out"].d()?.toInt() ?: 0,
            states = (d["states"] as? JsonArray)?.mapNotNull { s ->
                val o = s.o() ?: return@mapNotNull null
                OutageState(o["abbr"].s() ?: return@mapNotNull null, o["name"].s().orEmpty(), o["customers_out"].d()?.toInt() ?: 0, o["county_count"].d()?.toInt() ?: 0)
            } ?: emptyList(),
        )
    }
}
