import XCTest

/// Drives the REAL app in the iOS Simulator (real onboarding, real network): adds Home by ZIP, then visits every tab and a few screens, saving screenshots.
/// Runs only in CI. It verifies that the app launches, onboarding works and the tabs render without crashing; it does not judge visual quality.
final class TouchWeatherUITests: XCTestCase {
    private var app: XCUIApplication!
    private let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] ?? "/tmp/twc-shots"

    override func setUpWithError() throws {
        continueAfterFailure = false
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        app = XCUIApplication()
    }

    private func shot(_ name: String) {
        let s = XCUIScreen.main.screenshot()
        try? s.pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/\(name).png"))
        let a = XCTAttachment(screenshot: s); a.name = name; a.lifetime = .keepAlways; add(a)
    }

    private func button(containing text: String) -> XCUIElement {
        app.buttons.containing(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }

    func testOnboardingThenEveryTab() throws {
        app.launch()
        XCTAssertTrue(app.buttons["Get started"].waitForExistence(timeout: 30), "welcome screen did not appear")
        shot("00-welcome")
        app.buttons["Get started"].tap()

        let field = app.textFields["searchField"]
        XCTAssertTrue(field.waitForExistence(timeout: 15), "search field missing")
        field.tap(); field.typeText("05602")
        let result = button(containing: "ZIP 05602")
        XCTAssertTrue(result.waitForExistence(timeout: 40), "ZIP search returned no result (network?)")
        shot("01-search-results")
        result.tap()

        let notNow = app.buttons["Not now"]
        XCTAssertTrue(notNow.waitForExistence(timeout: 40), "notification step missing")
        shot("02-notifications-step")
        notNow.tap()
        let show = app.buttons["Show my weather"]
        XCTAssertTrue(show.waitForExistence(timeout: 15), "ready step missing")
        shot("03-ready")
        show.tap()

        // Today: wait for real data
        XCTAssertTrue(app.staticTexts["Next 24 hours"].waitForExistence(timeout: 90), "Today never showed the hourly forecast")
        shot("10-today")
        app.swipeUp(); shot("11-today-scrolled")
        app.swipeUp(); shot("12-today-bottom")

        for (name, file) in [("Map", "20-map"), ("Alerts", "30-alerts"), ("Storms", "40-storms"), ("Resources", "50-resources")] {
            let tab = app.tabBars.buttons[name]
            XCTAssertTrue(tab.waitForExistence(timeout: 10), "tab \(name) missing")
            tab.tap()
            sleep(name == "Map" ? 14 : 12)
            shot(file)
            if name == "Resources" { app.swipeUp(); shot("51-resources-lower"); app.swipeUp(); shot("52-resources-bottom") }
        }

        app.tabBars.buttons["Today"].tap()
        let manage = app.buttons["Manage places"]
        if manage.waitForExistence(timeout: 10) { manage.tap(); sleep(2); shot("60-places") }
    }
}
