package com.campusmarket.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.authResponse
import com.campusmarket.app.data.bodyText
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.OkResponse
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.UserPrivate
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Drives the real App() (navigation shell, DI graph, auth feature) against a fake server.
 * Catches runtime problems the compiler can't: missing Koin definitions, route serialization, nav wiring.
 */
@OptIn(ExperimentalTestApi::class)
class AppSmokeTest {

    private val server = FakeServer { req ->
        when (req.url.encodedPath) {
            ApiRoutes.REGISTER -> respondJson(RegisterResponse.serializer(), RegisterResponse("ada@uni.edu", devCode = "123456"))
            ApiRoutes.VERIFY_EMAIL ->
                // Like the real server: the code alone is not enough, the pending password must be sent too.
                if ("\"password\":\"password123\"" in req.bodyText) {
                    respondJson(AuthResponse.serializer(), authResponse("a1", "r1", System.currentTimeMillis() + HOUR))
                } else {
                    respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE, "Invalid code")
                }
            ApiRoutes.LOGIN -> respondError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "Wrong email or password")
            ApiRoutes.ME -> respondJson(UserPrivate.serializer(), testUser)
            ApiRoutes.LOGOUT -> respondJson(OkResponse.serializer(), OkResponse())
            else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun registerVerifyWithDevCodeSignsIn() = runComposeUiTest {
        startTestApp(server)
        setContent { App() }

        awaitText("Create account")
        click("Create account")
        awaitText("Join Quad")

        // Local validation first.
        click("Continue")
        awaitText("Enter your campus email")

        type("Campus email", "ada@uni.edu")
        type("Display name", "Ada")
        type("Password", "password123")
        click("Continue")

        awaitText("Check your email")
        awaitText("123456")
        click("Use code")

        // Verified → tokens stored → shell swaps to the signed-in tabs.
        awaitText("Inbox")
        awaitText("Browse")
    }

    @Test
    fun loginShowsServerErrorInline() = runComposeUiTest {
        startTestApp(server)
        setContent { App() }

        awaitText("Sign in")
        click("Sign in")
        awaitText("Welcome back")
        type("Campus email", "ada@uni.edu")
        type("Password", "wrongpass")
        onAllNodes(hasText("Sign in") and hasClickAction()).onFirst().performClick()
        awaitText("Wrong email or password")
    }

    /** Shell-level only (tabs, deep links, auth gate). Feature flows belong in each feature's own tests. */
    @Test
    fun signedInShellTabsDeepLinksAndSignOut() = runComposeUiTest {
        startTestApp(server, signedIn())
        setContent { App() }

        // Bottom bar on the start tab; every tab opens without crashing and keeps the bar.
        awaitTab("Browse")
        for (tab in listOf("Sell", "Inbox", "Profile", "Browse")) {
            click(tab)
            awaitTab(tab)
        }

        // Deep links navigate off the tab roots (bottom bar hides) and are consumed.
        DeepLinks.handle("campusmarket://orders/o-42")
        waitUntil(timeoutMillis = UI_TIMEOUT) { DeepLinks.pending.value == null }
        waitUntil(timeoutMillis = UI_TIMEOUT) { onAllNodes(hasText("Sell") and hasClickAction()).fetchSemanticsNodes().isEmpty() }
        DeepLinks.handle("campusmarket://payouts")
        waitUntil(timeoutMillis = UI_TIMEOUT) { DeepLinks.pending.value == null }
        waitForIdle()

        // Signing out (from anywhere) returns to the auth flow.
        runBlocking { GlobalContext.get().get<SessionManager>().signOut() }
        awaitText("Create account")
    }

    private fun ComposeUiTest.awaitTab(label: String) = awaitNode(hasText(label) and hasClickAction())
}
