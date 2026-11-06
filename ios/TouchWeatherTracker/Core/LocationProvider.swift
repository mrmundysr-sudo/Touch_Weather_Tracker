import Foundation
import CoreLocation

/// One-shot device location. Only used after the user taps "Use my location" (no background tracking).
@MainActor
final class LocationProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLLocation?, Never>?
    private var authContinuation: CheckedContinuation<Bool, Never>?

    override init() { super.init(); manager.delegate = self; manager.desiredAccuracy = kCLLocationAccuracyKilometer }

    func requestCurrent() async -> CLLocation? {
        switch manager.authorizationStatus {
        case .notDetermined:
            let ok = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in authContinuation = c; manager.requestWhenInUseAuthorization() }
            if !ok { return nil }
        case .denied, .restricted: return nil
        default: break
        }
        if let last = manager.location, Date().timeIntervalSince(last.timestamp) < 300 { return last }   // recent fix: save battery
        return await withCheckedContinuation { (c: CheckedContinuation<CLLocation?, Never>) in continuation = c; manager.requestLocation() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in self.continuation?.resume(returning: locations.last); self.continuation = nil }
    }
    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.continuation?.resume(returning: nil); self.continuation = nil }
    }
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            let s = manager.authorizationStatus
            if s != .notDetermined { self.authContinuation?.resume(returning: s == .authorizedWhenInUse || s == .authorizedAlways); self.authContinuation = nil }
        }
    }
}
