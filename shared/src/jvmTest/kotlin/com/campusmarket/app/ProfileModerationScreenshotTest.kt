package com.campusmarket.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.toStoredSession
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.feature.browse.browseGraph
import com.campusmarket.app.feature.chat.chatGraph
import com.campusmarket.app.feature.listing.listingGraph
import com.campusmarket.app.feature.moderation.DisputeDetailRoute
import com.campusmarket.app.feature.moderation.DisputesRoute
import com.campusmarket.app.feature.moderation.ModerationLogRoute
import com.campusmarket.app.feature.moderation.ModerationQueueRoute
import com.campusmarket.app.feature.moderation.ReportDetailRoute
import com.campusmarket.app.feature.moderation.StaffRoute
import com.campusmarket.app.feature.moderation.moderationGraph
import com.campusmarket.app.feature.orders.ordersGraph
import com.campusmarket.app.feature.profile.BlockedUsersRoute
import com.campusmarket.app.feature.profile.EditProfileRoute
import com.campusmarket.app.feature.profile.ProfileRoute
import com.campusmarket.app.feature.profile.SettingsRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.feature.profile.profileGraph
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.platform.createPlatformHttpClient
import com.campusmarket.app.ui.components.LocalUrlResolver
import com.campusmarket.app.ui.theme.CampusMarketTheme
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.LoginRequest
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.UserPrivate
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Phone-sized screenshots of every profile and moderation screen, light and dark, against the running dev server with
 * the seeded demo data. Read-only: it signs in, navigates and opens dialogs/sheets, but never confirms one. Opt-in:
 * ```
 * CAMPUS_MARKET_LIVE=http://localhost:8080 CAMPUS_MARKET_SCREENSHOTS=/some/dir \
 *   ./gradlew :shared:jvmTest --tests '*ProfileModerationScreenshotTest*'
 * ```
 * `CAMPUS_MARKET_FONT_SCALE=1.3` renders with larger text.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ProfileModerationScreenshotTest {
    private val base: String? = System.getenv("CAMPUS_MARKET_LIVE")?.trimEnd('/')?.takeIf { it.isNotBlank() }
    private val dir: File? = System.getenv("CAMPUS_MARKET_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File)
    private val fontScale: Float = System.getenv("CAMPUS_MARKET_FONT_SCALE")?.toFloatOrNull() ?: 1f

    private val env get() = AppEnvironment(isDebug = true, defaultBaseUrl = base!!, platform = PlatformKind.DESKTOP)

    private fun api(session: AuthResponse? = null) = ApiClient(
        createPlatformHttpClient { installCampusMarketDefaults(debugLogging = false) },
        ServerConfig(env),
        session?.let { InMemoryTokenStore(it.toStoredSession()) } ?: InMemoryTokenStore(),
    )

    private fun session(email: String): AuthResponse = sessions.getOrPut(email) {
        runBlocking { api().login(LoginRequest(email, "password123")) }
    }

    private fun startApp(session: AuthResponse) {
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-profile-shots").also { it.clear() }) }
                        single<TokenStore> { InMemoryTokenStore(session.toStoredSession()) }
                    },
                ) + featureModules,
            )
        }
    }

    private lateinit var nav: NavHostController

    /** The signed-in NavHost without the tab bar, so a test can open any destination directly. */
    @Composable
    private fun Harness(user: UserPrivate, start: Any) {
        val server: ServerConfig = koinInject()
        val resolver = remember(server) { { url: String? -> server.resolve(url) } }
        CampusMarketTheme {
            CompositionLocalProvider(LocalUrlResolver provides resolver, LocalCurrentUser provides user) {
                Surface(Modifier.fillMaxSize()) {
                    val controller = rememberNavController()
                    nav = controller
                    NavHost(controller, startDestination = start) {
                        browseGraph(controller)
                        listingGraph(controller)
                        chatGraph(controller)
                        ordersGraph(controller)
                        profileGraph(controller)
                        moderationGraph(controller)
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.clickNode(matcher: SemanticsMatcher, last: Boolean = false) {
        waitUntil(timeoutMillis = 20_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = if (last) onAllNodes(matcher).onLast() else onAllNodes(matcher).onFirst()
        runCatching { node.performScrollTo() }
        runOnUiThread { node.performClick() }
        waitForIdle()
    }

    private fun ComposeUiTest.click(text: String, substring: Boolean = false, last: Boolean = false) =
        clickNode(hasText(text, substring = substring) and hasClickAction(), last)

    private fun ComposeUiTest.go(route: Any) {
        runOnUiThread { nav.navigate(route) }
        waitForIdle()
    }

    private fun ComposeUiTest.back() {
        runOnUiThread { nav.popBackStack() }
        waitForIdle()
    }

    private fun ComposeUiTest.scrollDown(times: Int = 1) {
        repeat(times) {
            onRoot().performTouchInput { swipeUp() }
            waitForIdle()
        }
    }

    private var theme = "light"

    private fun SkikoComposeUiTest.shot(name: String) {
        repeat(25) {
            Thread.sleep(100)
            waitForIdle()
        }
        dir!!.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$theme-$name.png"))
    }

    private fun run(dark: Boolean, email: String, start: Any, block: SkikoComposeUiTest.(UserPrivate) -> Unit) {
        val session = session(email)
        startApp(session)
        theme = if (dark) "dark" else "light"
        try {
            runSkikoComposeUiTest(size = Size(412f * 2, 892f * 2), density = Density(2f, fontScale)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                        Harness(session.user, start)
                    }
                }
                try {
                    block(session.user)
                } catch (e: Throwable) {
                    runCatching { shot("failure-${email.substringBefore('@')}") }
                    throw e
                }
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun profileAndModerationScreenshots() {
        if (base == null || dir == null) {
            println("ProfileModerationScreenshotTest skipped: set CAMPUS_MARKET_LIVE and CAMPUS_MARKET_SCREENSHOTS")
            return
        }
        val buyer = "liam@state.edu"
        val seller = runBlocking { api(session("maya@state.edu")).me() }
        val noRatings = runBlocking { api(session("priya@state.edu")).me() }
        val modApi = api(session("moderator@state.edu"))
        val openReports = runBlocking { modApi.modReports(ReportStatus.OPEN).items }
        val resolved = runBlocking { modApi.modReports(ReportStatus.RESOLVED).items + modApi.modReports(ReportStatus.DISMISSED).items }
        val openDisputes = runBlocking { modApi.modDisputes(DisputeStatus.OPEN).items }
        val closedDisputes = runBlocking { modApi.modDisputes(DisputeStatus.CLOSED).items }

        for (dark in listOf(false, true)) {
            run(dark, buyer, ProfileRoute) { me ->
                awaitText("My listings")
                shot("p01-profile")
                scrollDown(2)
                shot("p02-profile-bottom")
                go(SettingsRoute)
                awaitText("Sign out")
                shot("p03-settings")
                scrollDown(2)
                shot("p04-settings-bottom")
                back()
                go(EditProfileRoute)
                awaitText("Display name")
                shot("p05-edit-profile")
                back()
                go(BlockedUsersRoute)
                waitForIdle()
                shot("p06-blocked")
                back()
                go(UserProfileRoute(me.id))
                awaitText(me.displayName)
                shot("p07-own-public")
                go(UserProfileRoute(seller.id))
                awaitText(seller.bio.take(12))
                shot("p08-other-public")
                runCatching { click("Ratings", substring = true) }
                shot("p09-other-ratings")
                runCatching { clickNode(hasContentDescription("More options") and hasClickAction()) }
                shot("p10-other-menu")
                runCatching { click("Block user") }
                shot("p11-block-dialog")
                runCatching { click("Cancel") }
                runCatching { clickNode(hasContentDescription("More options") and hasClickAction()) }
                runCatching { click("Report user", substring = true) }
                shot("p12-report-sheet")
                runCatching { click("Spam") }
                shot("p13-report-sheet-reason")
                back()
                go(UserProfileRoute(noRatings.id))
                awaitText(noRatings.displayName)
                runCatching { click("Ratings", substring = true) }
                shot("p14-no-ratings")
            }
            run(dark, "moderator@state.edu", ProfileRoute) {
                awaitText("Moderation")
                scrollDown(2)
                shot("m00-profile-moderator")
                go(ModerationQueueRoute)
                waitForIdle()
                shot("m01-queue-open")
                runCatching { click("Resolved") }
                shot("m02-queue-resolved")
                runCatching { click("Dismissed") }
                shot("m03-queue-dismissed")
                openReports.forEachIndexed { i, r ->
                    go(ReportDetailRoute(r.id))
                    awaitText(r.reporter.displayName)
                    shot("m1$i-report-${r.targetType.name.lowercase()}")
                    scrollDown(3)
                    shot("m1$i-report-${r.targetType.name.lowercase()}-bottom")
                    back()
                }
                resolved.firstOrNull()?.let { r ->
                    go(ReportDetailRoute(r.id))
                    awaitText(r.reporter.displayName)
                    shot("m20-report-closed")
                    scrollDown(3)
                    shot("m21-report-closed-bottom")
                    back()
                }
                openReports.firstOrNull { it.targetUser != null }?.let { r ->
                    go(ReportDetailRoute(r.id))
                    awaitText(r.reporter.displayName)
                    runCatching { click("Suspend user", last = true) }
                    shot("m22-suspend-dialog")
                    back()
                }
                go(DisputesRoute)
                waitForIdle()
                shot("m30-disputes-open")
                runCatching { click("Closed") }
                shot("m31-disputes-closed")
                openDisputes.firstOrNull()?.let { c ->
                    go(DisputeDetailRoute(c.orderId))
                    awaitText(c.listing.title)
                    shot("m32-dispute-open")
                    scrollDown(3)
                    shot("m33-dispute-open-bottom")
                    runCatching { click("Refund buyer", last = true) }
                    shot("m34-dispute-refund-dialog")
                    back()
                }
                closedDisputes.firstOrNull()?.let { c ->
                    go(DisputeDetailRoute(c.orderId))
                    awaitText(c.listing.title)
                    scrollDown(3)
                    shot("m35-dispute-closed-bottom")
                    back()
                }
                go(ModerationLogRoute)
                waitForIdle()
                shot("m40-log")
            }
            run(dark, "admin@state.edu", ModerationQueueRoute) {
                waitForIdle()
                shot("a01-queue-admin")
                go(StaffRoute)
                awaitText("Morgan")
                shot("a02-staff")
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("state")
                onAllNodes(hasSetTextAction()).onFirst().performImeAction()
                awaitText("Priya")
                shot("a03-staff-search")
                runCatching { click("Promote", substring = true) }
                shot("a04-staff-promote-dialog")
            }
        }
    }

    companion object {
        private val sessions = mutableMapOf<String, AuthResponse>()
    }
}
