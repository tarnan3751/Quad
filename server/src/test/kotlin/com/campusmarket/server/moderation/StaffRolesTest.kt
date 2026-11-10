package com.campusmarket.server.moderation

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.Page
import com.campusmarket.core.ResolveReportRequest
import com.campusmarket.core.SetRoleRequest
import com.campusmarket.core.StaffMember
import com.campusmarket.core.UserRole
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import com.campusmarket.server.assertApiError

class StaffRolesTest {
    private suspend fun ApiTestScope.setRole(caller: TestEnv.TestUser, target: UUID, role: UserRole): HttpResponse =
        client.put(ApiRoutes.modUserRole(target.toString())) { auth(caller); json(SetRoleRequest(role)) }

    private fun TestEnv.roleOf(u: TestEnv.TestUser): String =
        db.txBlocking { c -> c.queryOne("SELECT role FROM users WHERE id = ?", u.id) { it.getString(1) }!! }

    private fun TestEnv.auditCount(target: UUID): Int = db.txBlocking { c ->
        c.queryOne("SELECT count(*) FROM moderation_actions WHERE action = 'CHANGE_ROLE' AND target_id = ?", target) { it.getInt(1) }!!
    }

    @Test
    fun adminPromotesAndDemotesWithAuditRow() = apiTest { env ->
        val admin = env.user("admin", role = UserRole.ADMIN); val u = env.user("newbie")
        val promoted = setRole(admin, u.id, UserRole.MODERATOR)
        assertEquals(HttpStatusCode.OK, promoted.status)
        val member = promoted.body<StaffMember>()
        assertEquals(UserRole.MODERATOR, member.role)
        assertEquals(u.email, member.email)
        assertEquals(u.id.toString(), member.user.id)
        assertEquals("MODERATOR", env.roleOf(u))

        // the new moderator can use the moderation queue straight away (role is read per request)
        assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.MOD_REPORTS) { auth(u) }.status)

        assertEquals(HttpStatusCode.OK, setRole(admin, u.id, UserRole.USER).status)
        assertEquals("USER", env.roleOf(u))
        assertEquals(HttpStatusCode.Forbidden, client.get(ApiRoutes.MOD_REPORTS) { auth(u) }.status)

        assertEquals(2, env.auditCount(u.id))
        val log = client.get(ApiRoutes.MOD_LOG) { auth(admin) }.body<Page<ModerationLogEntry>>().items
        assertEquals(listOf(ModerationAction.CHANGE_ROLE, ModerationAction.CHANGE_ROLE), log.map { it.action })
        assertEquals(admin.id.toString(), log.first().moderator.id)
        assertEquals("MODERATOR -> USER", log.first().note)
        assertEquals("USER -> MODERATOR", log.last().note)
    }

    @Test
    fun moderatorsAndUsersGet403OnAllStaffEndpoints() = apiTest { env ->
        val mod = env.user("mod", role = UserRole.MODERATOR); val user = env.user("plain"); val target = env.user("target")
        for (caller in listOf(mod, user)) {
            setRole(caller, target.id, UserRole.MODERATOR).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
            client.get(ApiRoutes.MOD_STAFF) { auth(caller) }.assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
            client.get(ApiRoutes.MOD_USER_SEARCH + "?q=target") { auth(caller) }.assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        }
        assertEquals("USER", env.roleOf(target))
        assertEquals(0, env.auditCount(target.id))
    }

    @Test
    fun unauthenticatedIs401() = apiTest { env ->
        val target = env.user("t")
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.MOD_STAFF).status)
        assertEquals(HttpStatusCode.Unauthorized, client.put(ApiRoutes.modUserRole(target.id.toString())) { json(SetRoleRequest(UserRole.MODERATOR)) }.status)
    }

    @Test
    fun cannotChangeOwnRole() = apiTest { env ->
        val admin = env.user("admin", role = UserRole.ADMIN)
        setRole(admin, admin.id, UserRole.USER).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        setRole(admin, admin.id, UserRole.MODERATOR).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertEquals("ADMIN", env.roleOf(admin))
    }

    @Test
    fun cannotAlterAnotherAdminOrAssignAdmin() = apiTest { env ->
        val a1 = env.user("a1", role = UserRole.ADMIN); val a2 = env.user("a2", role = UserRole.ADMIN); val u = env.user("u")
        setRole(a1, a2.id, UserRole.USER).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        setRole(a1, a2.id, UserRole.MODERATOR).assertApiError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        assertEquals("ADMIN", env.roleOf(a2))
        setRole(a1, u.id, UserRole.ADMIN).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertEquals("USER", env.roleOf(u))
        assertEquals(0, env.auditCount(a2.id) + env.auditCount(u.id))
    }

    @Test
    fun targetMustBeVerifiedNonDeletedAndExisting() = apiTest { env ->
        val admin = env.user("admin", role = UserRole.ADMIN)
        val unverified = env.user("unv"); val deleted = env.user("del")
        env.db.txBlocking { c ->
            c.update("UPDATE users SET email_verified = false WHERE id = ?", unverified.id)
            c.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.id)
        }
        setRole(admin, unverified.id, UserRole.MODERATOR).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        setRole(admin, deleted.id, UserRole.MODERATOR).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        setRole(admin, UUID.randomUUID(), UserRole.MODERATOR).assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.put(ApiRoutes.modUserRole("not-a-uuid")) { auth(admin); json(SetRoleRequest(UserRole.MODERATOR)) }
            .assertApiError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        assertEquals("USER", env.roleOf(unverified))
        assertEquals("USER", env.roleOf(deleted))
    }

    @Test
    fun adminCanActAcrossCampuses() = apiTest { env ->
        val admin = env.user("admin", role = UserRole.ADMIN); val other = env.user("other", domain = "tech.edu")
        assertEquals(HttpStatusCode.OK, setRole(admin, other.id, UserRole.MODERATOR).status)
    }

    @Test
    fun settingCurrentRoleIsIdempotentWithoutAudit() = apiTest { env ->
        val admin = env.user("admin", role = UserRole.ADMIN); val mod = env.user("mod", role = UserRole.MODERATOR); val u = env.user("u")
        assertEquals(UserRole.MODERATOR, setRole(admin, mod.id, UserRole.MODERATOR).body<StaffMember>().role)
        assertEquals(UserRole.USER, setRole(admin, u.id, UserRole.USER).body<StaffMember>().role)
        assertEquals(0, env.auditCount(mod.id) + env.auditCount(u.id))
    }

    @Test
    fun changeRoleCannotResolveReports() = apiTest { env ->
        val mod = env.user("mod", role = UserRole.MODERATOR)
        client.post(ApiRoutes.modReportResolve(UUID.randomUUID().toString())) { auth(mod); json(ResolveReportRequest(ModerationAction.CHANGE_ROLE)) }
            .assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
    }

    @Test
    fun staffListIncludesOnlyVerifiedLiveStaffAdminsFirst() = apiTest { env ->
        val admin = env.user("zed", role = UserRole.ADMIN); val m1 = env.user("amy", role = UserRole.MODERATOR)
        val gone = env.user("gone", role = UserRole.MODERATOR); env.user("plain")
        env.db.txBlocking { c -> c.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.id) }
        val staff = client.get(ApiRoutes.MOD_STAFF) { auth(admin) }.body<List<StaffMember>>()
        assertEquals(listOf(admin.id.toString(), m1.id.toString()), staff.map { it.user.id })
        assertEquals(listOf(UserRole.ADMIN, UserRole.MODERATOR), staff.map { it.role })
        assertEquals(listOf(admin.email, m1.email), staff.map { it.email })
    }

    @Test
    fun searchByEmailOrNameCappedAndSafe() = apiTest { env ->
        val admin = env.user("boss", role = UserRole.ADMIN)
        val target = env.user("Quincy Jones")
        val unverified = env.user("Quincy Unverified")
        env.db.txBlocking { c -> c.update("UPDATE users SET email_verified = false WHERE id = ?", unverified.id) }
        suspend fun search(q: String) = client.get(ApiRoutes.MOD_USER_SEARCH + "?q=" + java.net.URLEncoder.encode(q, "UTF-8")) { auth(admin) }

        assertEquals(listOf(target.id.toString()), search("quincy j").body<List<StaffMember>>().map { it.user.id })
        val byEmail = search(target.email.uppercase()).body<List<StaffMember>>()
        assertEquals(listOf(target.id.toString()), byEmail.map { it.user.id })
        assertEquals(target.email, byEmail.single().email)
        assertEquals(UserRole.USER, byEmail.single().role)
        // too short and wildcard characters never match everything
        assertEquals(emptyList(), search("q").body<List<StaffMember>>())
        assertEquals(emptyList(), search("").body<List<StaffMember>>())
        assertEquals(emptyList(), search("%%").body<List<StaffMember>>())
        assertEquals(emptyList(), search("__").body<List<StaffMember>>())

        repeat(25) { env.user("Bulk Person $it") }
        assertEquals(20, search("bulk person").body<List<StaffMember>>().size)
        search("x".repeat(101)).assertApiError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
    }

    @Test
    fun searchFindsRareAndCommonTextAmongManyUsersInNameOrder() = apiTest { env ->
        val admin = env.user("boss", role = UserRole.ADMIN)
        // 1,500 users sort before the rare ones, more than the search reads in name order
        env.db.txBlocking { c ->
            c.update(
                """INSERT INTO users (id, email, password_hash, display_name, campus_domain, email_verified)
                   SELECT gen_random_uuid(), 'crowd' || g || '@state.edu', 'x', 'Crowd ' || lpad(g::text, 4, '0'), 'state.edu', true
                   FROM generate_series(1, 1500) g""",
            )
        }
        val zed = env.user("Zed Quixote")
        val zoe = env.user("Zoe Quixote")
        val byMail = env.user("Yuri", domain = "quixote.edu")
        val deleted = env.user("Zach Quixote")
        env.db.txBlocking { c -> c.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.id) }
        suspend fun search(q: String) = client.get(ApiRoutes.MOD_USER_SEARCH + "?q=" + java.net.URLEncoder.encode(q, "UTF-8")) { auth(admin) }
            .body<List<StaffMember>>()

        assertEquals(listOf(byMail, zed, zoe).map { it.id.toString() }, search("QUIXOTE").map { it.user.id })
        assertEquals(listOf(zoe.id.toString()), search("oe q").map { it.user.id })
        assertEquals(emptyList(), search("zach").map { it.user.id })
        assertEquals(emptyList(), search("%%").map { it.user.id })
        assertEquals((1..20).map { "Crowd %04d".format(it) }, search("crowd").map { it.user.displayName })
        assertEquals(listOf("boss") + (1..19).map { "Crowd %04d".format(it) }, search("@state.edu").map { it.user.displayName })
        assertEquals(listOf("Crowd 1499"), search("crowd 1499").map { it.user.displayName })
    }
}
