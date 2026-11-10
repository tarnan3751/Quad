package com.campusmarket.server.moderation

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateReportRequest
import com.campusmarket.core.Limits
import com.campusmarket.core.ModerationNoteRequest
import com.campusmarket.core.OkResponse
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ResolveReportRequest
import com.campusmarket.core.SetRoleRequest
import com.campusmarket.core.SuspendUserRequest
import com.campusmarket.core.UserRole
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.forbidden
import com.campusmarket.server.AUTH_JWT
import com.campusmarket.server.AppDeps
import com.campusmarket.server.UserThrottle
import com.campusmarket.server.asUuid
import com.campusmarket.server.invalidField
import com.campusmarket.server.notFound
import com.campusmarket.server.requireModerator
import com.campusmarket.server.user
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** Entry point for the moderation feature; called once from Application.module. See docs/CONTRACT.md. */
fun Route.moderationRoutes(deps: AppDeps) {
    val svc = ModerationService(deps)

    authenticate(AUTH_JWT) {
        post(ApiRoutes.REPORTS) {
            val req = call.receive<CreateReportRequest>()
            val reporter = call.user
            deps.throttle.acquire(UserThrottle.Action.REPORT, reporter.id, "You've sent a lot of reports recently. Try again later.")
            deps.db.tx { c -> svc.createReport(c, reporter, req) }
            call.respond(OkResponse())
        }

        get(ApiRoutes.MOD_REPORTS) {
            call.requireModerator()
            val status = when (val s = call.request.queryParameters["status"]) {
                null, "" -> ReportStatus.OPEN
                else -> runCatching { ReportStatus.valueOf(s) }.getOrNull() ?: invalidField("status", "Unknown status '$s'")
            }
            val cursor = call.request.queryParameters["cursor"]
            val limit = call.pageLimit()
            call.respond(deps.db.tx { c -> svc.listReports(c, status, cursor, limit) })
        }

        get("${ApiRoutes.MOD_REPORTS}/{id}") {
            call.requireModerator()
            val id = call.parameters["id"].asUuid("Report")
            call.respond(deps.db.tx { c -> svc.reportDetail(c, id) ?: notFound("Report") })
        }

        post("${ApiRoutes.MOD_REPORTS}/{id}/resolve") {
            val mod = call.requireModerator()
            val id = call.parameters["id"].asUuid("Report")
            val req = call.receive<ResolveReportRequest>()
            call.respond(deps.db.tx { c -> svc.resolveReport(c, mod, id, req) })
        }

        post("${ApiRoutes.BASE}/mod/users/{id}/suspend") {
            val mod = call.requireModerator()
            val id = call.parameters["id"].asUuid("User")
            val req = call.receive<SuspendUserRequest>()
            deps.db.tx { c -> svc.suspendUserDirect(c, mod, id, req) }
            call.respond(OkResponse())
        }

        post("${ApiRoutes.BASE}/mod/users/{id}/unsuspend") {
            val mod = call.requireModerator()
            val id = call.parameters["id"].asUuid("User")
            val req = call.receiveNote()
            deps.db.tx { c -> svc.unsuspendUser(c, mod, id, req) }
            call.respond(OkResponse())
        }

        post("${ApiRoutes.BASE}/mod/listings/{id}/remove") {
            val mod = call.requireModerator()
            val id = call.parameters["id"].asUuid("Listing")
            val req = call.receiveNote()
            deps.db.tx { c -> svc.removeListingDirect(c, mod, id, req) }
            call.respond(OkResponse())
        }

        post("${ApiRoutes.BASE}/mod/listings/{id}/restore") {
            val mod = call.requireModerator()
            val id = call.parameters["id"].asUuid("Listing")
            val req = call.receiveNote()
            deps.db.tx { c -> svc.restoreListing(c, mod, id, req) }
            call.respond(OkResponse())
        }

        // ---- staff management (admins only)

        get(ApiRoutes.MOD_STAFF) {
            call.requireAdmin()
            call.respond(deps.db.tx { c -> svc.listStaff(c) })
        }

        get(ApiRoutes.MOD_USER_SEARCH) {
            call.requireAdmin()
            val q = call.request.queryParameters["q"].orEmpty()
            call.respond(deps.db.tx { c -> svc.searchUsers(c, q) })
        }

        put("${ApiRoutes.BASE}/mod/users/{id}/role") {
            val admin = call.requireAdmin()
            val id = call.parameters["id"].asUuid("User")
            val req = call.receive<SetRoleRequest>()
            call.respond(deps.db.tx { c -> svc.setRole(c, admin, id, req.role) })
        }

        get(ApiRoutes.MOD_LOG) {
            call.requireModerator()
            val cursor = call.request.queryParameters["cursor"]
            val limit = call.pageLimit()
            call.respond(deps.db.tx { c -> svc.log(c, cursor, limit) })
        }
    }
}

private fun ApplicationCall.pageLimit(): Int {
    val raw = request.queryParameters["limit"] ?: return Limits.PAGE_DEFAULT
    val n = raw.toIntOrNull() ?: invalidField("limit", "limit must be a number")
    return n.coerceIn(1, Limits.PAGE_MAX)
}

/** The note body is optional: a request without a content type means an empty note. */
private suspend fun ApplicationCall.receiveNote(): ModerationNoteRequest =
    if (request.headers[HttpHeaders.ContentType] == null) ModerationNoteRequest() else receive()

/** Throws 403 unless the caller is an admin (moderators are not enough). */
private fun ApplicationCall.requireAdmin(): UserPrincipal =
    user.also { if (it.role != UserRole.ADMIN) forbidden("Admin access required") }
