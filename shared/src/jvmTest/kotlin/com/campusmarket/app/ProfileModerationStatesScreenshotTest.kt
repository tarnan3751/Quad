package com.campusmarket.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.feature.moderation.DisputeDetailRoute
import com.campusmarket.app.feature.moderation.ModerationLogRoute
import com.campusmarket.app.feature.moderation.ModerationQueueRoute
import com.campusmarket.app.feature.moderation.ReportDetailRoute
import com.campusmarket.app.feature.moderation.StaffRoute
import com.campusmarket.app.feature.profile.BlockedUsersRoute
import com.campusmarket.app.feature.profile.EditProfileRoute
import com.campusmarket.app.feature.profile.ProfileRoute
import com.campusmarket.app.feature.profile.SettingsRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.OkResponse
import com.campusmarket.core.OrderDispute
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.Rating
import com.campusmarket.core.Report
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.StaffMember
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.campusmarket.core.UserRole
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test

/**
 * Screenshots of the profile and moderation screens in states the demo data doesn't have (many ratings, photo avatars,
 * long queues, closed reports, a busy log, loading and error), light and dark, against a fake server. Opt-in:
 * ```
 * CAMPUS_MARKET_SCREENSHOTS=/some/dir [CAMPUS_MARKET_SHOT_PHOTOS=/dir/with/jpgs] [CAMPUS_MARKET_FONT_SCALE=1.3] \
 *   ./gradlew :shared:jvmTest --tests '*ProfileModerationStatesScreenshotTest*'
 * ```
 * Photo avatars use a.jpg / b.jpg / c.jpg / d.jpg from `CAMPUS_MARKET_SHOT_PHOTOS` when given (initials otherwise).
 */
@OptIn(ExperimentalTestApi::class)
class ProfileModerationStatesScreenshotTest : ScreenshotGallery(settleMillis = 1_500) {

    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val day = 24 * hour

    private val me = UserPrivate(
        id = "me", email = "ada@state.edu", displayName = "Ada Okafor", bio = "Junior, CS. Usually around the library after 4pm.",
        avatarUrl = photoUrl("c.jpg"), campusDomain = "state.edu", role = UserRole.ADMIN, ratingAverage = 4.8, ratingCount = 12,
        payoutsEnabled = true, joinedAt = now - 400 * day,
    )
    private val sam = UserPublic(
        "sam", "Sam Rivera", bio = "Selling my dorm stuff before graduation. Pickups by the student union.",
        avatarUrl = photoUrl("a.jpg"), campusDomain = "state.edu", ratingAverage = 4.6, ratingCount = 23, joinedAt = now - 700 * day,
    )
    private val blocked = UserPublic("blk", "Jordan Reyes", campusDomain = "state.edu", ratingAverage = 3.0, ratingCount = 2, joinedAt = now - 90 * day, blockedByMe = true)
    private val deleted = UserPublic("gone", "Deleted user", campusDomain = "state.edu", joinedAt = now - 90 * day)
    private val raters = listOf(
        UserPublic("r1", "Liam O'Connor", avatarUrl = photoUrl("b.jpg"), campusDomain = "state.edu", joinedAt = 1L),
        UserPublic("r2", "Noah Williams", campusDomain = "state.edu", joinedAt = 1L),
        UserPublic("r3", "Priya Patel", avatarUrl = photoUrl("d.jpg"), campusDomain = "state.edu", joinedAt = 1L),
        UserPublic("r4", "Sofia Alvarez", campusDomain = "state.edu", joinedAt = 1L),
        UserPublic("r5", "Maya Chen", campusDomain = "state.edu", joinedAt = 1L),
    )
    private val comments = listOf(
        "Exactly as described and Sam was right on time at the union.",
        "Great communication, would buy again.",
        "",
        "Lamp works perfectly. Thanks!",
        "Bit of a wait at pickup but the bike is in great shape.",
        "Super friendly, threw in a free textbook cover.",
    )
    private val stars = List(15) { 5 } + List(5) { 4 } + List(2) { 3 } + listOf(1)
    private val ratings = stars.mapIndexed { i, s ->
        Rating(
            "rt$i", "o$i", raters[i % raters.size], "sam", s, comments[i % comments.size],
            listingTitle = listOf("Desk lamp with USB port", "Road bike, 54cm", "Mini fridge, 3.2 cu ft", "Calculus textbook")[i % 4],
            createdAt = now - (i * 3 + 1) * day,
        )
    }

    private fun listing(id: String, title: String, cents: Int, img: String?, seller: UserPublic = sam, status: ListingStatus = ListingStatus.ACTIVE, age: Long = 2 * day) =
        ListingSummary(id, title, cents, category = ListingCategory.OTHER, condition = ListingCondition.GOOD, status = status,
            thumbnailUrl = photoUrl(img ?: ""), sellerId = seller.id, sellerName = seller.displayName, createdAt = now - age)

    private val samListings = listOf(
        listing("l1", "Acoustic guitar with soft case", 12000, "a.jpg"),
        listing("l2", "Monstera plant, 3ft", 2500, "b.jpg"),
        listing("l3", "Polaroid camera + 2 film packs", 6500, "c.jpg"),
        listing("l4", "Mini fridge, 3.2 cu ft", 8000, "d.jpg"),
    )
    private val myListings = samListings.mapIndexed { i, l -> l.copy(id = "m$i", sellerId = "me", status = if (i < 3) ListingStatus.ACTIVE else ListingStatus.SOLD) } +
        listing("m9", "Lab coat, size M", 1500, null, status = ListingStatus.SOLD)

    private val mod = UserPublic("mod", "Morgan Moderator", campusDomain = "state.edu", joinedAt = 1L)
    private val reports = listOf(
        Report("rp1", raters[0], ReportTargetType.LISTING, "l1", ReportReason.SCAM, "Asked me to pay with gift cards before meeting, then stopped replying.",
            ReportStatus.OPEN, "Acoustic guitar with soft case", sam, samListings[0], openReportsAgainstTargetUser = 3, createdAt = now - 3 * day),
        Report("rp2", raters[1], ReportTargetType.MESSAGE, "msg1", ReportReason.HARASSMENT, "",
            ReportStatus.OPEN, "If you don't lower the price I'll tell everyone you're a scammer.", blocked, samListings[1], openReportsAgainstTargetUser = 1, createdAt = now - 5 * hour),
        Report("rp3", raters[2], ReportTargetType.USER, "blk", ReportReason.SPAM, "Keeps messaging me about a crypto 'opportunity'.",
            ReportStatus.OPEN, "Jordan Reyes", blocked, openReportsAgainstTargetUser = 2, createdAt = now - 40 * 60_000L),
        Report("rp4", raters[3], ReportTargetType.LISTING, "l3", ReportReason.MISLEADING, "Photos are from a store website.",
            ReportStatus.OPEN, "Polaroid camera + 2 film packs", sam, samListings[2].copy(status = ListingStatus.RESERVED), openReportsAgainstTargetUser = 3, createdAt = now - 26 * hour),
    )
    private val closedReports = listOf(
        Report("rc1", raters[4], ReportTargetType.USER, "blk", ReportReason.HARASSMENT, "Sent abusive messages after I declined.",
            ReportStatus.RESOLVED, "Jordan Reyes", blocked, resolutionAction = ModerationAction.SUSPEND_USER,
            resolutionNote = "Screenshots confirm repeated insults. 7 day suspension.", resolvedBy = mod, createdAt = now - 4 * day, resolvedAt = now - 3 * day),
        Report("rc2", raters[0], ReportTargetType.LISTING, "l2", ReportReason.PROHIBITED_ITEM, "",
            ReportStatus.RESOLVED, "Monstera plant, 3ft", sam, samListings[1].copy(status = ListingStatus.REMOVED), resolutionAction = ModerationAction.REMOVE_LISTING,
            resolvedBy = mod, createdAt = now - 6 * day, resolvedAt = now - 5 * day),
    )
    private val dispute = DisputeCase(
        "ord1", samListings[3], raters[2], sam, 8000, 400, orderStatus = OrderStatus.DISPUTED,
        dispute = OrderDispute("d1", DisputeReason.ITEM_NOT_AS_DESCRIBED, "The fridge doesn't get cold at all. Sam says it worked last week but won't answer since.", DisputeStatus.OPEN, openedAt = now - 20 * hour),
        orderCreatedAt = now - 3 * day, paidAt = now - 3 * day + hour, sellerSuspended = true,
    )
    private val closedDispute = dispute.copy(
        orderId = "ord2", orderStatus = OrderStatus.REFUNDED,
        dispute = dispute.dispute.copy(id = "d2", status = DisputeStatus.CLOSED, outcome = DisputeOutcome.REFUNDED_BY_MODERATOR, closedAt = now - 2 * hour),
        resolvedBy = mod, resolutionNote = "Seller admitted in chat the compressor is broken.",
    )
    private val log = listOf(
        ModerationLogEntry("g1", mod, ModerationAction.RESOLVE_DISPUTE, ReportTargetType.ORDER, "ord2", "Seller admitted in chat the compressor is broken.", createdAt = now - 2 * hour),
        ModerationLogEntry("g2", UserPublic("me", me.displayName, avatarUrl = me.avatarUrl, campusDomain = "state.edu", joinedAt = 1L), ModerationAction.CHANGE_ROLE, ReportTargetType.USER, "mod", "USER -> MODERATOR", createdAt = now - 5 * hour),
        ModerationLogEntry("g3", mod, ModerationAction.SUSPEND_USER, ReportTargetType.USER, "blk", "Screenshots confirm repeated insults. 7 day suspension.", reportId = "rc1", createdAt = now - 3 * day),
        ModerationLogEntry("g4", mod, ModerationAction.DISMISS, ReportTargetType.MESSAGE, "msg9", "", reportId = "rc9", createdAt = now - 3 * day - hour),
        ModerationLogEntry("g5", mod, ModerationAction.REMOVE_LISTING, ReportTargetType.LISTING, "l2", "", reportId = "rc2", createdAt = now - 5 * day),
        ModerationLogEntry("g6", mod, ModerationAction.WARN_USER, ReportTargetType.USER, "r2", "First warning for spammy messages.", createdAt = now - 6 * day),
    )
    private val staff = listOf(
        StaffMember(UserPublic("me", me.displayName, avatarUrl = me.avatarUrl, campusDomain = "state.edu", joinedAt = 1L), UserRole.ADMIN, me.email),
        StaffMember(mod, UserRole.MODERATOR, "moderator@state.edu"),
    )

    private fun startApp() {
        val users = (listOf(sam, blocked, deleted) + raters).associateBy { it.id }
        val server = FakeServer { req ->
            val path = req.url.encodedPath
            when {
                path == ApiRoutes.ME -> respondJson(UserPrivate.serializer(), me)
                path == ApiRoutes.MY_LISTINGS -> respondJson(Page.serializer(ListingSummary.serializer()), Page(myListings))
                path == ApiRoutes.MY_BLOCKS -> respondJson(ListSerializer(UserPublic.serializer()), listOf(blocked, raters[1].copy(blockedByMe = true), raters[3].copy(blockedByMe = true)))
                path == ApiRoutes.user("slow") || path == ApiRoutes.modReport("slow") -> awaitCancellation()
                path == ApiRoutes.user("broken") -> respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL, "Something went wrong on our side. Try again in a moment.")
                path.startsWith("/api/v1/users/") && path.endsWith("/ratings") ->
                    respondJson(Page.serializer(Rating.serializer()), Page(if (path.contains("/sam/")) ratings else emptyList()))
                path.startsWith("/api/v1/users/") && path.endsWith("/listings") ->
                    respondJson(Page.serializer(ListingSummary.serializer()), Page(if (path.contains("/sam/")) samListings else emptyList()))
                path.startsWith("/api/v1/users/") -> users[path.substringAfterLast('/')]?.let { respondJson(UserPublic.serializer(), it) }
                    ?: respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "Not found")
                path == ApiRoutes.REPORTS && req.method == HttpMethod.Post -> respondJson(OkResponse.serializer(), OkResponse())
                path == ApiRoutes.MOD_REPORTS -> respondJson(
                    Page.serializer(Report.serializer()),
                    Page(if (req.url.parameters["status"] == "OPEN") reports else closedReports.filter { it.status.name == req.url.parameters["status"] }),
                )
                path.startsWith(ApiRoutes.MOD_REPORTS + "/") -> (reports + closedReports).firstOrNull { it.id == path.substringAfterLast('/') }
                    ?.let { respondJson(Report.serializer(), it) } ?: respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
                path == ApiRoutes.MOD_DISPUTES -> respondJson(
                    Page.serializer(DisputeCase.serializer()),
                    Page(if (req.url.parameters["status"] == "CLOSED") listOf(closedDispute) else listOf(dispute)),
                )
                path == ApiRoutes.modDispute("ord1") -> respondJson(DisputeCase.serializer(), dispute)
                path == ApiRoutes.modDispute("ord2") -> respondJson(DisputeCase.serializer(), closedDispute)
                path == ApiRoutes.MOD_LOG -> respondJson(Page.serializer(ModerationLogEntry.serializer()), Page(log))
                path == ApiRoutes.MOD_STAFF -> respondJson(ListSerializer(StaffMember.serializer()), staff)
                path == ApiRoutes.MOD_USER_SEARCH -> respondJson(
                    ListSerializer(StaffMember.serializer()),
                    listOf(staff[1], StaffMember(raters[2], UserRole.USER, "priya@state.edu"), StaffMember(sam, UserRole.USER, "sam@state.edu")),
                )
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        startTestApp(server, InMemoryTokenStore(StoredSession("a1", "r1", now + hour, me)))
    }

    private fun run(dark: Boolean, start: Any, block: SkikoComposeUiTest.() -> Unit) {
        startApp()
        prefix = (if (dark) "dark" else "light") + (if (fontScale != 1f) "-x$fontScale" else "") + "-"
        screens(dark, content = { Destinations(me, start) }, block = block)
    }

    @Test
    fun stateScreenshots() {
        if (!enabled()) return
        for (dark in listOf(false, true)) {
            run(dark, ProfileRoute) {
                awaitText("Ada Okafor")
                // The stats card exposes one description per number ("3 Active listings"), not its texts.
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasContentDescription("Active listings", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                shot("s01-profile-admin")
                scrollDown(2)
                shot("s02-profile-admin-menu")
                scrollDown(3)
                shot("s03-profile-bottom")
                go(UserProfileRoute("sam"))
                awaitText("Sam Rivera")
                awaitText("Acoustic guitar")
                shot("s04-public-listings")
                click("Ratings", substring = true)
                awaitText("Exactly as described")
                shot("s05-public-ratings")
                scrollDown(1)
                shot("s06-public-ratings-scrolled")
                pop() // the header scrolled out of the lazy list: open the profile afresh
                go(UserProfileRoute("sam"))
                awaitText("Acoustic guitar")
                click("Message")
                awaitText("Which one are you asking about")
                shot("s07-message-sheet")
                pop()
                go(UserProfileRoute("blk"))
                awaitText("You blocked Jordan Reyes")
                shot("s08-public-blocked")
                pop()
                go(UserProfileRoute("gone"))
                awaitText("This account has been deleted")
                shot("s09-public-deleted")
                pop()
                go(UserProfileRoute("slow"))
                shot("s10-public-loading")
                pop()
                go(UserProfileRoute("broken"))
                awaitText("Try again")
                shot("s11-public-error")
                pop()
                go(UserProfileRoute("sam"))
                awaitText("Sam Rivera")
                click("Report")
                awaitText("Pick the closest reason")
                click(ReportReason.SCAM.label)
                shot("s12-report-reason")
                click("Send report")
                awaitText("Report sent")
                shot("s13-report-sent")
                click("Done")
                click("Report")
                awaitText("You already reported this account")
                shot("s14-report-duplicate")
                click("Done")
                pop()
                go(EditProfileRoute)
                awaitText("Display name")
                shot("s15-edit-profile")
                onAllNodes(hasSetTextAction() and hasText("Display name")).onFirst().performTextClearance()
                onAllNodes(hasSetTextAction() and hasText("Display name")).onFirst().performTextInput("A")
                awaitText("Use at least")
                shot("s16-edit-profile-invalid")
                back()
                awaitText("Discard your changes?")
                shot("s17-edit-profile-discard")
                click("Discard")
                go(BlockedUsersRoute)
                awaitText("Noah Williams")
                shot("s18-blocked-users")
                pop()
                go(SettingsRoute)
                awaitText("Sign out")
                shot("s19-settings")
                click("Delete account")
                awaitText("Delete your account?")
                shot("s20-delete-warning")
            }
            run(dark, ModerationQueueRoute) {
                awaitText("Acoustic guitar")
                shot("s30-queue-open")
                click("Resolved")
                awaitText("Jordan Reyes")
                shot("s31-queue-resolved")
                go(ReportDetailRoute("rp1"))
                awaitText("gift cards")
                shot("s32-report-listing")
                scrollDown(2)
                shot("s33-report-listing-actions")
                click("Suspend user", last = true)
                awaitText("How long")
                shot("s34-suspend-dialog")
                click("Cancel")
                pop()
                go(ReportDetailRoute("rp2"))
                awaitText("tell everyone")
                shot("s35-report-message")
                pop()
                go(ReportDetailRoute("rp4"))
                awaitText("store website")
                scrollDown(2)
                shot("s36-report-reserved-listing")
                pop()
                go(ReportDetailRoute("rc1"))
                awaitText("abusive")
                scrollDown(2)
                shot("s37-report-closed")
                pop()
                go(ReportDetailRoute("slow"))
                shot("s38-report-loading")
                pop()
                go(DisputeDetailRoute("ord1"))
                awaitText("doesn't get cold")
                shot("s40-dispute-open")
                scrollDown(2)
                shot("s41-dispute-open-middle")
                scrollDown(3)
                shot("s42-dispute-decide")
                click("Release to seller")
                awaitText("Reason for the decision")
                shot("s43-dispute-release-dialog")
                click("Cancel")
                pop()
                go(DisputeDetailRoute("ord2"))
                awaitText("doesn't get cold")
                scrollDown(4)
                shot("s44-dispute-closed")
                pop()
                go(ModerationLogRoute)
                awaitText("Seller admitted")
                shot("s50-log")
                pop()
                go(StaffRoute)
                awaitText("Morgan Moderator")
                shot("s60-staff")
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("st")
                awaitText("sam@state.edu")
                shot("s61-staff-search")
                click("Make moderator")
                awaitText("Make Priya Patel a moderator?")
                shot("s62-staff-promote")
            }
        }
    }
}
