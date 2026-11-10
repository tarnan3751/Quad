package com.campusmarket.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.authResponse
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.OkResponse
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.UserPrivate
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import com.campusmarket.app.data.SessionManager
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Drives the real App() (navigation shell, DI graph, auth feature) against a fake server.
 * Catches runtime problems the compiler can't: missing Koin definitions, route serialization, nav wiring.
 */
@OptIn(ExperimentalTestApi::class)
class AppSmokeTest {

    private val farFuture = System.currentTimeMillis() + HOUR

    private fun startApp(tokens: TokenStore) {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = "http://test.local", platform = PlatformKind.DESKTOP)
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                ApiRoutes.REGISTER -> respondJson(RegisterResponse.serializer(), RegisterResponse("ada@uni.edu", devCode = "123456"))
                ApiRoutes.VERIFY_EMAIL ->
                    // Like the real server: the code alone is not enough, the pending password must be sent too.
                    if ("\"password\":\"password123\"" in req.body.toByteArray().decodeToString()) {
                        respondJson(AuthResponse.serializer(), authResponse("a1", "r1", farFuture))
                    } else {
                        respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE, "Invalid code")
                    }
                ApiRoutes.LOGIN -> respondError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "Wrong email or password")
                ApiRoutes.ME -> respondJson(UserPrivate.serializer(), testUser)
                ApiRoutes.LOGOUT -> respondJson(OkResponse.serializer(), OkResponse())
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-smoke-test").also { it.clear() }) }
                        single<TokenStore> { tokens }
                        single<HttpClient> { HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) } }
                    },
                ) + featureModules,
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.click(text: String) {
        val node = onAllNodes(hasText(text) and hasClickAction()).onFirst()
        try {
            node.performScrollTo() // only works inside a scrollable parent
        } catch (e: AssertionError) {
            // not scrollable: already on screen
        }
        // Inject on the UI (AWT) thread: navigation updates lifecycles, which must happen on the main thread.
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.back() {
        waitForIdle()
        val node = onAllNodesWithContentDescription("Back").onFirst()
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    @Test
    fun registerVerifyWithDevCodeSignsIn() = runComposeUiTest {
        startApp(InMemoryTokenStore())
        setContent { App() }

        awaitText("Create account")
        click("Create account")
        awaitText("Join Quad")

        // Local validation first.
        click("Continue")
        awaitText("Enter your campus email")

        onAllNodes(hasSetTextAction() and hasText("Campus email")).onFirst().performTextInput("ada@uni.edu")
        onAllNodes(hasSetTextAction() and hasText("Display name")).onFirst().performTextInput("Ada")
        onAllNodes(hasSetTextAction() and hasText("Password")).onFirst().performTextInput("password123")
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
        startApp(InMemoryTokenStore())
        setContent { App() }

        awaitText("Sign in")
        click("Sign in")
        awaitText("Welcome back")
        onAllNodes(hasSetTextAction() and hasText("Campus email")).onFirst().performTextInput("ada@uni.edu")
        onAllNodes(hasSetTextAction() and hasText("Password")).onFirst().performTextInput("wrongpass")
        onAllNodes(hasText("Sign in") and hasClickAction()).onFirst().performClick()
        awaitText("Wrong email or password")
    }

    /**
     * Shell-level only (tabs, deep links, auth gate), so it keeps passing while features replace their placeholders.
     * Feature flows belong in each feature's own tests.
     */
    @Test
    fun signedInShellTabsDeepLinksAndSignOut() = runComposeUiTest {
        startApp(InMemoryTokenStore(StoredSession("a1", "r1", farFuture, testUser)))
        setContent { App() }

        // Bottom bar on the start tab; every tab opens without crashing and keeps the bar.
        awaitTab("Browse")
        for (tab in listOf("Sell", "Inbox", "Profile", "Browse")) {
            click(tab)
            awaitTab(tab)
        }

        // Deep links navigate off the tab roots (bottom bar hides) and are consumed.
        DeepLinks.handle("campusmarket://orders/o-42")
        waitUntil(timeoutMillis = 5_000) { DeepLinks.pending.value == null }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Sell") and hasClickAction()).fetchSemanticsNodes().isEmpty() }
        DeepLinks.handle("campusmarket://payouts")
        waitUntil(timeoutMillis = 5_000) { DeepLinks.pending.value == null }
        waitForIdle()

        // Signing out (from anywhere) returns to the auth flow.
        runBlocking { GlobalContext.get().get<SessionManager>().signOut() }
        awaitText("Create account")
    }

    private fun ComposeUiTest.awaitTab(label: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
    }
}
