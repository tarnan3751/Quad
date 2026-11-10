import XCTest

/**
 * End-to-end flows on the simulator against a running dev server (`scripts/dev.sh`, seeded demo data) at
 * http://localhost:8080. Compose Multiplatform exposes its semantics as accessibility elements, so buttons and fields
 * are found by their labels (text fields are `textViews`). Run with
 *
 *     xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
 *         -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:iosAppUITests/QuadFlowTests
 *
 * Build with code signing on (the default for the simulator): an unsigned app has no Keychain access, so sessions
 * wouldn't survive a relaunch.
 */
final class QuadFlowTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launch()
        signOutIfSignedIn()
    }

    func testWelcomeOffersSignUpAndSignIn() {
        XCTAssertTrue(app.buttons["Create account"].exists)
        XCTAssertTrue(app.buttons["Sign in"].exists)
    }

    func testWrongPasswordIsRejected() {
        app.buttons["Sign in"].tap()
        fill("Campus email", "liam@state.edu")
        app.keyboards.buttons["Next:"].tap()
        app.typeText("not-the-password")
        app.buttons["Sign in"].tap()
        let error = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Incorrect email or password"))
        XCTAssertTrue(error.firstMatch.waitForExistence(timeout: 10))
    }

    func testSessionSurvivesRelaunchUntilSignOut() {
        signIn("liam@state.edu")
        XCTAssertTrue(app.otherElements["Browse"].waitForExistence(timeout: 15), "signed in to Browse")

        app.terminate()
        app.launch()
        XCTAssertTrue(app.otherElements["Browse"].waitForExistence(timeout: 15), "session restored from the Keychain")

        signOut()
        XCTAssertTrue(app.buttons["Create account"].waitForExistence(timeout: 10))
    }

    // MARK: - helpers

    private func fill(_ field: String, _ text: String) {
        let element = app.textViews[field]
        XCTAssertTrue(element.waitForExistence(timeout: 10), "field \(field)")
        element.tap()
        app.typeText(text)
    }

    private func signIn(_ email: String, password: String = "password123") {
        app.buttons["Sign in"].tap()
        fill("Campus email", email)
        app.keyboards.buttons["Next:"].tap()
        app.typeText(password + "\n")
    }

    private func signOutIfSignedIn() {
        let welcome = app.buttons["Create account"]
        let profileTab = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Profile")).firstMatch
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if welcome.exists { return }
            if profileTab.exists {
                signOut()
                XCTAssertTrue(welcome.waitForExistence(timeout: 10))
                return
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTFail("neither Welcome nor the signed-in shell appeared")
    }

    private func signOut() {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Profile")).firstMatch.tap()
        let signOut = app.buttons["Sign out"]
        for _ in 0..<6 where !(signOut.exists && signOut.isHittable) {
            app.swipeUp()
        }
        // A tap during the fling would only stop it.
        Thread.sleep(forTimeInterval: 1.5)
        signOut.tap()
        // The confirmation dialog hides the screen behind it, so this is its button.
        let prompt = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "You'll need your campus email"))
        XCTAssertTrue(prompt.firstMatch.waitForExistence(timeout: 5))
        app.buttons["Sign out"].tap()
    }
}
