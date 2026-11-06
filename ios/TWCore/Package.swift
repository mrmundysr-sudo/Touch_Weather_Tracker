// swift-tools-version:5.9
import PackageDescription

// TWCore: platform-independent product logic shared by the iOS app, its widgets and tests.
// Mirrors the Android `source/`, `domain/` and `data/` packages. No Apple-only frameworks, so it also builds and tests on Linux.
let package = Package(
    name: "TWCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "TWCore", targets: ["TWCore"])],
    targets: [
        .target(name: "TWCore"),
        .testTarget(name: "TWCoreTests", dependencies: ["TWCore"]),
    ]
)
