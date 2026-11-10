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
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
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
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test

/**
 * Phone-sized screenshots of the app shell and auth screens (light and dark, plus 1.3× font scale), and a gallery
 * of the shared components, against a fake server. Opt-in:
 * ```
 * CAMPUS_MARKET_SCREENSHOTS=/some/dir ./gradlew :shared:jvmTest --tests '*UiCoreScreenshotTest*'
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class UiCoreScreenshotTest : ScreenshotGallery(settleMillis = 1_600) {
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

    private fun fakeServer(behaviour: FakeBehaviour) = FakeServer { req ->
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

    /** The real App() against the fake server; [tokens] decide whether it starts signed in. */
    private fun app(dark: Boolean, tokens: TokenStore, behaviour: FakeBehaviour, fontScale: Float = 1f, landscape: Boolean = false, block: SkikoComposeUiTest.() -> Unit) {
        startTestApp(fakeServer(behaviour), tokens, baseUrl = "http://10.0.2.2:8080")
        screens(dark, fontScale, landscape, block = block)
    }

    private fun gallery(dark: Boolean, content: @Composable () -> Unit, name: String) =
        screens(dark, fontScale = 1f, content = { CampusMarketTheme { content() } }) { shot(name) }

    @Test
    fun uiCoreScreenshots() {
        if (!enabled()) return
        for (dark in listOf(false, true)) {
            prefix = if (dark) "dark-" else "light-"

            app(dark, InMemoryTokenStore(), FakeBehaviour.Normal) {
                awaitText("Create account")
                shot("a01-welcome")
                click("Create account")
                awaitText("Join Quad")
                shot("a02-register")
                click("Continue")
                awaitText("Enter your campus email")
                shot("a03-register-errors")
                type("Campus email", "ada@uni.edu")
                type("Display name", "Ada")
                type("Password", "password123")
                click("Continue")
                awaitText("Check your email")
                shot("a04-verify")
                onAllNodes(hasSetTextAction() and hasContentDescription("Verification code")).onFirst().performTextInput("111111")
                awaitText("isn't right")
                shot("a05-verify-error")
            }
            app(dark, InMemoryTokenStore(), FakeBehaviour.Normal) {
                awaitText("Create account")
                click("Sign in")
                awaitText("Welcome back")
                shot("a06-login")
                type("Campus email", "ada@uni.edu")
                type("Password", "wrongpass")
                clickNode(hasText("Sign in") and hasClickAction())
                awaitText("Wrong email or password")
                shot("a07-login-error")
            }
            app(dark, signedIn(), FakeBehaviour.Suspended) {
                awaitText("suspended")
                shot("a08-suspended-dialog")
                click("Close")
                shot("a09-welcome-suspended")
            }
            // Signed-in shell: first loads that never finish (skeletons), the Inbox badge, and offline.
            app(dark, signedIn(), FakeBehaviour.Hang) {
                awaitText("Browse")
                shot("b01-browse-loading")
                click("Inbox")
                shot("b02-inbox-loading")
                click("Profile")
                shot("b03-profile-loading")
                // A frame in the middle of the push transition.
                mainClock.autoAdvance = false
                val node = onAllNodes(hasText("My listings") and hasClickAction()).onFirst()
                runOnUiThread { node.performClick() }
                mainClock.advanceTimeBy(140)
                save("b05-push-transition")
                mainClock.autoAdvance = true
            }
            app(dark, signedIn(), FakeBehaviour.Offline) {
                awaitText("Browse")
                shot("b04-browse-offline")
            }
            gallery(dark, { ComponentGallery() }, "c01-components")
            gallery(dark, { StateGallery() }, "c03-states")
            gallery(dark, { DialogGallery() }, "c02-dialog")
        }

        prefix = "light-"
        // Landscape: the Welcome screen scrolls rather than squashing the hero (here with the suspension notice).
        app(false, signedIn(), FakeBehaviour.Suspended, landscape = true) {
            awaitText("suspended")
            click("Close")
            shot("g01-welcome-landscape")
        }

        // Large text (1.3×), light only.
        app(false, InMemoryTokenStore(), FakeBehaviour.Normal, fontScale = 1.3f) {
            awaitText("Create account")
            shot("f01-welcome-1_3x")
            click("Create account")
            awaitText("Join Quad")
            shot("f02-register-1_3x")
        }
        app(false, signedIn(), FakeBehaviour.Hang, fontScale = 1.3f) {
            awaitText("Browse")
            shot("f03-tabs-1_3x")
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
}
