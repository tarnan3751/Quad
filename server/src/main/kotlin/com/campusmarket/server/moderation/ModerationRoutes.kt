package com.campusmarket.server.moderation

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateReportRequest
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
import com.campusmarket.server.enumParam
import com.campusmarket.server.notFound
import com.campusmarket.server.pageParams
import com.campusmarket.server.receiveBody
import com.campusmarket.server.requireModerator
import com.campusmarket.server.user
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.sql.Connection
import java.util.UUID

/** Entry point for the moderation feature; called once from Application.module. See docs/CONTRACT.md. */
fun Route.moderationRoutes(deps: AppDeps) {
    val svc = ModerationService(deps)

    authenticate(AUTH_JWT) {
        post(ApiRoutes.REPORTS) {
            val req = call.receiveBody<CreateReportRequest>()
            val reporter = call.user
            deps.throttle.acquire(UserThrottle.Action.REPORT, reporter.id, "You've sent a lot of reports recently. Try again later.")
            deps.db.tx { c -> svc.createReport(c, reporter, req) }
            call.respond(OkResponse())
        }

        get(ApiRoutes.MOD_REPORTS) {
            call.requireModerator()
            val status = call.enumParam("status", ReportStatus.OPEN)
            val page = call.pageParams()
            call.respond(deps.db.tx { c -> svc.listReports(c, status, page) })
        }

        get("${ApiRoutes.MOD_REPORTS}/{id}") {
            call.requireModerator()
            val id = call.parameters["id"].asUuid("Report")
            call.respond(deps.db.tx { c -> svc.reportDetail(c, id) ?: notFound("Report") })
        }

        post("${ApiRoutes.MOD_REPORTS}/{id}/resolve") {
            val mod = call.requireModerator()
            val id = call.parameters["id"].asUuid("Report")
            val req = call.receiveBody<ResolveReportRequest>()
            call.respond(deps.db.tx { c -> svc.resolveReport(c, mod, id, req) })
        }

        post("${ApiRoutes.BASE}/mod/users/{id}/suspend") {
            val mod = call.requireModerator()
            val id = call.parameters["id"].asUuid("User")
            val req = call.receiveBody<SuspendUserRequest>()
            deps.db.tx { c -> svc.suspendUserDirect(c, mod, id, req) }
            call.respond(OkResponse())
        }

        noteAction("${ApiRoutes.BASE}/mod/users/{id}/unsuspend", "User", deps, svc::unsuspendUser)
        noteAction("${ApiRoutes.BASE}/mod/listings/{id}/remove", "Listing", deps, svc::removeListingDirect)
        noteAction("${ApiRoutes.BASE}/mod/listings/{id}/restore", "Listing", deps, svc::restoreListing)

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
            val req = call.receiveBody<SetRoleRequest>()
            call.respond(deps.db.tx { c -> svc.setRole(c, admin, id, req.role) })
        }

        get(ApiRoutes.MOD_LOG) {
            call.requireModerator()
            val page = call.pageParams()
            call.respond(deps.db.tx { c -> svc.log(c, page) })
        }
    }
}

/** A moderator's direct action on [path]'s `{id}` (a [what]) with an optional note; answers `OkResponse`. */
private fun Route.noteAction(
    path: String,
    what: String,
    deps: AppDeps,
    action: (Connection, UserPrincipal, UUID, ModerationNoteRequest) -> Unit,
) = post(path) {
    val mod = call.requireModerator()
    val id = call.parameters["id"].asUuid(what)
    val req = call.receiveNote()
    deps.db.tx { c -> action(c, mod, id, req) }
    call.respond(OkResponse())
}

/** The note body is optional: a request without a content type means an empty note. */
private suspend fun ApplicationCall.receiveNote(): ModerationNoteRequest =
    if (request.headers[HttpHeaders.ContentType] == null) ModerationNoteRequest() else receiveBody()

/** Throws 403 unless the caller is an admin (moderators are not enough). */
private fun ApplicationCall.requireAdmin(): UserPrincipal =
    user.also { if (it.role != UserRole.ADMIN) forbidden("Admin access required") }
