package com.campusmarket.server.moderation

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateReportRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.ModerationNoteRequest
import com.campusmarket.core.Page
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.ResolveReportRequest
import com.campusmarket.core.SuspendUserRequest
import com.campusmarket.core.UserRole
import com.campusmarket.server.AppDeps
import com.campusmarket.server.Budget
import com.campusmarket.server.TestEnv
import com.campusmarket.server.UserRateLimits
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.hours

class ModerationAuditTest {

    @Test
    fun reportingIsRateLimitedPerUser() =
        apiTest(AppDeps(TestEnv.config.copy(userRateLimits = UserRateLimits(reports = Budget(2, 1.hours))), TestEnv.db)) { env ->
            val seller = env.user("sally")
            val reporter = env.user("rita")
            val listings = (1..3).map { env.listing(seller, title = "Item $it") }
            suspend fun report(i: Int) = client.post(ApiRoutes.REPORTS) {
                auth(reporter); json(CreateReportRequest(ReportTargetType.LISTING, listings[i].toString(), ReportReason.SCAM))
            }
            assertEquals(HttpStatusCode.OK, report(0).status)
            assertEquals(HttpStatusCode.OK, report(1).status)
            val res = report(2)
            assertEquals(HttpStatusCode.TooManyRequests, res.status)
            assertEquals(ErrorCodes.RATE_LIMITED, res.body<ErrorResponse>().code)
            // someone else can still report
            val other = env.user("otto")
            assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.REPORTS) {
                auth(other); json(CreateReportRequest(ReportTargetType.LISTING, listings[2].toString(), ReportReason.SCAM))
            }.status)
        }

    @Test
    fun controlCharactersInReportDetailsAreDroppedInsteadOfFailing() = apiTest { env ->
        val seller = env.user("sally")
        val reporter = env.user("rita")
        val listing = env.listing(seller)
        val res = client.post(ApiRoutes.REPORTS) {
            auth(reporter); json(CreateReportRequest(ReportTargetType.LISTING, listing.toString(), ReportReason.SCAM, "fake\u0000 item"))
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("fake item", env.db.tx { c -> c.queryOne("SELECT details FROM reports WHERE target_id = ?", listing) { it.getString(1) } })
    }

    /** The suspended user sees `suspension_reason` on every request; moderators' notes must not end up there. */
    @Test
    fun moderatorTextIsNotShownToTheSuspendedUser() = apiTest { env ->
        val mod = env.user("mo", role = UserRole.MODERATOR)
        val reporter = env.user("rita")
        val viaReport = env.user("victor")
        val direct = env.user("dora")
        client.post(ApiRoutes.REPORTS) { auth(reporter); json(CreateReportRequest(ReportTargetType.USER, viaReport.id.toString(), ReportReason.HARASSMENT)) }
        val reportId = env.db.tx { c -> c.queryOne("SELECT id FROM reports WHERE target_id = ?", viaReport.id) { it.getString(1) } }!!
        val note = "reported by rita@state.edu, 3rd time"
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.modReportResolve(reportId)) {
            auth(mod); json(ResolveReportRequest(ModerationAction.SUSPEND_USER, note, 7))
        }.status)
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.modUserSuspend(direct.id.toString())) {
            auth(mod); json(SuspendUserRequest("complaint from rita@state.edu", 7))
        }.status)

        for (u in listOf(viaReport, direct)) {
            val res = client.get(ApiRoutes.ME) { auth(u) }
            assertEquals(HttpStatusCode.Forbidden, res.status)
            val err = res.body<ErrorResponse>()
            assertEquals(ErrorCodes.ACCOUNT_SUSPENDED, err.code)
            assertFalse("rita" in err.message, err.message)
            assertEquals("Your account is suspended: Violation of community guidelines", err.message)
        }
        // the moderators keep their notes
        val notes = client.get(ApiRoutes.MOD_LOG) { auth(mod) }.body<Page<ModerationLogEntry>>().items.map { it.note }.toSet()
        assertEquals(setOf(note, "complaint from rita@state.edu"), notes)
    }

    @Test
    fun aListingOfADeletedAccountCannotBeRestored() = apiTest { env ->
        val mod = env.user("mo", role = UserRole.MODERATOR)
        val seller = env.user("sally")
        val listing = env.listing(seller, status = ListingStatus.REMOVED)
        env.db.tx { c -> c.update("UPDATE users SET deleted_at = now() WHERE id = ?", seller.id) }
        val res = client.post(ApiRoutes.modListingRestore(listing.toString())) { auth(mod); json(ModerationNoteRequest("appeal")) }
        assertEquals(HttpStatusCode.Conflict, res.status, res.bodyAsText())
        val status = env.db.tx { c -> c.queryOne("SELECT status FROM listings WHERE id = ?", listing) { it.getString(1) } }
        assertEquals("REMOVED", status)

        // a live seller's removed listing is still restorable
        val other = env.listing(env.user("sam"), status = ListingStatus.REMOVED)
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.modListingRestore(other.toString())) { auth(mod); json(ModerationNoteRequest()) }.status)
    }
}
