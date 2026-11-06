import Foundation

public enum Geo {
    private static let rKm = 6371.0088

    public static func distanceKm(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180
        let dp = p2 - p1, dl = (lon2 - lon1) * .pi / 180
        let a = pow(sin(dp / 2), 2) + cos(p1) * cos(p2) * pow(sin(dl / 2), 2)
        return 2 * rKm * asin(min(1.0, sqrt(a)))
    }
    public static func distanceMiles(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        distanceKm(lat1, lon1, lat2, lon2) * 0.621371
    }

    /// Initial bearing 0..360 from point 1 to point 2.
    public static func bearing(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180, dl = (lon2 - lon1) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    private static let points16 = ["N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW"]
    public static func compass(_ deg: Double) -> String {
        let d = ((deg.truncatingRemainder(dividingBy: 360)) + 360).truncatingRemainder(dividingBy: 360)
        return points16[Int(d / 22.5 + 0.5) % 16]
    }

    /// Great-circle destination from a point.
    public static func destination(lat: Double, lon: Double, bearingDeg: Double, km: Double) -> (lat: Double, lon: Double) {
        let d = km / rKm, b = bearingDeg * .pi / 180
        let p1 = lat * .pi / 180, l1 = lon * .pi / 180
        let p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        let l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return (p2 * 180 / .pi, ((l2 * 180 / .pi) + 540).truncatingRemainder(dividingBy: 360) - 180)
    }

    /// Rough U.S. coverage test used only to reject obviously non-U.S. searches before calling NWS.
    public static func inUsCoverage(_ lat: Double, _ lon: Double) -> Bool {
        let conus = (24.0...49.6).contains(lat) && (-125.0...(-66.5)).contains(lon)
        let alaska = (51.0...71.6).contains(lat) && ((-170.0...(-129.0)).contains(lon) || lon >= 172.0 || lon <= -180.0)
        let hawaii = (18.5...22.5).contains(lat) && (-160.6...(-154.5)).contains(lon)
        let pr = (17.5...18.7).contains(lat) && (-68.0...(-64.4)).contains(lon)
        let guam = (13.0...14.0).contains(lat) && (144.4...145.1).contains(lon)
        return conus || alaska || hawaii || pr || guam
    }

    /// Ray-casting point-in-ring test; ring points are [lon, lat].
    public static func inRing(_ lat: Double, _ lon: Double, _ ring: [[Double]]) -> Bool {
        guard ring.count > 2 else { return false }
        var inside = false
        var j = ring.count - 1
        for i in 0..<ring.count {
            let xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1]
            if (yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi { inside.toggle() }
            j = i
        }
        return inside
    }

    /// Minimum distance (miles) from a point to a polyline of (lat, lon) vertices, plus the fractional vertex index of the closest point.
    public static func minDistanceToPath(_ lat: Double, _ lon: Double, _ path: [(Double, Double)], steps: Int = 12) -> (miles: Double, position: Double)? {
        guard !path.isEmpty else { return nil }
        if path.count == 1 { return (distanceMiles(lat, lon, path[0].0, path[0].1), 0) }
        var best = Double.greatestFiniteMagnitude, bestPos = 0.0
        for i in 0..<(path.count - 1) {
            for k in 0...steps {
                let f = Double(k) / Double(steps)
                let pl = path[i].0 + (path[i + 1].0 - path[i].0) * f
                let po = path[i].1 + (path[i + 1].1 - path[i].1) * f
                let d = distanceMiles(lat, lon, pl, po)
                if d < best { best = d; bestPos = Double(i) + f }
            }
        }
        return (best, bestPos)
    }
}
