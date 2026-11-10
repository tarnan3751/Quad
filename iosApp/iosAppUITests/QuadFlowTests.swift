import XCTest

/**
 * End-to-end flows on the simulator against a running dev server (`scripts/dev.sh`, seeded demo data) at
 * http://localhost:8080. Compose Multiplatform exposes its semantics as accessibility elements, so buttons and fields
 * are found by their labels.
 */
final class QuadFlowTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
    }

    func testWelcomeOffersSignUpAndSignIn() {
        app.launch()
        XCTAssertTrue(app.buttons["Create account"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Sign in"].exists)
    }
}
