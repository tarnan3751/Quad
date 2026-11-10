package com.campusmarket.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.SessionState
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Page
import com.campusmarket.core.Report
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.campusmarket.core.UserRole
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Profile → Settings (sign out / account deletion) and the moderation queue as a moderator, against a fake server. */
@OptIn(ExperimentalTestApi::class)
class ProfileModerationSmokeTest {

    private val farFuture = System.currentTimeMillis() + HOUR
    private val deleteCalls = mutableListOf<String>()

    private val reporter = UserPublic("r1", "Rita Reporter", campusDomain = "uni.edu", joinedAt = 1L)
    private val seller = UserPublic("s1", "Sam Seller", campusDomain = "uni.edu", joinedAt = 1L)
    private val report = Report(
        id = "rep1", reporter = reporter, targetType = ReportTargetType.LISTING, targetId = "l1",
        reason = ReportReason.SCAM, details = "Asked me to pay by gift card", status = ReportStatus.OPEN,
        targetSummary = "Mountain bike, barely used", targetUser = seller,
        targetListing = ListingSummary(
            "l1", "Mountain bike, barely used", 15000, category = ListingCategory.BIKES, condition = ListingCondition.GOOD,
            status = ListingStatus.ACTIVE, sellerId = "s1", sellerName = "Sam Seller", createdAt = 1L,
        ),
        openReportsAgainstTargetUser = 3, createdAt = System.currentTimeMillis() - 3 * HOUR,
    )

    private fun startApp(user: UserPrivate, deleteConflict: Boolean = false) {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = "http://test.local", platform = PlatformKind.DESKTOP)
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path == ApiRoutes.ME && req.method == HttpMethod.Delete -> {
                    deleteCalls += path
                    if (deleteConflict) respondError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, "Open orders")
                    else respondJson(com.campusmarket.core.OkResponse.serializer(), com.campusmarket.core.OkResponse())
                }
                path == ApiRoutes.ME -> respondJson(UserPrivate.serializer(), user)
                path == ApiRoutes.LOGOUT -> respondJson(com.campusmarket.core.OkResponse.serializer(), com.campusmarket.core.OkResponse())
                path == ApiRoutes.MOD_REPORTS -> respondJson(Page.serializer(Report.serializer()), Page(listOf(report)))
                path == ApiRoutes.modReport("rep1") -> respondJson(Report.serializer(), report)
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val tokens: TokenStore = InMemoryTokenStore(StoredSession("a1", "r1", farFuture, user))
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-smoke-test-c").also { it.clear() }) }
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

    private fun ComposeUiTest.awaitGone(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun ComposeUiTest.click(text: String, last: Boolean = false) {
        val nodes = onAllNodes(hasText(text) and hasClickAction())
        val node = if (last) nodes.onLast() else nodes.onFirst()
        try {
            node.performScrollTo()
        } catch (e: AssertionError) {
            // not inside a scrollable parent
        }
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
    fun profileShowsMeAndOpensSettingsAndEdit() = runComposeUiTest {
        startApp(testUser.copy(displayName = "Ada Lovelace", bio = "Maths and bikes"))
        setContent { App() }

        awaitText("Browse")
        click("Profile")
        awaitText("Ada Lovelace")
        awaitText("Maths and bikes")
        awaitText("My listings")
        awaitText("Orders")
        // Students never see the moderation entry.
        assertTrue(onAllNodesWithText("Moderation queue").fetchSemanticsNodes().isEmpty())

        click("Edit profile")
        awaitText("Display name")
        awaitText("Save changes")
        back()

        click("Settings")
        awaitText("Community guidelines")
        awaitText("Sign out")
        awaitText("Version")
        // Debug harness: the server override row is available.
        awaitText("API server")
    }

    @Test
    fun accountDeletionIsTwoStepAndSurfacesOpenOrders() = runComposeUiTest {
        startApp(testUser, deleteConflict = true)
        setContent { App() }

        awaitText("Browse")
        click("Profile")
        awaitText("Settings")
        click("Settings")
        awaitText("Delete account")
        click("Delete account")

        // Step 1: warning.
        awaitText("Delete your account?")
        click("Continue")
        // Step 2: typed confirmation; the destructive button stays disabled until the keyword matches.
        awaitText("Confirm deletion")
        assertTrue(deleteCalls.isEmpty())
        onAllNodes(hasSetTextAction() and hasText("Type DELETE")).onFirst().performTextInput("DELETE")
        waitForIdle()
        click("Delete account", last = true)

        awaitText("You have open orders")
        assertEquals(1, deleteCalls.size)
        assertTrue(GlobalContext.get().get<SessionManager>().state.value is SessionState.SignedIn)
    }

    @Test
    fun accountDeletionSuccessEndsTheSession() = runComposeUiTest {
        startApp(testUser)
        setContent { App() }

        awaitText("Browse")
        click("Profile")
        click("Settings")
        awaitText("Delete account")
        click("Delete account")
        awaitText("Delete your account?")
        click("Continue")
        awaitText("Confirm deletion")
        onAllNodes(hasSetTextAction() and hasText("Type DELETE")).onFirst().performTextInput("delete")
        waitForIdle()
        click("Delete account", last = true)

        awaitText("Create account") // back on the Welcome screen
        assertEquals(SessionState.SignedOut, GlobalContext.get().get<SessionManager>().state.value)
        // The confirmation survives the switch from the signed-in shell to the auth shell.
        awaitText("Your account has been deleted")
    }

    @Test
    fun moderatorSeesQueueAndReportDetail() = runComposeUiTest {
        startApp(testUser.copy(displayName = "Mo Derator", role = UserRole.MODERATOR))
        setContent { App() }

        awaitText("Browse")
        click("Profile")
        awaitText("Moderation queue")
        click("Moderation queue")

        awaitText("Open")
        awaitText("Resolved")
        awaitText("Dismissed")
        awaitText("Mountain bike, barely used")
        awaitText("Reported by Rita Reporter")
        awaitText("3 open")

        click("Mountain bike, barely used")
        awaitText("Asked me to pay by gift card")
        awaitText("3 open reports against this user")
        awaitText("Dismiss (no violation)")
        awaitText("Remove listing")
        awaitText("Warn user")
        awaitText("Suspend user")

        // Actions are confirmed first.
        click("Warn user")
        awaitText("Warn Sam Seller?")
        click("Cancel")
        awaitGone("Warn Sam Seller?")
    }
}
