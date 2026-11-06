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

    /// Waits for `el`; on failure saves a screenshot of what IS on screen (named `FAIL-<name>`) before failing the test.
    private func require(_ el: XCUIElement, _ timeout: TimeInterval, _ name: String, _ message: String) {
        if !el.waitForExistence(timeout: timeout) { shot("FAIL-\(name)"); XCTFail(message); fatalError("stopping: \(message)") }
    }

    private func button(containing text: String) -> XCUIElement {
        app.buttons.containing(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }

    func testOnboardingThenEveryTab() throws {
        app.launch()
        require(app.buttons["Get started"], 30, "welcome", "welcome screen did not appear")
        shot("00-welcome")
        app.buttons["Get started"].tap()

        let field = app.textFields["searchField"]
        require(field, 15, "search-field", "search field missing")
        field.tap(); field.typeText("05602")
        let result = button(containing: "ZIP 05602")
        require(result, 40, "zip-results", "ZIP search returned no result (network?)")
        shot("01-search-results")
        result.tap()

        let notNow = app.buttons["Not now"]
        require(notNow, 40, "notification-step", "notification step missing")
        shot("02-notifications-step")
        notNow.tap()
        let show = app.buttons["Show my weather"]
        require(show, 15, "ready-step", "ready step missing")
        shot("03-ready")
        show.tap()

        // Today: wait for real data
        require(app.staticTexts["Next 24 hours"], 90, "today", "Today never showed the hourly forecast")
        shot("10-today")
        app.swipeUp(); shot("11-today-scrolled")
        app.swipeUp(); shot("12-today-bottom")

        for (name, file) in [("Map", "20-map"), ("Alerts", "30-alerts"), ("Storms", "40-storms"), ("Resources", "50-resources")] {
            let tab = app.tabBars.buttons[name]
            require(tab, 10, "tab-\(name)", "tab \(name) missing")
            tab.tap()
            sleep(name == "Map" ? 14 : 12)
            shot(file)
            if name == "Alerts" {
                let all = app.buttons["All active"]
                if all.exists { all.tap(); sleep(10); shot("31-alerts-all-active") }
            }
            if name == "Storms" {
                let open = button(containing: "Open the storm report")
                if open.waitForExistence(timeout: 5) { open.tap(); sleep(30); shot("41-storm-report"); app.swipeUp(); shot("42-storm-report-2"); app.swipeUp(); shot("43-storm-report-3"); app.navigationBars.buttons.firstMatch.tap(); sleep(1) }
            }
            if name == "Resources" {
                app.swipeUp(); shot("51-resources-lower"); app.swipeUp(); shot("52-resources-bottom")
                app.swipeDown(); app.swipeDown(); app.swipeDown()
                let power = button(containing: "electric utility")
                if power.waitForExistence(timeout: 5) { power.tap(); sleep(20); shot("53-power"); app.swipeUp(); shot("54-power-2"); app.navigationBars.buttons.firstMatch.tap() }
            }
        }

        app.tabBars.buttons["Today"].tap()
        sleep(2)
        app.swipeDown(); app.swipeDown(); app.swipeDown()
        let manage = app.buttons["Manage places"]
        if manage.waitForExistence(timeout: 10) { manage.tap(); sleep(2); shot("60-places"); app.navigationBars.buttons.firstMatch.tap(); sleep(1) }
        let settings = app.buttons["Settings"]
        if settings.waitForExistence(timeout: 10) { settings.tap(); sleep(2); shot("61-settings") }
    }
}
