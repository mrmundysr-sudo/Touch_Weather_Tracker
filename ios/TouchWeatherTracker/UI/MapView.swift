import SwiftUI
import MapKit
import TWCore

// MARK: tile overlays

private let originShift = 20037508.342789244
/// Web-Mercator bounding box of a slippy-map tile: [minx, miny, maxx, maxy].
func tileBbox(z: Int, x: Int, y: Int) -> [Double] {
    let size = 2 * originShift / Double(1 << z)
    let minx = -originShift + Double(x) * size, maxy = originShift - Double(y) * size
    return [minx, maxy - size, minx + size, maxy]
}

/// NOAA/NWS RIDGE2 / MRMS base reflectivity, drawn through the ArcGIS `export` endpoint as 256px tiles.
final class RadarTileOverlay: MKTileOverlay {
    let bucket: Int
    init(bucket: Int) { self.bucket = bucket; super.init(urlTemplate: nil); tileSize = CGSize(width: 256, height: 256); canReplaceMapContent = false; maximumZ = 11 }
    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        let b = tileBbox(z: path.z, x: path.x, y: path.y)
        return URL(string: String(format: "https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer/export?bbox=%.3f,%.3f,%.3f,%.3f&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&f=image&t=%d", b[0], b[1], b[2], b[3], bucket))!
    }
}
/// WMS GetMap tiles (nowCOAST GOES imagery).
final class WmsTileOverlay: MKTileOverlay {
    let layer: String; let bucket: Int
    init(layer: String, bucket: Int) { self.layer = layer; self.bucket = bucket; super.init(urlTemplate: nil); tileSize = CGSize(width: 256, height: 256); canReplaceMapContent = false; maximumZ = 9 }
    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        let b = tileBbox(z: path.z, x: path.x, y: path.y)
        return URL(string: String(format: "%@?service=WMS&version=1.3.0&request=GetMap&layers=%@&styles=&crs=EPSG:3857&bbox=%.3f,%.3f,%.3f,%.3f&width=256&height=256&format=image/png&transparent=true&t=%d", NowCoast.wms, layer, b[0], b[1], b[2], b[3], bucket))!
    }
}
/// USGS The National Map basemaps (free, public domain). ArcGIS tile order is z/y/x.
final class UsgsTileOverlay: MKTileOverlay {
    let service: String
    init(service: String) { self.service = service; super.init(urlTemplate: nil); tileSize = CGSize(width: 256, height: 256); canReplaceMapContent = true; maximumZ = 15 }
    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        URL(string: "https://basemap.nationalmap.gov/arcgis/rest/services/\(service)/MapServer/tile/\(path.z)/\(path.y)/\(path.x)")!
    }
}

final class StyledPolygon: MKPolygon { var fill = UIColor.clear; var stroke = UIColor.white; var width: CGFloat = 2; var alertId: String? }
final class StyledPolyline: MKPolyline { var color = UIColor.black; var width: CGFloat = 4; var dash: [NSNumber]? }
final class TWAnnotation: NSObject, MKAnnotation {
    enum Kind { case home, place, storm, forecastPoint, buoy, pin }
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var title: String?; var subtitle: String?; let kind: Kind; var stormId: String?
    init(_ c: CLLocationCoordinate2D, _ kind: Kind, title: String?, subtitle: String? = nil, stormId: String? = nil) { coordinate = c; self.kind = kind; self.title = title; self.subtitle = subtitle; self.stormId = stormId }
}

struct MapLayers: Equatable {
    var radar = true, warnings = true, tracks = true, buoys = false, gfs = false, ecmwf = false, imagery = false
    var satellite = "off"     // off | visible | infrared | watervapor
}

struct MapContent: Equatable {
    var layers: MapLayers
    var alerts: [Alert]
    var bundles: [StormBundle]
    var buoys: [BuoyObs]
    var places: [Place]
    var pin: CLLocationCoordinate2D?
    var bucket: Int
    static func == (a: MapContent, b: MapContent) -> Bool { a.key == b.key }
    var key: String {
        "\(layers)|\(alerts.count):\(alerts.map(\.id).joined().hashValue)|\(bundles.map { "\($0.storm.id)\($0.fetchedAtMs)" })|\(buoys.count)|\(places.map { "\($0.id)\($0.isHome)" })|\(pin?.latitude ?? 0),\(pin?.longitude ?? 0)|\(bucket)"
    }
}

struct MKMapRepresentable: UIViewRepresentable {
    let content: MapContent
    let focus: MapFocus?
    let onLongPress: (CLLocationCoordinate2D) -> Void
    let onOpenAlert: (String) -> Void
    let onOpenStorm: (String) -> Void
    let onFocusConsumed: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MKMapView {
        let m = MKMapView()
        m.delegate = context.coordinator
        m.pointOfInterestFilter = .excludingAll
        m.showsCompass = true
        m.cameraZoomRange = MKMapView.CameraZoomRange(minCenterCoordinateDistance: 20_000, maxCenterCoordinateDistance: 12_000_000)
        let lp = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.longPress(_:)))
        m.addGestureRecognizer(lp)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tap(_:)))
        tap.require(toFail: lp)
        m.addGestureRecognizer(tap)
        if let p = content.places.first(where: { $0.isHome }) ?? content.places.first {
            m.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), span: MKCoordinateSpan(latitudeDelta: 6, longitudeDelta: 6)), animated: false)
        } else { m.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 39, longitude: -96), span: MKCoordinateSpan(latitudeDelta: 30, longitudeDelta: 40)), animated: false) }
        return m
    }

    func updateUIView(_ m: MKMapView, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.lastKey != content.key { context.coordinator.rebuild(m, content) }
        if let f = focus, context.coordinator.lastFocus != f.nonce {
            context.coordinator.lastFocus = f.nonce
            m.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: f.lat, longitude: f.lon), span: MKCoordinateSpan(latitudeDelta: f.spanDegrees, longitudeDelta: f.spanDegrees)), animated: true)
            DispatchQueue.main.async { onFocusConsumed() }
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: MKMapRepresentable
        var lastKey = ""
        var lastFocus: UUID?
        private var warningRings: [(id: String, ring: [[Double]], level: AlertLevel)] = []
        init(_ p: MKMapRepresentable) { parent = p }

        func rebuild(_ m: MKMapView, _ c: MapContent) {
            lastKey = c.key
            m.removeOverlays(m.overlays); m.removeAnnotations(m.annotations)
            let L = c.layers
            m.addOverlay(UsgsTileOverlay(service: L.imagery ? "USGSImageryTopo" : "USGSTopo"), level: .aboveLabels)
            if L.satellite != "off" {
                let band: NowCoast.Band = L.satellite == "visible" ? .visible : L.satellite == "watervapor" ? .waterVapor : .infrared
                m.addOverlay(WmsTileOverlay(layer: band.rawValue, bucket: c.bucket), level: .aboveLabels)
            }
            if L.radar { m.addOverlay(RadarTileOverlay(bucket: c.bucket), level: .aboveLabels) }

            warningRings = []
            if L.warnings {
                for a in c.alerts.filter({ $0.level == .warning || $0.level == .watch }).sorted(by: { $0.level > $1.level }) {
                    let isWarn = a.level == .warning
                    for ring in a.polygons where ring.count >= 3 {
                        let poly = StyledPolygon(coordinates: ring.map { CLLocationCoordinate2D(latitude: $0[1], longitude: $0[0]) }, count: ring.count)
                        let col = isWarn ? UIColor(red: 0.83, green: 0.18, blue: 0.18, alpha: 1) : UIColor(red: 0.96, green: 0.49, blue: 0, alpha: 1)
                        poly.fill = col.withAlphaComponent(0.2); poly.stroke = col; poly.width = isWarn ? 3 : 2; poly.alertId = a.id; poly.title = a.event
                        m.addOverlay(poly, level: .aboveLabels)
                        warningRings.append((a.id, ring, a.level))
                    }
                }
            }

            if L.tracks {
                for b in c.bundles {
                    if let g = b.graphics {
                        for f in g.cone { for r in f.rings { let p = StyledPolygon(coordinates: r.map { CLLocationCoordinate2D(latitude: $0[1], longitude: $0[0]) }, count: r.count); p.fill = UIColor.white.withAlphaComponent(0.16); p.stroke = .white; p.width = 2; p.title = "NHC forecast cone — uncertainty in the center track only"; m.addOverlay(p, level: .aboveLabels) } }
                        for f in g.warnings {
                            let label = f.attrs.values.joined(separator: " ").uppercased()
                            let col: UIColor = label.contains("HURRICANE WARNING") || label.contains("HWRN") ? UIColor(red: 0.86, green: 0.08, blue: 0.24, alpha: 1)
                                : label.contains("HURRICANE WATCH") || label.contains("HWCH") ? UIColor(red: 1, green: 0.41, blue: 0.71, alpha: 1)
                                : label.contains("TROPICAL STORM WARNING") || label.contains("TWRN") ? UIColor(red: 0.12, green: 0.39, blue: 1, alpha: 1)
                                : label.contains("TROPICAL STORM WATCH") || label.contains("TWCH") ? UIColor(red: 1, green: 0.84, blue: 0, alpha: 1) : .gray
                            for pts in (f.line.isEmpty ? f.rings : [f.line]) { addLine(m, pts, col, 8) }
                        }
                        for f in g.past { for pts in (f.line.isEmpty ? f.rings : [f.line]) { addLine(m, pts, UIColor.gray, 3, dash: [8, 6]) } }
                        for f in g.track { for pts in (f.line.isEmpty ? f.rings : [f.line]) { addLine(m, pts, UIColor(red: 0.04, green: 0.16, blue: 0.29, alpha: 1), 5) } }
                    }
                    // Official forecast track from ATCF if the ArcGIS graphics are unavailable.
                    if (b.graphics?.track.isEmpty ?? true), let t = b.official { addLine(m, t.points.map { [$0.lon, $0.lat] }, UIColor(red: 0.04, green: 0.16, blue: 0.29, alpha: 1), 5) }
                    for p in b.official?.points ?? [] { m.addAnnotation(TWAnnotation(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), .forecastPoint, title: "NHC forecast +\(p.tauHours) h", subtitle: p.vmaxKt.map { "Max wind \(StormReportBuilder.ktToMph($0)) mph" })) }
                    if L.gfs, let t = b.gfs { addLine(m, t.points.map { [$0.lon, $0.lat] }, UIColor(red: 0.48, green: 0.12, blue: 0.64, alpha: 1), 3.5, dash: [10, 7], title: "GFS model guidance (not official)") }
                    if L.ecmwf, let t = b.ecmwf { addLine(m, t.points.map { [$0.lon, $0.lat] }, UIColor(red: 0, green: 0.59, blue: 0.53, alpha: 1), 3.5, dash: [3, 7], title: "ECMWF model guidance (not official)") }
                    m.addAnnotation(TWAnnotation(CLLocationCoordinate2D(latitude: b.storm.lat, longitude: b.storm.lon), .storm, title: "\(b.storm.classLabel) \(b.storm.name)", subtitle: "Tap for the storm report", stormId: b.storm.id))
                }
            }
            if L.buoys {
                let center = m.centerCoordinate
                for b in c.buoys.filter({ Geo.distanceMiles(center.latitude, center.longitude, $0.lat, $0.lon) < 600 }).prefix(120) {
                    let sub = [b.windMph.map { "Wind \(Int($0)) mph" }, b.gustMph.map { "gust \(Int($0))" }, b.waveHeightFt.map { String(format: "waves %.1f ft", $0) }, "reported \(Units.ago(b.timeMs))"].compactMap { $0 }.joined(separator: " · ")
                    m.addAnnotation(TWAnnotation(CLLocationCoordinate2D(latitude: b.lat, longitude: b.lon), .buoy, title: "NDBC buoy \(b.id)", subtitle: sub))
                }
            }
            for p in c.places { m.addAnnotation(TWAnnotation(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), p.isHome ? .home : .place, title: p.cityState, subtitle: p.isHome ? "Home" : "Saved place")) }
            if let pin = c.pin { m.addAnnotation(TWAnnotation(pin, .pin, title: "Dropped pin")) }
        }

        private func addLine(_ m: MKMapView, _ pts: [[Double]], _ color: UIColor, _ width: CGFloat, dash: [NSNumber]? = nil, title: String? = nil) {
            guard pts.count >= 2 else { return }
            let l = StyledPolyline(coordinates: pts.map { CLLocationCoordinate2D(latitude: $0[1], longitude: $0[0]) }, count: pts.count)
            l.color = color; l.width = width; l.dash = dash; l.title = title
            m.addOverlay(l, level: .aboveLabels)
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            switch overlay {
            case let t as MKTileOverlay:
                let r = MKTileOverlayRenderer(tileOverlay: t)
                if !(t is UsgsTileOverlay) { r.alpha = 0.78 }
                return r
            case let p as StyledPolygon:
                let r = MKPolygonRenderer(polygon: p); r.fillColor = p.fill; r.strokeColor = p.stroke; r.lineWidth = p.width; return r
            case let l as StyledPolyline:
                let r = MKPolylineRenderer(polyline: l); r.strokeColor = l.color; r.lineWidth = l.width; r.lineDashPattern = l.dash; r.lineCap = .round; return r
            default: return MKOverlayRenderer(overlay: overlay)
            }
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard let a = annotation as? TWAnnotation else { return nil }
            let v = MKMarkerAnnotationView(annotation: a, reuseIdentifier: "twm")
            v.canShowCallout = true
            switch a.kind {
            case .home: v.markerTintColor = UIColor(red: 0.04, green: 0.37, blue: 0.65, alpha: 1); v.glyphImage = UIImage(systemName: "house.fill")
            case .place: v.markerTintColor = UIColor(red: 0, green: 0.41, blue: 0.36, alpha: 1); v.glyphImage = UIImage(systemName: "mappin")
            case .storm: v.markerTintColor = UIColor(red: 0.84, green: 0, blue: 0, alpha: 1); v.glyphImage = UIImage(systemName: "hurricane"); v.displayPriority = .required
                v.rightCalloutAccessoryView = UIButton(type: .detailDisclosure)
            case .forecastPoint: v.markerTintColor = UIColor(red: 0.04, green: 0.16, blue: 0.29, alpha: 1); v.glyphText = "•"; v.displayPriority = .defaultLow
            case .buoy: v.markerTintColor = UIColor(red: 0, green: 0.47, blue: 0.42, alpha: 1); v.glyphImage = UIImage(systemName: "sensor.fill"); v.displayPriority = .defaultLow
            case .pin: v.markerTintColor = .systemOrange; v.glyphImage = UIImage(systemName: "mappin.and.ellipse")
            }
            return v
        }

        func mapView(_ mapView: MKMapView, annotationView view: MKAnnotationView, calloutAccessoryControlTapped control: UIControl) {
            if let a = view.annotation as? TWAnnotation, let id = a.stormId { parent.onOpenStorm(id) }
        }

        @objc func longPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let m = g.view as? MKMapView else { return }
            parent.onLongPress(m.convert(g.location(in: m), toCoordinateFrom: m))
        }

        /// Tap on a warned area opens the alert (warnings win over watches where they overlap).
        @objc func tap(_ g: UITapGestureRecognizer) {
            guard let m = g.view as? MKMapView else { return }
            let c = m.convert(g.location(in: m), toCoordinateFrom: m)
            let hits = warningRings.filter { Geo.inRing(c.latitude, c.longitude, $0.ring) }
            if let h = hits.min(by: { $0.level < $1.level }) { parent.onOpenAlert(h.id) }
        }
    }
}

struct MapTab: View {
    @EnvironmentObject var model: AppModel
    @State private var layers = MapLayers()
    @State private var showLayers = false
    @State private var pin: CLLocationCoordinate2D?
    @State private var satTimes: [String: Int64] = [:]
    @State private var buoys: [BuoyObs] = []
    @State private var bucket = Int(Date().timeIntervalSince1970 / 600)
    @State private var path: [Route] = []
    private let tick = Timer.publish(every: 600, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack(path: $path) {
            ZStack {
                MKMapRepresentable(
                    content: MapContent(layers: layers, alerts: model.mapAlerts, bundles: Array(model.bundles.values), buoys: buoys, places: model.places, pin: pin, bucket: bucket),
                    focus: model.mapFocus,
                    onLongPress: { pin = $0 },
                    onOpenAlert: { path.append(.alert($0)) },
                    onOpenStorm: { path.append(.storm($0)) },
                    onFocusConsumed: { model.mapFocus = nil })
                .ignoresSafeArea(edges: [.top, .horizontal])

                VStack { HStack { Spacer(); VStack(spacing: 8) {
                    Button { showLayers = true } label: { Image(systemName: "square.3.layers.3d").frame(width: 44, height: 44).background(.regularMaterial, in: Circle()) }.accessibilityLabel("Map layers")
                    Button { if let p = model.selected { model.mapFocus = MapFocus(lat: p.lat, lon: p.lon, spanDegrees: 4) } } label: { Image(systemName: "location.fill").frame(width: 44, height: 44).background(.regularMaterial, in: Circle()) }.accessibilityLabel("Center on selected place")
                }.padding(12) }; Spacer() }

                VStack { Spacer(); HStack { legend.padding(8); Spacer() } }
            }
            .navigationBarHidden(true)
            .routes()
        }
        .sheet(isPresented: $showLayers) { layersSheet.presentationDetents([.medium, .large]) }
        .alert("Dropped pin", isPresented: Binding(get: { pin != nil }, set: { if !$0 { pin = nil } })) {
            Button("Add place") { if let p = pin { Task { if let r = await model.addPinned(lat: p.latitude, lon: p.longitude) { model.toast("Added \(r.cityState)") } } }; pin = nil }
            Button("Cancel", role: .cancel) { pin = nil }
        } message: { if let p = pin { Text(String(format: "%.4f, %.4f\nAdd this spot as a saved place?", p.latitude, p.longitude)) } }
        .onAppear {
            let d = model.prefs
            layers = MapLayers(radar: d.mapRadar, warnings: d.mapWarnings, tracks: d.mapTracks, buoys: d.mapObservations, gfs: d.mapGfs, ecmwf: d.mapEcmwf, imagery: d.mapImagery, satellite: d.mapSatellite)
            model.loadStorms(); model.loadMapAlerts()
        }
        .task(id: layers.satellite) { if layers.satellite != "off" { satTimes = (try? await NowCoast.latestTimes()) ?? [:] } }
        .task(id: layers.buoys) { if layers.buoys { buoys = (try? await NdbcSource.all()) ?? [] } }
        .task(id: model.storms.storms?.value?.map(\.id).joined()) { for s in model.storms.storms?.value ?? [] { model.loadBundle(s) } }
        .onReceive(tick) { _ in bucket = Int(Date().timeIntervalSince1970 / 600) }
        .onChange(of: layers) { _, l in
            let d = model.prefs
            d.mapRadar = l.radar; d.mapWarnings = l.warnings; d.mapTracks = l.tracks; d.mapObservations = l.buoys; d.mapGfs = l.gfs; d.mapEcmwf = l.ecmwf; d.mapImagery = l.imagery; d.mapSatellite = l.satellite
        }
    }

    private var legend: some View {
        let band: NowCoast.Band = layers.satellite == "visible" ? .visible : layers.satellite == "watervapor" ? .waterVapor : .infrared
        return VStack(alignment: .leading, spacing: 2) {
            if layers.radar { Text("Radar: NOAA MRMS base reflectivity — refreshes ~every 10 min; scan time not published by the service.") }
            if layers.satellite != "off" { Text("Satellite (\(band.label)): NOAA GOES" + (satTimes[band.rawValue].map { " — latest image \(Units.stamp($0))" } ?? "")) }
            if layers.tracks && !model.bundles.isEmpty { Text("Solid dark line = official NHC forecast. Dashed lines = model guidance, not official.") }
            if layers.warnings { Text("Red = warnings, orange = watches (NWS). Tap a shaded area for detail.") }
            Text("Basemap: USGS The National Map · Data: NOAA/NWS/NHC. Not an endorsement by any agency. Long-press to drop a pin.").foregroundStyle(Tw.onSurfaceMuted)
        }.font(.caption2).padding(8).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10)).frame(maxWidth: 340, alignment: .leading)
    }

    private var layersSheet: some View {
        NavigationStack {
            Form {
                Section("Weather") {
                    Toggle(isOn: $layers.radar) { VStack(alignment: .leading) { Text("Radar"); Text("NOAA MRMS base reflectivity").font(.caption).foregroundStyle(Tw.onSurfaceMuted) } }
                    VStack(alignment: .leading) { Text("Satellite (NOAA GOES)").font(.subheadline)
                        Picker("Satellite", selection: $layers.satellite) { Text("Off").tag("off"); Text("Visible").tag("visible"); Text("Infrared").tag("infrared"); Text("Water vapor").tag("watervapor") }.pickerStyle(.segmented) }
                    Toggle(isOn: $layers.warnings) { VStack(alignment: .leading) { Text("NWS warnings & watches"); Text("Warned polygons; tap for detail").font(.caption).foregroundStyle(Tw.onSurfaceMuted) } }
                    Toggle(isOn: $layers.buoys) { VStack(alignment: .leading) { Text("Observed conditions"); Text("NOAA NDBC buoys").font(.caption).foregroundStyle(Tw.onSurfaceMuted) } }
                    Toggle(isOn: $layers.imagery) { VStack(alignment: .leading) { Text("Satellite-style basemap"); Text("USGS imagery instead of topographic").font(.caption).foregroundStyle(Tw.onSurfaceMuted) } }
                }
                Section("Hurricanes") {
                    Toggle(isOn: $layers.tracks) { VStack(alignment: .leading) { Text("Hurricane tracks"); Text("NHC official forecast, cone, watches/warnings — over any layer, including satellite").font(.caption).foregroundStyle(Tw.onSurfaceMuted) } }
                }
                Section {
                    Toggle(isOn: $layers.gfs) { VStack(alignment: .leading) { Text("GFS track"); Text("Dashed purple · NOAA model guidance").font(.caption).foregroundStyle(Tw.onSurfaceMuted) } }
                    Toggle(isOn: $layers.ecmwf) { VStack(alignment: .leading) { Text("ECMWF track"); Text("Dashed teal · European model guidance (when available)").font(.caption).foregroundStyle(Tw.onSurfaceMuted) } }
                } header: { Text("Model guidance (not the official forecast)") } footer: { Text("Models can disagree. Don't treat any model line as a forecast — the NHC track and cone are the official forecast.") }
            }
            .navigationTitle("Map layers").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showLayers = false } } }
        }
    }
}
