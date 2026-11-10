package com.campusmarket.server.moderation

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateReportRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.ModerationNoteRequest
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.Report
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.ResolveReportRequest
import com.campusmarket.core.SuspendUserRequest
import com.campusmarket.core.UserRole
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.campusmarket.server.assertApiError

class ModerationTest {
    private suspend fun ApiTestScope.report(
        u: TestEnv.TestUser, type: ReportTargetType, target: UUID,
        reason: ReportReason = ReportReason.SCAM, details: String = "",
    ): HttpResponse = client.post(ApiRoutes.REPORTS) { auth(u); json(CreateReportRequest(type, target.toString(), reason, details)) }

    private suspend fun ApiTestScope.queue(m: TestEnv.TestUser, query: String = ""): HttpResponse =
        client.get(ApiRoutes.MOD_REPORTS + query) { auth(m) }

    private suspend fun ApiTestScope.resolve(m: TestEnv.TestUser, id: String, action: ModerationAction, note: String = "", days: Int? = null) =
        client.post(ApiRoutes.modReportResolve(id)) { auth(m); json(ResolveReportRequest(action, note, days)) }

    private fun TestEnv.count(sql: String, vararg p: Any?): Int = db.txBlocking { c -> c.queryOne(sql, *p) { it.getInt(1) }!! }

    private fun TestEnv.conversation(listing: UUID, buyer: TestEnv.TestUser, seller: TestEnv.TestUser): UUID {
        val id = UUID.randomUUID()
        db.txBlocking { c ->
            c.update("INSERT INTO conversations (id, listing_id, buyer_id, seller_id) VALUES (?, ?, ?, ?)", id, listing, buyer.id, seller.id)
        }
        return id
    }

    private fun TestEnv.message(conv: UUID, sender: TestEnv.TestUser, body: String): UUID {
        val id = UUID.randomUUID()
        db.txBlocking { c -> c.update("INSERT INTO messages (id, conversation_id, sender_id, body) VALUES (?, ?, ?, ?)", id, conv, sender.id, body) }
        return id
    }

    // ------------------------------------------------------------ creating reports

    @Test
    fun reportListingSnapshotsAndDedups() = apiTest { env ->
        val seller = env.user("seller"); val rep = env.user("rep"); val mod = env.user("mod", role = UserRole.MODERATOR)
        val listing = env.listing(seller, title = "Fake tickets")
        assertEquals(OkResponse(), report(rep, ReportTargetType.LISTING, listing, details = " looks fake ").body())
        // repeat is a no-op 200
        assertEquals(HttpStatusCode.OK, report(rep, ReportTargetType.LISTING, listing, ReportReason.SPAM).status)
        assertEquals(1, env.count("SELECT count(*) FROM reports"))
        val r = queue(mod).body<Page<Report>>().items.single()
        assertEquals("Fake tickets", r.targetSummary)
        assertEquals("looks fake", r.details)
        assertEquals(seller.id.toString(), r.targetUser?.id)
        assertEquals(ReportReason.SCAM, r.reason)
        // another reporter can report the same target
        val rep2 = env.user("rep2")
        report(rep2, ReportTargetType.LISTING, listing)
        assertEquals(2, env.count("SELECT count(*) FROM reports"))
        // after resolution a fresh report is allowed again
        resolve(mod, r.id, ModerationAction.DISMISS)
        report(rep, ReportTargetType.LISTING, listing)
        assertEquals(1, env.count("SELECT count(*) FROM reports WHERE status = 'OPEN'"))
    }

    @Test
    fun reportListingRules() = apiTest { env ->
        val seller = env.user("seller"); val rep = env.user("rep")
        val other = env.user("outsider", domain = "other.edu")
        val listing = env.listing(seller)
        report(seller, ReportTargetType.LISTING, listing).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        report(other, ReportTargetType.LISTING, listing).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        report(rep, ReportTargetType.LISTING, UUID.randomUUID()).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        for (s in listOf(ListingStatus.REMOVED, ListingStatus.DELETED)) {
            report(rep, ReportTargetType.LISTING, env.listing(seller, status = s)).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        }
        // reserved / sold stay reportable
        assertEquals(HttpStatusCode.OK, report(rep, ReportTargetType.LISTING, env.listing(seller, status = ListingStatus.SOLD)).status)
        report(rep, ReportTargetType.LISTING, listing, details = "x".repeat(1001)).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        // malformed id
        client.post(ApiRoutes.REPORTS) {
            auth(rep); json(CreateReportRequest(ReportTargetType.LISTING, "not-a-uuid", ReportReason.SPAM))
        }.assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        // unauthenticated
        assertEquals(HttpStatusCode.Unauthorized, client.post(ApiRoutes.REPORTS) {
            json(CreateReportRequest(ReportTargetType.LISTING, listing.toString(), ReportReason.SPAM))
        }.status)
    }

    @Test
    fun reportUserRules() = apiTest { env ->
        val bad = env.user("badguy"); val rep = env.user("rep"); val other = env.user("far", domain = "other.edu")
        val mod = env.user("mod", role = UserRole.MODERATOR)
        report(rep, ReportTargetType.USER, rep.id).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        report(other, ReportTargetType.USER, bad.id).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        report(rep, ReportTargetType.USER, UUID.randomUUID()).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        val gone = env.user("gone")
        env.db.tx { c -> c.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.id) }
        report(rep, ReportTargetType.USER, gone.id).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        assertEquals(HttpStatusCode.OK, report(rep, ReportTargetType.USER, bad.id, ReportReason.HARASSMENT).status)
        val r = queue(mod).body<Page<Report>>().items.single()
        assertEquals("badguy", r.targetSummary)
        assertEquals(bad.id.toString(), r.targetUser?.id)
        assertNull(r.targetListing)
    }

    @Test
    fun reportMessageRules() = apiTest { env ->
        val seller = env.user("seller"); val buyer = env.user("buyer"); val snoop = env.user("snoop")
        val mod = env.user("mod", role = UserRole.MODERATOR)
        val listing = env.listing(seller, title = "Bike")
        val conv = env.conversation(listing, buyer, seller)
        val msg = env.message(conv, buyer, "send me your password")
        report(snoop, ReportTargetType.MESSAGE, msg).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        report(buyer, ReportTargetType.MESSAGE, msg).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        report(seller, ReportTargetType.MESSAGE, UUID.randomUUID()).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        assertEquals(HttpStatusCode.OK, report(seller, ReportTargetType.MESSAGE, msg, ReportReason.SCAM).status)
        val r = queue(mod).body<Page<Report>>().items.single()
        assertEquals("send me your password", r.targetSummary)
        assertEquals(buyer.id.toString(), r.targetUser?.id)
        assertEquals(listing.toString(), r.targetListing?.id)
        // cross-campus participant (data oddity) can't report
        val far = env.user("far", domain = "other.edu")
        report(far, ReportTargetType.MESSAGE, msg).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
    }

    // ------------------------------------------------------------ queue & detail

    @Test
    fun queueIsModeratorOnlyAndFiltersAndPaginates() = apiTest { env ->
        val user = env.user("plain"); val mod = env.user("mod", role = UserRole.MODERATOR); val admin = env.user("adm", role = UserRole.ADMIN)
        val seller = env.user("seller")
        queue(user).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.MOD_REPORTS).status)
        client.get(ApiRoutes.modReport(UUID.randomUUID().toString())) { auth(user) }.assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        client.get(ApiRoutes.MOD_LOG) { auth(user) }.assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)

        // five open reports from five reporters, spread over time
        val ids = (1..5).map { i ->
            val rep = env.user("rep$i")
            val l = env.listing(seller, title = "Item $i")
            report(rep, ReportTargetType.LISTING, l)
            env.db.txBlocking { c -> c.update("UPDATE reports SET created_at = now() - make_interval(mins => ?) WHERE target_id = ?", 100 - i, l) }
            l.toString()
        }
        val p1 = queue(admin, "?limit=2").body<Page<Report>>()
        assertEquals(listOf(ids[0], ids[1]), p1.items.map { it.targetId })  // oldest first
        assertNotNull(p1.nextCursor)
        val p2 = queue(admin, "?limit=2&cursor=${p1.nextCursor}").body<Page<Report>>()
        assertEquals(listOf(ids[2], ids[3]), p2.items.map { it.targetId })
        val p3 = queue(admin, "?limit=2&cursor=${p2.nextCursor}").body<Page<Report>>()
        assertEquals(listOf(ids[4]), p3.items.map { it.targetId })
        assertNull(p3.nextCursor)
        queue(admin, "?cursor=garbage").assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        queue(admin, "?status=NOPE").assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertEquals(0, queue(admin, "?status=RESOLVED").body<Page<Report>>().items.size)

        // resolve two as dismissed in order; DISMISSED list is newest first
        val first = p1.items[0]; val second = p1.items[1]
        resolve(mod, first.id, ModerationAction.DISMISS)
        Thread.sleep(5)
        resolve(mod, second.id, ModerationAction.DISMISS)
        val d = queue(mod, "?status=DISMISSED").body<Page<Report>>()
        assertEquals(2, d.items.size)
        // newest first by created_at: item 2 was created after item 1
        assertEquals(listOf(second.id, first.id), d.items.map { it.id })
        assertEquals(3, queue(mod).body<Page<Report>>().items.size)
        assertEquals(1, queue(mod, "?status=DISMISSED&limit=1").body<Page<Report>>().items.size)
    }

    @Test
    fun queueCoversAllCampuses() = apiTest { env ->
        val mod = env.user("mod", role = UserRole.MODERATOR)
        val s1 = env.user("s1"); val s2 = env.user("s2", domain = "other.edu")
        report(env.user("r1"), ReportTargetType.LISTING, env.listing(s1))
        report(env.user("r2", domain = "other.edu"), ReportTargetType.LISTING, env.listing(s2))
        assertEquals(2, queue(mod).body<Page<Report>>().items.size)
    }

    @Test
    fun detailIsFullyPopulated() = apiTest { env ->
        val seller = env.user("seller"); val rep = env.user("rep"); val rep2 = env.user("rep2")
        val mod = env.user("mod", role = UserRole.MODERATOR)
        val l1 = env.listing(seller, title = "One"); val l2 = env.listing(seller, title = "Two")
        report(rep, ReportTargetType.LISTING, l1); report(rep2, ReportTargetType.LISTING, l2); report(rep, ReportTargetType.USER, seller.id)
        val id = queue(mod).body<Page<Report>>().items.first { it.targetId == l1.toString() }.id
        val d = client.get(ApiRoutes.modReport(id)) { auth(mod) }.body<Report>()
        assertEquals("rep", d.reporter.displayName)
        assertEquals("seller", d.targetUser?.displayName)
        assertEquals("One", d.targetListing?.title)
        assertNotNull(d.targetListing?.thumbnailUrl)
        assertEquals(3, d.openReportsAgainstTargetUser)
        assertEquals(ReportStatus.OPEN, d.status)
        assertNull(d.resolvedBy); assertNull(d.resolvedAt)
        client.get(ApiRoutes.modReport(UUID.randomUUID().toString())) { auth(mod) }.assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.get(ApiRoutes.modReport("zzz")) { auth(mod) }.assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
    }

    // ------------------------------------------------------------ resolve

    @Test
    fun dismissAndSiblingResolution() = apiTest { env ->
        val seller = env.user("seller"); val mod = env.user("mod", role = UserRole.MODERATOR)
        val l = env.listing(seller); val other = env.listing(seller)
        report(env.user("a"), ReportTargetType.LISTING, l); report(env.user("b"), ReportTargetType.LISTING, l)
        report(env.user("c"), ReportTargetType.LISTING, other)
        val open = queue(mod).body<Page<Report>>().items
        val target = open.first { it.targetId == l.toString() }
        val res = resolve(mod, target.id, ModerationAction.DISMISS, " fine ").body<Report>()
        assertEquals(ReportStatus.DISMISSED, res.status)
        assertEquals(ModerationAction.DISMISS, res.resolutionAction)
        assertEquals("fine", res.resolutionNote)
        assertEquals(mod.id.toString(), res.resolvedBy?.id)
        assertNotNull(res.resolvedAt)
        assertEquals(1, queue(mod).body<Page<Report>>().items.size)
        assertEquals(2, env.count("SELECT count(*) FROM reports WHERE status = 'DISMISSED' AND resolved_by = ?", mod.id))
        assertEquals(1, env.count("SELECT count(*) FROM moderation_actions WHERE report_id = ?", UUID.fromString(target.id)))
        // not open any more
        resolve(mod, target.id, ModerationAction.DISMISS).assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        // sibling was resolved too, so can't be resolved again either
        val sibling = queue(mod, "?status=DISMISSED").body<Page<Report>>().items.first { it.id != target.id }
        resolve(mod, sibling.id, ModerationAction.REMOVE_LISTING).assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        assertEquals("ACTIVE", env.listingStatus(l))
    }

    private fun TestEnv.listingStatus(id: UUID): String = db.txBlocking { c -> c.queryOne("SELECT status FROM listings WHERE id = ?", id) { it.getString(1) }!! }

    @Test
    fun removeListingResolution() = apiTest { env ->
        val seller = env.user("seller"); val mod = env.user("mod", role = UserRole.MODERATOR)
        val l = env.listing(seller)
        report(env.user("a"), ReportTargetType.LISTING, l); report(env.user("b"), ReportTargetType.LISTING, l)
        val id = queue(mod).body<Page<Report>>().items.first().id
        val res = resolve(mod, id, ModerationAction.REMOVE_LISTING, "scam").body<Report>()
        assertEquals(ReportStatus.RESOLVED, res.status)
        assertEquals(ListingStatus.REMOVED, res.targetListing?.status)
        assertEquals("REMOVED", env.listingStatus(l))
        assertEquals(0, queue(mod).body<Page<Report>>().items.size)
        assertEquals(2, queue(mod, "?status=RESOLVED").body<Page<Report>>().items.size)
        val log = client.get(ApiRoutes.MOD_LOG) { auth(mod) }.body<Page<ModerationLogEntry>>().items.single()
        assertEquals(ModerationAction.REMOVE_LISTING, log.action)
        assertEquals(ReportTargetType.LISTING, log.targetType)
        assertEquals(l.toString(), log.targetId)
        assertEquals(id, log.reportId)
        assertEquals("scam", log.note)
        assertEquals("mod", log.moderator.displayName)
    }

    @Test
    fun removeListingResolutionRules() = apiTest { env ->
        val seller = env.user("seller"); val mod = env.user("mod", role = UserRole.MODERATOR)
        val sold = env.listing(seller, status = ListingStatus.SOLD)
        report(env.user("a"), ReportTargetType.LISTING, sold)
        val id = queue(mod).body<Page<Report>>().items.single().id
        resolve(mod, id, ModerationAction.REMOVE_LISTING).assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        // transaction rolled back: still open, no log
        assertEquals(1, queue(mod).body<Page<Report>>().items.size)
        assertEquals(0, env.count("SELECT count(*) FROM moderation_actions"))
        // wrong target type, wrong action
        report(env.user("b"), ReportTargetType.USER, seller.id)
        val userReport = queue(mod).body<Page<Report>>().items.first { it.targetType == ReportTargetType.USER }
        resolve(mod, userReport.id, ModerationAction.REMOVE_LISTING).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        resolve(mod, userReport.id, ModerationAction.RESTORE_LISTING).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        resolve(mod, userReport.id, ModerationAction.UNSUSPEND_USER).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        resolve(mod, userReport.id, ModerationAction.WARN_USER, days = 3).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        resolve(mod, userReport.id, ModerationAction.SUSPEND_USER, days = 0).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        client.post(ApiRoutes.modReportResolve(UUID.randomUUID().toString())) { auth(mod); json(ResolveReportRequest(ModerationAction.DISMISS)) }
            .assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.post(ApiRoutes.modReportResolve(userReport.id)) { auth(env.user("plain")); json(ResolveReportRequest(ModerationAction.DISMISS)) }
            .assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
    }

    @Test
    fun suspendResolutionRevokesTokensAndLogs() = apiTest { env ->
        val seller = env.user("seller"); val mod = env.user("mod", role = UserRole.MODERATOR)
        val l = env.listing(seller)
        env.db.tx { c ->
            c.update("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) VALUES (?, ?, 'h1', now() + interval '1 day')", UUID.randomUUID(), seller.id)
            c.update("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) VALUES (?, ?, 'h2', now() + interval '1 day')", UUID.randomUUID(), seller.id)
        }
        report(env.user("a"), ReportTargetType.LISTING, l)
        val id = queue(mod).body<Page<Report>>().items.single().id
        val res = resolve(mod, id, ModerationAction.SUSPEND_USER, "scammer", days = 7).body<Report>()
        assertEquals(ReportStatus.RESOLVED, res.status)
        assertEquals(0, env.count("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", seller.id))
        val (suspended, hours) = env.db.tx { c ->
            c.queryOne("SELECT suspended, extract(epoch from suspended_until - now()) / 3600 FROM users WHERE id = ?", seller.id) { it.getBoolean(1) to it.getDouble(2) }!!
        }
        assertTrue(suspended)
        assertTrue(hours in 167.0..168.1, "hours=$hours")
        // suspended user can no longer authenticate
        assertEquals(HttpStatusCode.Forbidden, client.get(ApiRoutes.MOD_LOG) { auth(seller) }.status)
        val log = client.get(ApiRoutes.MOD_LOG) { auth(mod) }.body<Page<ModerationLogEntry>>().items.single()
        assertEquals(ReportTargetType.USER, log.targetType)
        assertEquals(seller.id.toString(), log.targetId)
        assertEquals(ModerationAction.SUSPEND_USER, log.action)
    }

    @Test
    fun suspendResolutionIndefiniteDefaultReasonAndHierarchy() = apiTest { env ->
        val mod = env.user("mod", role = UserRole.MODERATOR); val mod2 = env.user("mod2", role = UserRole.MODERATOR)
        val admin = env.user("adm", role = UserRole.ADMIN); val victim = env.user("victim")
        report(env.user("a"), ReportTargetType.USER, victim.id)
        report(env.user("b"), ReportTargetType.USER, mod2.id)
        val reports = queue(mod).body<Page<Report>>().items
        val vr = reports.first { it.targetId == victim.id.toString() }; val mr = reports.first { it.targetId == mod2.id.toString() }
        resolve(mod, mr.id, ModerationAction.SUSPEND_USER).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals(1, queue(mod).body<Page<Report>>().items.count { it.id == mr.id })  // still open
        assertEquals(HttpStatusCode.OK, resolve(mod, vr.id, ModerationAction.SUSPEND_USER).status)
        val row = env.db.tx { c -> c.queryOne("SELECT suspended_until, suspension_reason FROM users WHERE id = ?", victim.id) { it.getTimestamp(1) to it.getString(2) }!! }
        assertNull(row.first)
        assertEquals("Violation of community guidelines", row.second)
        // admin may suspend a moderator through a report
        assertEquals(HttpStatusCode.OK, resolve(admin, mr.id, ModerationAction.SUSPEND_USER, "abuse").status)
    }

    @Test
    fun warnResolution() = apiTest { env ->
        val seller = env.user("seller"); val buyer = env.user("buyer"); val mod = env.user("mod", role = UserRole.MODERATOR)
        val msg = env.message(env.conversation(env.listing(seller), buyer, seller), buyer, "rude words")
        report(seller, ReportTargetType.MESSAGE, msg)
        val id = queue(mod).body<Page<Report>>().items.single().id
        val res = resolve(mod, id, ModerationAction.WARN_USER, "watch it").body<Report>()
        assertEquals(ReportStatus.RESOLVED, res.status)
        assertEquals(buyer.id.toString(), res.targetUser?.id)
        val log = client.get(ApiRoutes.MOD_LOG) { auth(mod) }.body<Page<ModerationLogEntry>>().items.single()
        assertEquals(buyer.id.toString(), log.targetId)
        assertEquals(ReportTargetType.USER, log.targetType)
    }

    @Test
    fun warnUserOnListingReport() = apiTest { env ->
        val seller = env.user("seller"); val mod = env.user("mod", role = UserRole.MODERATOR)
        val l = env.listing(seller)
        report(env.user("a"), ReportTargetType.LISTING, l)
        val id = queue(mod).body<Page<Report>>().items.single().id
        val res = resolve(mod, id, ModerationAction.WARN_USER, "be nice").body<Report>()
        assertEquals(ReportStatus.RESOLVED, res.status)
        assertEquals(ModerationAction.WARN_USER, res.resolutionAction)
        // warning has no other side effects
        assertEquals("ACTIVE", env.listingStatus(l))
        assertEquals(0, env.count("SELECT count(*) FROM users WHERE suspended"))
        val log = client.get(ApiRoutes.MOD_LOG) { auth(mod) }.body<Page<ModerationLogEntry>>().items.single()
        assertEquals(ModerationAction.WARN_USER, log.action)
        assertEquals(ReportTargetType.USER, log.targetType)
        assertEquals(seller.id.toString(), log.targetId)
    }

    // ------------------------------------------------------------ direct actions

    @Test
    fun directSuspendAndUnsuspend() = apiTest { env ->
        val mod = env.user("mod", role = UserRole.MODERATOR); val admin = env.user("adm", role = UserRole.ADMIN)
        val mod2 = env.user("mod2", role = UserRole.MODERATOR); val admin2 = env.user("adm2", role = UserRole.ADMIN)
        val victim = env.user("victim"); val plain = env.user("plain"); val faraway = env.user("far", domain = "other.edu")
        env.db.tx { c -> c.update("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) VALUES (?, ?, 'x', now() + interval '1 day')", UUID.randomUUID(), faraway.id) }

        suspend fun suspend(by: TestEnv.TestUser, id: UUID, reason: String = "spam", days: Int? = null) =
            client.post(ApiRoutes.modUserSuspend(id.toString())) { auth(by); json(SuspendUserRequest(reason, days)) }

        suspend(plain, victim.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        suspend(mod, victim.id, reason = "  ").assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        suspend(mod, victim.id, days = -1).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        suspend(mod, UUID.randomUUID()).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        suspend(mod, mod.id).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        suspend(mod, mod2.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        suspend(mod, admin.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        suspend(admin, admin2.id).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals(0, env.count("SELECT count(*) FROM moderation_actions"))

        assertEquals(OkResponse(), suspend(mod, faraway.id, "harassing", 30).body())  // any campus
        assertEquals(0, env.count("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", faraway.id))
        assertEquals(HttpStatusCode.Forbidden, client.get(ApiRoutes.MOD_LOG) { auth(faraway) }.status)
        assertEquals(HttpStatusCode.OK, suspend(admin, mod2.id, "abuse").status)  // admin may suspend moderators

        // unsuspend
        client.post(ApiRoutes.modUserUnsuspend(victim.id.toString())) { auth(mod); json(ModerationNoteRequest("oops")) }
            .assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        client.post(ApiRoutes.modUserUnsuspend(mod2.id.toString())) { auth(mod) }.assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.modUserUnsuspend(faraway.id.toString())) { auth(mod); json(ModerationNoteRequest("appealed")) }.status)
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.modUserUnsuspend(mod2.id.toString())) { auth(admin) }.status)  // no body at all
        assertEquals(0, env.count("SELECT count(*) FROM users WHERE suspended"))
        client.post(ApiRoutes.modUserUnsuspend(UUID.randomUUID().toString())) { auth(mod) }.assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)

        val log = client.get(ApiRoutes.MOD_LOG) { auth(admin) }.body<Page<ModerationLogEntry>>().items
        assertEquals(
            listOf(ModerationAction.UNSUSPEND_USER, ModerationAction.UNSUSPEND_USER, ModerationAction.SUSPEND_USER, ModerationAction.SUSPEND_USER),
            log.map { it.action },
        )
        assertEquals("appealed", log[1].note)
        assertNull(log[0].reportId)
    }

    @Test
    fun directRemoveAndRestoreListing() = apiTest { env ->
        val seller = env.user("seller"); val mod = env.user("mod", role = UserRole.MODERATOR)
        val active = env.listing(seller); val reserved = env.listing(seller, status = ListingStatus.RESERVED)
        val sold = env.listing(seller, status = ListingStatus.SOLD); val deleted = env.listing(seller, status = ListingStatus.DELETED)
        suspend fun remove(by: TestEnv.TestUser, id: UUID) = client.post(ApiRoutes.modListingRemove(id.toString())) { auth(by); json(ModerationNoteRequest("bad")) }
        suspend fun restore(by: TestEnv.TestUser, id: UUID) = client.post(ApiRoutes.modListingRestore(id.toString())) { auth(by); json(ModerationNoteRequest("ok")) }

        remove(seller, active).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        remove(mod, UUID.randomUUID()).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        remove(mod, reserved).assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        remove(mod, sold).assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        remove(mod, deleted).assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        restore(mod, active).assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        restore(mod, UUID.randomUUID()).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        restore(seller, active).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals(0, env.count("SELECT count(*) FROM moderation_actions"))

        assertEquals(OkResponse(), remove(mod, active).body())
        assertEquals("REMOVED", env.listingStatus(active))
        remove(mod, active).assertApiError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        assertEquals(OkResponse(), restore(mod, active).body())
        assertEquals("ACTIVE", env.listingStatus(active))
        val log = client.get(ApiRoutes.MOD_LOG) { auth(mod) }.body<Page<ModerationLogEntry>>().items
        assertEquals(listOf(ModerationAction.RESTORE_LISTING, ModerationAction.REMOVE_LISTING), log.map { it.action })
        assertTrue(log.all { it.targetType == ReportTargetType.LISTING && it.targetId == active.toString() })
    }

    @Test
    fun logPaginatesNewestFirst() = apiTest { env ->
        val mod = env.user("mod", role = UserRole.MODERATOR); val seller = env.user("seller")
        val l = env.listing(seller)
        repeat(3) {
            client.post(ApiRoutes.modListingRemove(l.toString())) { auth(mod) }
            client.post(ApiRoutes.modListingRestore(l.toString())) { auth(mod) }
        }
        assertEquals(6, env.count("SELECT count(*) FROM moderation_actions"))
        val all = mutableListOf<ModerationLogEntry>()
        var cursor: String? = null
        var pages = 0
        do {
            val p = client.get(ApiRoutes.MOD_LOG + "?limit=4" + (cursor?.let { "&cursor=$it" } ?: "")) { auth(mod) }.body<Page<ModerationLogEntry>>()
            all += p.items; cursor = p.nextCursor; pages++
        } while (cursor != null)
        assertEquals(2, pages)
        assertEquals(6, all.map { it.id }.toSet().size)
        assertEquals(all.map { it.createdAt }.sortedDescending(), all.map { it.createdAt })
        assertEquals(ModerationAction.RESTORE_LISTING, all.first().action)
        client.get(ApiRoutes.MOD_LOG + "?cursor=bm9wZQ") { auth(mod) }.assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
    }
}
