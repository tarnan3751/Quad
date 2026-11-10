package com.campusmarket.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.toStoredSession
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.platform.createPlatformHttpClient
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.LoginRequest
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Phone-sized screenshots of the main screens in light AND dark, against a running dev server with the seeded demo
 * data, for reviewing the Quad / Golden Hour look. Read-only (it only signs in and navigates). Opt-in:
 * ```
 * CAMPUS_MARKET_LIVE=http://localhost:8080 CAMPUS_MARKET_SCREENSHOTS=/some/dir \
 *   ./gradlew :shared:jvmTest --tests '*BrandScreenshotTest*'
 * ```
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class BrandScreenshotTest {
    private val base: String? = System.getenv("CAMPUS_MARKET_LIVE")?.trimEnd('/')?.takeIf { it.isNotBlank() }
    private val dir: File? = System.getenv("CAMPUS_MARKET_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File)

    private val env get() = AppEnvironment(isDebug = true, defaultBaseUrl = base!!, platform = PlatformKind.DESKTOP)

    private fun api(session: AuthResponse? = null) = ApiClient(
        createPlatformHttpClient { installCampusMarketDefaults(debugLogging = false) },
        ServerConfig(env),
        session?.let { InMemoryTokenStore(it.toStoredSession()) } ?: InMemoryTokenStore(),
    )

    /** Logins are cached per JVM: the server rate-limits them. */
    private fun session(email: String): AuthResponse = sessions.getOrPut(email) {
        runBlocking { api().login(LoginRequest(email, "password123")) }
    }

    private fun startApp(session: AuthResponse?) {
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-brand-shots").also { it.clear() }) }
                        single<TokenStore> { session?.let { InMemoryTokenStore(it.toStoredSession()) } ?: InMemoryTokenStore() }
                    },
                ) + featureModules,
            )
        }
    }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.clickNode(matcher: SemanticsMatcher) {
        waitUntil(timeoutMillis = 20_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.click(text: String, substring: Boolean = false) =
        clickNode(hasText(text, substring = substring) and hasClickAction())

    private fun SkikoComposeUiTest.shot(name: String) {
        repeat(25) {
            Thread.sleep(100)
            waitForIdle()
        }
        dir!!.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    /** Runs [block] with the real App() in light or dark, signed in as [email] (or signed out). */
    private fun app(dark: Boolean, email: String?, block: SkikoComposeUiTest.() -> Unit) {
        startApp(email?.let(::session))
        try {
            runSkikoComposeUiTest(size = Size(412f * 2, 892f * 2), density = Density(2f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) { App() }
                }
                try {
                    block()
                } catch (e: Throwable) {
                    runCatching { shot("${if (dark) "dark" else "light"}-failure") }
                    throw e
                }
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun brandScreenshots() {
        if (base == null || dir == null) {
            println("BrandScreenshotTest skipped: set CAMPUS_MARKET_LIVE and CAMPUS_MARKET_SCREENSHOTS")
            return
        }
        val buyer = "liam@state.edu"
        val conversation = runBlocking { api(session(buyer)).conversations().items.first() }
        // An order still waiting for the handoff shows the most UI; fall back to any order.
        val orderOwner = "sofia@state.edu"
        val order = runBlocking {
            val orders = api(session(orderOwner)).orders(OrderRole.BUYER).items
            orders.firstOrNull { it.status == OrderStatus.PAID } ?: orders.first()
        }

        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            app(dark, email = null) {
                awaitText("Create account")
                shot("$t-01-welcome")
                click("Sign in")
                awaitText("Welcome back")
                shot("$t-02-login")
            }
            app(dark, buyer) {
                awaitText("Campbell Biology")
                shot("$t-03-browse")
                clickNode(hasContentDescription("Filters", substring = true) and hasClickAction())
                awaitText("Show results")
                click("Like new")
                shot("$t-04-filters")
                click("Show results")
                click("Clear all")
                awaitText("Campbell Biology")
                // An empty state (its call to action is a primary button).
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("zzqxv")
                onAllNodes(hasSetTextAction()).onFirst().performImeAction()
                awaitText("No matches")
                shot("$t-04b-empty-search")
                click("Clear search and filters")
                awaitText("Campbell Biology")
                click("Campbell Biology", substring = true)
                awaitText("Message seller")
                shot("$t-05-listing-detail")
                clickNode(hasContentDescription("Back") and hasClickAction())
                click("Sell")
                awaitText("Post listing")
                shot("$t-06-sell")
                click("Post listing")
                awaitText("Add at least one photo")
                shot("$t-07-sell-errors")
                click("Inbox")
                awaitText(conversation.otherUser.displayName)
                shot("$t-08-inbox")
                click(conversation.otherUser.displayName)
                waitForIdle()
                shot("$t-09-conversation")
                clickNode(hasContentDescription("Back") and hasClickAction())
                click("Profile")
                awaitText("My listings")
                shot("$t-10-profile")
                click("Settings")
                awaitText("Sign out")
                shot("$t-11-settings")
            }
            app(dark, orderOwner) {
                awaitText("Campbell Biology")
                DeepLinks.handle("campusmarket://orders/${order.id}")
                awaitText(order.listing.title)
                shot("$t-12-order-detail")
            }
            app(dark, "moderator@state.edu") {
                awaitText("Browse")
                click("Profile")
                click("Reports")
                waitForIdle()
                shot("$t-13-moderation-queue")
            }
        }
    }

    companion object {
        private val sessions = mutableMapOf<String, AuthResponse>()
    }
}
