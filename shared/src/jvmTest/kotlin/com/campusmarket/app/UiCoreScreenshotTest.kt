package com.campusmarket.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.data.toStoredSession
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.platform.createPlatformHttpClient
import com.campusmarket.app.ui.components.AppSnackbar
import com.campusmarket.app.ui.components.AppSnackbarVisuals
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.CountBadge
import com.campusmarket.app.ui.components.DestructiveButton
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ErrorState
import com.campusmarket.app.ui.components.InfoBanner
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.ListRowSkeleton
import com.campusmarket.app.ui.components.ListingCardSkeleton
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.QuadIcons
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.SkeletonLeading
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.theme.CampusMarketTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.LoginRequest
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Phone-sized screenshots of the app shell and auth screens (light and dark, plus 1.3× font scale), and a gallery
 * of the shared components, for reviewing the UI. Opt-in:
 * ```
 * CAMPUS_MARKET_SCREENSHOTS=/some/dir [CAMPUS_MARKET_LIVE=http://127.0.0.1:8080] \
 *   ./gradlew :shared:jvmTest --tests '*UiCoreScreenshotTest*'
 * ```
 * The auth screens and the loading / error states use a fake server; the tab roots use the live dev server
 * (read-only: it only signs in and navigates) when `CAMPUS_MARKET_LIVE` is set.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class UiCoreScreenshotTest {
    private val live: String? = System.getenv("CAMPUS_MARKET_LIVE")?.trimEnd('/')?.takeIf { it.isNotBlank() }
    private val dir: File? = System.getenv("CAMPUS_MARKET_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File)

    private val farFuture = System.currentTimeMillis() + HOUR

    private enum class FakeBehaviour { Normal, Suspended, Hang, Offline }

    private val unreadConversation = Conversation(
        id = "c1",
        listing = ListingSummary(
            id = "l1", title = "Desk lamp", priceCents = 1200, category = ListingCategory.FURNITURE,
            condition = ListingCondition.GOOD, status = ListingStatus.ACTIVE, sellerId = "u2", sellerName = "Maya", createdAt = 0,
        ),
        otherUser = UserPublic(id = "u2", displayName = "Maya Patel", campusDomain = "uni.edu", joinedAt = 0),
        lastMessagePreview = "Still available?",
        unreadCount = 3,
        createdAt = 0,
    )

    private fun fakeEngine(behaviour: FakeBehaviour) = MockEngine { req ->
        when (req.url.encodedPath) {
            ApiRoutes.REGISTER -> respondJson(RegisterResponse.serializer(), RegisterResponse("ada@uni.edu", devCode = "123456"))
            ApiRoutes.RESEND_CODE -> respondJson(RegisterResponse.serializer(), RegisterResponse("ada@uni.edu", devCode = "123456"))
            ApiRoutes.VERIFY_EMAIL -> respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE, "That code isn't right. Check it and try again.")
            ApiRoutes.LOGIN -> respondError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "Wrong email or password")
            ApiRoutes.ME -> when (behaviour) {
                FakeBehaviour.Suspended -> respondError(HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED, "Your account is suspended: Violation of community guidelines")
                else -> respondJson(UserPrivate.serializer(), testUser)
            }
            ApiRoutes.LOGOUT -> respondJson(OkResponse.serializer(), OkResponse())
            // The Inbox badge counts unread messages from the first page of conversations (the list itself hangs).
            ApiRoutes.CONVERSATIONS -> when {
                behaviour == FakeBehaviour.Hang && req.url.parameters["limit"] == "50" ->
                    respondJson(Page.serializer(Conversation.serializer()), Page(listOf(unreadConversation)))
                behaviour == FakeBehaviour.Offline -> throw java.io.IOException("offline")
                else -> awaitCancellation()
            }
            else -> when (behaviour) {
                FakeBehaviour.Hang -> awaitCancellation()
                FakeBehaviour.Offline -> throw java.io.IOException("offline")
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
    }

    private fun startFake(tokens: TokenStore, behaviour: FakeBehaviour) {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = "http://10.0.2.2:8080", platform = PlatformKind.DESKTOP)
        val engine = fakeEngine(behaviour)
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-uicore-shots").also { it.clear() }) }
                        single<TokenStore> { tokens }
                        single<HttpClient> { HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) } }
                    },
                ) + featureModules,
            )
        }
    }

    private fun startLive(session: AuthResponse) {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = live!!, platform = PlatformKind.DESKTOP)
        startKoin {
            modules(
                listOf(
                    module { single<PlatformUrlLauncher> { PlatformUrlLauncher { true } } },
                    coreModule(env),
                    module {
                        single<Settings> { PreferencesSettings(java.util.prefs.Preferences.userRoot().node("campusmarket-uicore-shots").also { it.clear() }) }
                        single<TokenStore> { InMemoryTokenStore(session.toStoredSession()) }
                    },
                ) + featureModules,
            )
        }
    }

    private fun liveSession(email: String): AuthResponse = sessions.getOrPut(email) {
        val env = AppEnvironment(isDebug = true, defaultBaseUrl = live!!, platform = PlatformKind.DESKTOP)
        val api = ApiClient(createPlatformHttpClient { installCampusMarketDefaults(debugLogging = false) }, ServerConfig(env), InMemoryTokenStore())
        runBlocking { api.login(LoginRequest(email, "password123")) }
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

    private fun ComposeUiTest.type(label: String, text: String) =
        onAllNodes(hasSetTextAction() and hasText(label, substring = true)).onFirst().performTextInput(text)

    private fun SkikoComposeUiTest.shot(name: String) {
        repeat(20) {
            Thread.sleep(80)
            waitForIdle()
        }
        dir!!.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    private fun render(
        dark: Boolean,
        fontScale: Float = 1f,
        start: () -> Unit,
        content: @Composable () -> Unit = { App() },
        block: SkikoComposeUiTest.() -> Unit,
    ) {
        start()
        try {
            runSkikoComposeUiTest(size = Size(412f * 2, 892f * 2), density = Density(2f, fontScale)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) { content() }
                }
                try {
                    block()
                } catch (e: Throwable) {
                    runCatching { shot("${if (dark) "dark" else "light"}-failure") }
                    throw e
                }
            }
        } finally {
            runCatching { stopKoin() }
        }
    }

    private fun signedIn() = InMemoryTokenStore(StoredSession("a1", "r1", farFuture, testUser))

    @Test
    fun uiCoreScreenshots() {
        if (dir == null) {
            println("UiCoreScreenshotTest skipped: set CAMPUS_MARKET_SCREENSHOTS")
            return
        }
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"

            render(dark, start = { startFake(InMemoryTokenStore(), FakeBehaviour.Normal) }) {
                awaitText("Create account")
                shot("$t-a01-welcome")
                click("Create account")
                awaitText("Join Quad")
                shot("$t-a02-register")
                click("Continue")
                awaitText("Enter your campus email")
                shot("$t-a03-register-errors")
                type("Campus email", "ada@uni.edu")
                type("Display name", "Ada")
                type("Password", "password123")
                click("Continue")
                awaitText("Check your email")
                shot("$t-a04-verify")
                onAllNodes(hasSetTextAction() and hasContentDescription("Verification code")).onFirst().performTextInput("111111")
                awaitText("isn't right")
                shot("$t-a05-verify-error")
            }
            render(dark, start = { startFake(InMemoryTokenStore(), FakeBehaviour.Normal) }) {
                awaitText("Create account")
                click("Sign in")
                awaitText("Welcome back")
                shot("$t-a06-login")
                type("Campus email", "ada@uni.edu")
                type("Password", "wrongpass")
                clickNode(hasText("Sign in") and hasClickAction())
                awaitText("Wrong email or password")
                shot("$t-a07-login-error")
            }
            render(dark, start = { startFake(signedIn(), FakeBehaviour.Suspended) }) {
                awaitText("suspended")
                shot("$t-a08-suspended-dialog")
                click("Close")
                shot("$t-a09-welcome-suspended")
            }
            // Signed-in shell: first loads that never finish (skeletons), the Inbox badge, and offline.
            render(dark, start = { startFake(signedIn(), FakeBehaviour.Hang) }) {
                awaitText("Browse")
                shot("$t-b01-browse-loading")
                click("Inbox")
                shot("$t-b02-inbox-loading")
                click("Profile")
                shot("$t-b03-profile-loading")
            }
            render(dark, start = { startFake(signedIn(), FakeBehaviour.Offline) }) {
                awaitText("Browse")
                shot("$t-b04-browse-offline")
            }
            render(dark, start = {}, content = { CampusMarketTheme { ComponentGallery() } }) {
                shot("$t-c01-components")
            }
            render(dark, start = {}, content = { CampusMarketTheme { StateGallery() } }) {
                shot("$t-c03-states")
            }
            render(dark, start = {}, content = { CampusMarketTheme { DialogGallery() } }) {
                shot("$t-c02-dialog")
            }

            val liveLogin = live?.let { runCatching { liveSession("liam@state.edu") }.onFailure { println("Live server unavailable: $it") }.getOrNull() }
            if (liveLogin != null) {
                render(dark, start = { startLive(liveLogin) }) {
                    awaitText("Campbell Biology")
                    shot("$t-d01-browse")
                    click("Sell")
                    awaitText("Post listing")
                    shot("$t-d02-sell")
                    click("Inbox")
                    waitForIdle()
                    shot("$t-d03-inbox")
                    click("Profile")
                    awaitText("My listings")
                    shot("$t-d04-profile")
                    click("My listings")
                    shot("$t-d05-pushed")
                }
            }
        }

        // Large text (1.3×), light only.
        render(false, fontScale = 1.3f, start = { startFake(InMemoryTokenStore(), FakeBehaviour.Normal) }) {
            awaitText("Create account")
            shot("light-f01-welcome-1_3x")
            click("Create account")
            awaitText("Join Quad")
            shot("light-f02-register-1_3x")
        }
        render(false, fontScale = 1.3f, start = { startFake(signedIn(), FakeBehaviour.Hang) }) {
            awaitText("Browse")
            shot("light-f03-tabs-1_3x")
        }
    }

    @Composable
    private fun ComponentGallery() {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PrimaryButton("Primary", onClick = {})
                    PrimaryButton("Loading", onClick = {}, loading = true)
                    PrimaryButton("Off", onClick = {}, enabled = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SecondaryButton("Secondary", onClick = {})
                    DestructiveButton("Delete", onClick = {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Avatar(null, "Maya Patel", size = 44.dp)
                    Avatar(null, "liam", size = 44.dp)
                    Avatar(null, "Sofia Rossi", size = 44.dp)
                    Avatar("http://127.0.0.1:1/missing.jpg", "Broken Url", size = 44.dp)
                    CountBadge(3)
                    CountBadge(120)
                    CountBadge(0)
                    StatusChip("Paid", ChipTone.Info)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RemoteImage(null, null, Modifier.width(72.dp).height(72.dp))
                    RemoteImage("http://127.0.0.1:1/missing.jpg", null, Modifier.width(72.dp).height(72.dp))
                    ListingCardSkeleton(Modifier.width(140.dp))
                }
                ListRowSkeleton(leading = SkeletonLeading.Avatar)
                InlineError("Wrong email or password", Modifier.fillMaxWidth())
                InfoBanner("Your session has ended. Please sign in again.", Modifier.fillMaxWidth())
                AppSnackbar(snackbar("Listing deleted"))
                AppSnackbar(snackbar("Couldn't save your changes. Check your connection.", isError = true, dismiss = true))
                AppSnackbar(snackbar("Removed from favorites", action = "Undo"))
            }
        }
    }

    @Composable
    private fun StateGallery() {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                // Deliberately short boxes: the states must scroll, not clip their buttons.
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    EmptyState(
                        "No messages yet",
                        message = "When you message a seller, the conversation shows up here.",
                        icon = QuadIcons.ChatBubbleOutline,
                        actionLabel = "Browse listings",
                        onAction = {},
                    )
                }
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    ErrorState(ApiError(ApiError.NETWORK, "Can't reach Quad. Check your connection and try again."), onRetry = {})
                }
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    ErrorState(ApiError(ErrorCodes.NOT_FOUND, "This listing was removed."), onRetry = {})
                }
            }
        }
    }

    private fun snackbar(text: String, action: String? = null, isError: Boolean = false, dismiss: Boolean = false) = object : SnackbarData {
        override val visuals: SnackbarVisuals = AppSnackbarVisuals(text, actionLabel = action, withDismissAction = dismiss, isError = isError)
        override fun performAction() = Unit
        override fun dismiss() = Unit
    }

    @Composable
    private fun DialogGallery() {
        Scaffold { padding ->
            Text("Behind the dialog", Modifier.padding(padding).padding(Spacing.screen), style = MaterialTheme.typography.bodyLarge)
            ConfirmDialog(
                title = "Delete this listing?",
                message = "It disappears from Browse and buyers can no longer message you about it.",
                confirmLabel = "Delete",
                onConfirm = {},
                onDismiss = {},
                destructive = true,
            )
        }
    }

    companion object {
        private val sessions = mutableMapOf<String, AuthResponse>()
    }
}
