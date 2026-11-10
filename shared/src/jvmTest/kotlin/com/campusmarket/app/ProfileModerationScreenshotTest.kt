package com.campusmarket.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.campusmarket.app.feature.moderation.DisputeDetailRoute
import com.campusmarket.app.feature.moderation.DisputesRoute
import com.campusmarket.app.feature.moderation.ModerationLogRoute
import com.campusmarket.app.feature.moderation.ModerationQueueRoute
import com.campusmarket.app.feature.moderation.ReportDetailRoute
import com.campusmarket.app.feature.moderation.StaffRoute
import com.campusmarket.app.feature.profile.BlockedUsersRoute
import com.campusmarket.app.feature.profile.EditProfileRoute
import com.campusmarket.app.feature.profile.ProfileRoute
import com.campusmarket.app.feature.profile.SettingsRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.UserPrivate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Phone-sized screenshots of every profile and moderation screen, light and dark, against the running dev server with
 * the seeded demo data. Read-only: it signs in, navigates and opens dialogs/sheets, but never confirms one. Opt-in:
 * ```
 * CAMPUS_MARKET_LIVE=http://127.0.0.1:8080 CAMPUS_MARKET_SCREENSHOTS=/some/dir \
 *   ./gradlew :shared:jvmTest --tests '*ProfileModerationScreenshotTest*'
 * ```
 * `CAMPUS_MARKET_FONT_SCALE=1.3` renders with larger text.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileModerationScreenshotTest : ScreenshotGallery(settleMillis = 2_500) {

    private fun run(dark: Boolean, email: String, start: Any, block: SkikoComposeUiTest.(UserPrivate) -> Unit) {
        val session = LiveServer.session(email)
        startTestApp(server = null, tokens = LiveServer.tokens(email))
        prefix = if (dark) "dark-" else "light-"
        screens(dark, content = { Destinations(session.user, start) }) { block(session.user) }
    }

    @Test
    fun profileAndModerationScreenshots() {
        if (!enabled(live = true)) return
        val buyer = "liam@state.edu"
        val seller = runBlocking { LiveServer.api("maya@state.edu").me() }
        val noRatings = runBlocking { LiveServer.api("priya@state.edu").me() }
        val modApi = LiveServer.api("moderator@state.edu")
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
                pop()
                go(EditProfileRoute)
                awaitText("Display name")
                shot("p05-edit-profile")
                pop()
                go(BlockedUsersRoute)
                waitForIdle()
                shot("p06-blocked")
                pop()
                go(UserProfileRoute(me.id))
                awaitText(me.displayName)
                shot("p07-own-public")
                go(UserProfileRoute(seller.id))
                awaitText(seller.bio.take(12))
                shot("p08-other-public")
                runCatching { click("Ratings", substring = true) }
                shot("p09-other-ratings")
                runCatching { click("Ratings", substring = true) }
                runCatching { click("Listings", substring = true) }
                runCatching { click("Message") }
                shot("p10-message-sheet")
                pop()
                go(UserProfileRoute(seller.id))
                awaitText(seller.bio.take(12))
                runCatching { click("Block") }
                shot("p11-block-dialog")
                runCatching { click("Cancel") }
                runCatching { clickNode(hasContentDescription("More options") and hasClickAction()) }
                shot("p11b-other-menu")
                runCatching { click("Report ${seller.displayName}") }
                shot("p12-report-sheet")
                runCatching { click("Spam") }
                shot("p13-report-sheet-reason")
                pop()
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
                runCatching { click("Open") }
                runCatching { click("Messages") }
                shot("m04-queue-filter-messages")
                runCatching { click("All") }
                openReports.forEachIndexed { i, r ->
                    go(ReportDetailRoute(r.id))
                    awaitText(r.reporter.displayName)
                    shot("m1$i-report-${r.targetType.name.lowercase()}")
                    scrollDown(3)
                    shot("m1$i-report-${r.targetType.name.lowercase()}-bottom")
                    pop()
                }
                resolved.firstOrNull()?.let { r ->
                    go(ReportDetailRoute(r.id))
                    awaitText(r.reporter.displayName)
                    shot("m20-report-closed")
                    scrollDown(3)
                    shot("m21-report-closed-bottom")
                    pop()
                }
                openReports.firstOrNull { it.targetUser != null }?.let { r ->
                    go(ReportDetailRoute(r.id))
                    awaitText(r.reporter.displayName)
                    runCatching { click("Suspend user", last = true) }
                    shot("m22-suspend-dialog")
                    pop()
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
                    pop()
                }
                closedDisputes.firstOrNull()?.let { c ->
                    go(DisputeDetailRoute(c.orderId))
                    awaitText(c.listing.title)
                    scrollDown(3)
                    shot("m35-dispute-closed-bottom")
                    pop()
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
                runCatching { click("Make moderator") }
                shot("a04-staff-promote-dialog")
            }
        }
    }
}
