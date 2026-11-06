package com.mundymorning.touchweathertracker.core

import kotlin.math.*

object Geo {
    private const val R_KM = 6371.0088

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R_KM * asin(min(1.0, sqrt(a)))
    }

    fun distanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double) = distanceKm(lat1, lon1, lat2, lon2) * 0.621371

    /** Initial bearing 0..360 from point 1 to point 2. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private val POINTS16 = arrayOf("N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW")
    fun compass(deg: Double): String = POINTS16[(((deg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]

    /** Great-circle destination from a point. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, km: Double): Pair<Double, Double> {
        val d = km / R_KM; val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
    }

    /** Rough CONUS+AK+HI+territories bounding test used only to reject obviously non-US searches before calling NWS. */
    fun inUsCoverage(lat: Double, lon: Double): Boolean {
        val conus = lat in 24.0..49.6 && lon in -125.0..-66.5
        val alaska = lat in 51.0..71.6 && (lon in -170.0..-129.0 || lon >= 172.0 || lon <= -180.0)
        val hawaii = lat in 18.5..22.5 && lon in -160.6..-154.5
        val pr = lat in 17.5..18.7 && lon in -68.0..-64.4
        val guam = lat in 13.0..14.0 && lon in 144.4..145.1
        return conus || alaska || hawaii || pr || guam
    }

    /** Ray-casting point in ring test; ring points are [lon, lat]. */
    fun inRing(lat: Double, lon: Double, ring: List<List<Double>>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i][0]; val yi = ring[i][1]; val xj = ring[j][0]; val yj = ring[j][1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Minimum distance (miles) from a point to a polyline of (lat,lon) vertices, sampling each segment; returns distance and fraction index along path. */
    fun minDistanceToPath(lat: Double, lon: Double, path: List<Pair<Double, Double>>, steps: Int = 12): Pair<Double, Double>? {
        if (path.isEmpty()) return null
        var best = Double.MAX_VALUE; var bestPos = 0.0
        if (path.size == 1) return distanceMiles(lat, lon, path[0].first, path[0].second) to 0.0
        for (i in 0 until path.size - 1) {
            for (k in 0..steps) {
                val f = k / steps.toDouble()
                val pl = path[i].first + (path[i + 1].first - path[i].first) * f
                val po = path[i].second + (path[i + 1].second - path[i].second) * f
                val d = distanceMiles(lat, lon, pl, po)
                if (d < best) { best = d; bestPos = i + f }
            }
        }
        return best to bestPos
    }
}
