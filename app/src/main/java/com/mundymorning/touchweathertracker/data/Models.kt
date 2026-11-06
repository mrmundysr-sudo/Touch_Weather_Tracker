package com.mundymorning.touchweathertracker.data

import kotlinx.serialization.Serializable

// ---------------- Places ----------------

@Serializable
enum class AlertLevel { WARNING, WATCH, ADVISORY, STATEMENT }

/** Per-place notification preferences. Defaults follow the brief: only Home gets warnings by default. */
@Serializable
data class AlertPrefs(
    val notify: Boolean = false,
    val warnings: Boolean = true,
    val watches: Boolean = false,
    val advisories: Boolean = false,
    val statements: Boolean = false,
)

@Serializable
data class UtilityChoice(
    val name: String,
    val phone: String? = null,
    val website: String? = null,
    val state: String? = null,
    val manual: Boolean = false,
)

@Serializable
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isHome: Boolean = false,
    val isDefault: Boolean = false,
    val subtitle: String? = null,
    val state: String? = null,       // USPS abbreviation
    val countyName: String? = null,
    val countyFips: String? = null,  // 5-digit state+county FIPS
    val timeZone: String? = null,
    val usesDeviceLocation: Boolean = false,
    val alertPrefs: AlertPrefs = AlertPrefs(),
    val utility: UtilityChoice? = null,
)

/** A favourite state or county followed as a broader area of interest. */
@Serializable
data class FavoriteArea(
    val id: String,          // "ST:TX" or "CO:48201"
    val label: String,       // "Texas" / "Harris County, TX"
    val state: String,
    val countyFips: String? = null,
    val alertPrefs: AlertPrefs = AlertPrefs(),
)

// ---------------- Weather ----------------

@Serializable
data class Conditions(
    val tempF: Double? = null,
    val feelsLikeF: Double? = null,
    val summary: String? = null,
    val windMph: Double? = null,
    val gustMph: Double? = null,
    val windDirDeg: Double? = null,
    val humidityPct: Double? = null,
    val dewpointF: Double? = null,
    val pressureInHg: Double? = null,
    val visibilityMi: Double? = null,
    val precipLastHourIn: Double? = null,
    val stationId: String? = null,
    val stationName: String? = null,
    val observedAtMs: Long? = null,
)

@Serializable
data class HourlyPoint(
    val timeMs: Long,
    val tempF: Double?,
    val feelsLikeF: Double? = null,
    val precipChance: Double? = null,
    val precipIn: Double? = null,
    val windMph: Double? = null,
    val gustMph: Double? = null,
    val windDir: String? = null,
    val humidityPct: Double? = null,
    val summary: String? = null,
    val isDay: Boolean = true,
)

@Serializable
data class DailyPoint(
    val name: String,
    val startMs: Long,
    val highF: Double?,
    val lowF: Double?,
    val summary: String,
    val detail: String,
    val precipChance: Double? = null,
    val windText: String? = null,
)

@Serializable
data class SunTimes(val sunriseMs: Long?, val sunsetMs: Long?)

@Serializable
data class WeatherData(
    val placeId: String,
    val fetchedAtMs: Long,
    val gridId: String? = null,
    val forecastOffice: String? = null,
    val radarStation: String? = null,
    val forecastUpdatedMs: Long? = null,
    val conditions: Conditions? = null,
    val hourly: List<HourlyPoint> = emptyList(),
    val daily: List<DailyPoint> = emptyList(),
    val sun: SunTimes? = null,
)

// ---------------- Alerts ----------------

@Serializable
data class Alert(
    val id: String,
    val event: String,
    val level: AlertLevel,
    val severity: String,
    val urgency: String,
    val certainty: String,
    val headline: String?,
    val description: String,
    val instruction: String?,
    val areaDesc: String,
    val states: List<String>,
    val sameCodes: List<String>,     // 6-digit SAME (0 + state fips + county fips)
    val sentMs: Long?,
    val effectiveMs: Long?,
    val onsetMs: Long?,
    val expiresMs: Long?,
    val endsMs: Long?,
    val senderName: String,
    val messageType: String,         // Alert, Update, Cancel
    val response: String?,
    val lifeSafety: Boolean,
    /** Outer rings of polygon geometry as [lon,lat] pairs, when the alert carries geometry. */
    val polygons: List<List<List<Double>>> = emptyList(),
) {
    val endMs: Long? get() = endsMs ?: expiresMs
}

@Serializable
data class AlertSet(val fetchedAtMs: Long, val alerts: List<Alert>)

// ---------------- Cached envelope ----------------

@Serializable
data class Cached<T>(val value: T, val fetchedAtMs: Long)
