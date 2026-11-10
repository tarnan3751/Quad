package com.campusmarket.server.moderation

import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Limits
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.ModerationNoteRequest
import com.campusmarket.core.Page
import com.campusmarket.core.Report
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.CreateReportRequest
import com.campusmarket.core.ResolveReportRequest
import com.campusmarket.core.StaffMember
import com.campusmarket.core.SuspendUserRequest
import com.campusmarket.core.UserRole
import com.campusmarket.server.AppDeps
import com.campusmarket.server.KeysetCursor
import com.campusmarket.server.PageParams
import com.campusmarket.server.Sql
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.asUuid
import com.campusmarket.server.badRequest
import com.campusmarket.server.conflict
import com.campusmarket.server.forbidden
import com.campusmarket.server.invalidField
import com.campusmarket.server.keyset
import com.campusmarket.server.loadListingSummaries
import com.campusmarket.server.loadUsersPublic
import com.campusmarket.server.millis
import com.campusmarket.server.millisOrNull
import com.campusmarket.server.notFound
import com.campusmarket.server.page
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import com.campusmarket.server.uuid
import com.campusmarket.server.uuidOrNull
import com.campusmarket.server.withoutControlChars
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val MAX_SUMMARY = 200
private const val SEARCH_MAX = 20
/** Users read in name order before the search turns to its index. */
private const val SEARCH_WALK = 1000
private const val SEARCH_MIN_CHARS = 2
private const val SEARCH_MAX_CHARS = 100
private const val MAX_SUSPEND_DAYS = 3650
/**
 * What a suspended user is told (`users.suspension_reason` is echoed in every 403 `ACCOUNT_SUSPENDED`). Never the
 * moderator's own text: the moderation app presents it as an internal note / log entry, and it can name the reporter.
 * That text is kept in the moderation log and on the report.
 */
private const val USER_FACING_SUSPEND_REASON = "Violation of community guidelines"

/** Business logic of reports and moderation. Every function runs inside the caller's transaction. */
internal class ModerationService(private val deps: AppDeps) {
    private val config get() = deps.config

    // ---------------------------------------------------------------- reporting

    private data class TargetInfo(val userId: UUID?, val summary: String)

    fun createReport(c: Connection, reporter: UserPrincipal, req: CreateReportRequest) {
        val targetId = req.targetId.asUuid("Target")
        val details = req.details.withoutControlChars().trim()
        if (details.length > Limits.REPORT_DETAILS_MAX) {
            invalidField("details", "Details must be at most ${Limits.REPORT_DETAILS_MAX} characters")
        }
        val target = when (req.targetType) {
            ReportTargetType.LISTING -> resolveListingTarget(c, reporter, targetId)
            ReportTargetType.USER -> resolveUserTarget(c, reporter, targetId)
            ReportTargetType.MESSAGE -> resolveMessageTarget(c, reporter, targetId)
            // Log-only target type (dispute resolutions); problems with an order are reported as a dispute on it.
            ReportTargetType.ORDER -> invalidField("targetType", "Orders can't be reported. Open the order and report a problem instead.")
        }
        // Repeat reports by the same reporter on the same target while one is open are a silent no-op.
        c.update(
            """INSERT INTO reports (id, reporter_id, target_type, target_id, target_user_id, reason, details, target_summary)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT DO NOTHING""",
            UUID.randomUUID(), reporter.id, req.targetType, targetId, target.userId, req.reason, details, target.summary,
        )
    }

    private fun resolveListingTarget(c: Connection, reporter: UserPrincipal, id: UUID): TargetInfo {
        data class Row(val seller: UUID, val title: String)
        val row = c.queryOne(
            "SELECT seller_id, title FROM listings WHERE id = ? AND campus_domain = ? AND status NOT IN ('REMOVED', 'DELETED')",
            id, reporter.campusDomain,
        ) { Row(it.uuid("seller_id"), it.getString("title")) } ?: notFound("Listing")
        if (row.seller == reporter.id) badRequest("You can't report your own listing")
        return TargetInfo(row.seller, row.title.take(MAX_SUMMARY))
    }

    private fun resolveUserTarget(c: Connection, reporter: UserPrincipal, id: UUID): TargetInfo {
        val name = c.queryOne(
            "SELECT display_name FROM users WHERE id = ? AND campus_domain = ? AND deleted_at IS NULL",
            id, reporter.campusDomain,
        ) { it.getString("display_name") } ?: notFound("User")
        if (id == reporter.id) badRequest("You can't report yourself")
        return TargetInfo(id, name.take(MAX_SUMMARY))
    }

    private fun resolveMessageTarget(c: Connection, reporter: UserPrincipal, id: UUID): TargetInfo {
        data class Row(val sender: UUID, val body: String)
        val row = c.queryOne(
            """SELECT m.sender_id, m.body
               FROM messages m
               JOIN conversations cv ON cv.id = m.conversation_id
               JOIN listings l ON l.id = cv.listing_id
               WHERE m.id = ? AND (cv.buyer_id = ? OR cv.seller_id = ?) AND l.campus_domain = ?""",
            id, reporter.id, reporter.id, reporter.campusDomain,
        ) { Row(it.uuid("sender_id"), it.getString("body")) } ?: notFound("Message")
        if (row.sender == reporter.id) badRequest("You can't report your own message")
        return TargetInfo(row.sender, row.body.take(MAX_SUMMARY))
    }

    // ---------------------------------------------------------------- queue & detail

    private class ReportRow(
        val id: UUID,
        val reporterId: UUID,
        val targetType: ReportTargetType,
        val targetId: UUID,
        val targetUserId: UUID?,
        val reason: ReportReason,
        val details: String,
        val status: ReportStatus,
        val summary: String,
        val resolvedBy: UUID?,
        val action: ModerationAction?,
        val note: String?,
        val createdAt: Long,
        val resolvedAt: Long?,
        val listingId: UUID?,
        val openAgainstUser: Int,
        val key: KeysetCursor,
    )

    private val reportSelect = """
        SELECT r.id, r.reporter_id, r.target_type, r.target_id, r.target_user_id, r.reason, r.details, r.status,
               r.target_summary, r.resolved_by, r.resolution_action, r.resolution_note, r.created_at, r.resolved_at,
               CASE r.target_type WHEN 'LISTING' THEN r.target_id
                                  WHEN 'MESSAGE' THEN (SELECT cv.listing_id FROM messages m JOIN conversations cv ON cv.id = m.conversation_id WHERE m.id = r.target_id)
               END AS listing_id,
               CASE WHEN r.target_user_id IS NULL THEN 0
                    ELSE (SELECT count(*) FROM reports x WHERE x.target_user_id = r.target_user_id AND x.status = 'OPEN') END AS open_against
        FROM reports r"""

    private fun ResultSet.toReportRow() = ReportRow(
        id = uuid("id"),
        reporterId = uuid("reporter_id"),
        targetType = ReportTargetType.valueOf(getString("target_type")),
        targetId = uuid("target_id"),
        targetUserId = uuidOrNull("target_user_id"),
        reason = ReportReason.valueOf(getString("reason")),
        details = getString("details"),
        status = ReportStatus.valueOf(getString("status")),
        summary = getString("target_summary"),
        resolvedBy = uuidOrNull("resolved_by"),
        action = getString("resolution_action")?.let(ModerationAction::valueOf),
        note = getString("resolution_note"),
        createdAt = millis("created_at"),
        resolvedAt = millisOrNull("resolved_at"),
        listingId = uuidOrNull("listing_id"),
        openAgainstUser = getInt("open_against"),
        key = KeysetCursor(getTimestamp("created_at").toInstant(), uuid("id")),
    )

    private fun render(c: Connection, rows: List<ReportRow>): List<Report> {
        val userIds = rows.flatMap { listOfNotNull(it.reporterId, it.targetUserId, it.resolvedBy) }
        val users = c.loadUsersPublic(config, userIds)
        val listings = c.loadListingSummaries(config, rows.mapNotNull { it.listingId })
        return rows.map { r ->
            Report(
                id = r.id.toString(),
                reporter = users.getValue(r.reporterId),
                targetType = r.targetType,
                targetId = r.targetId.toString(),
                reason = r.reason,
                details = r.details,
                status = r.status,
                targetSummary = r.summary,
                targetUser = r.targetUserId?.let(users::get),
                targetListing = r.listingId?.let(listings::get),
                openReportsAgainstTargetUser = r.openAgainstUser,
                resolutionAction = r.action,
                resolutionNote = r.note,
                resolvedBy = r.resolvedBy?.let(users::get),
                createdAt = r.createdAt,
                resolvedAt = r.resolvedAt,
            )
        }
    }

    fun listReports(c: Connection, status: ReportStatus, page: PageParams): Page<Report> {
        val sql = Sql("$reportSelect WHERE r.status = ?", status)
            .keysetPage("r.created_at", "r.id", KeysetCursor.decode(page.cursor), page.limit, ascending = status == ReportStatus.OPEN)
        val rows = c.page(sql, page.limit, { it.key }) { it.toReportRow() }
        return Page(render(c, rows.items), rows.nextCursor)
    }

    fun reportDetail(c: Connection, id: UUID): Report? =
        c.queryOne("$reportSelect WHERE r.id = ?", id) { it.toReportRow() }?.let { render(c, listOf(it)).first() }

    // ---------------------------------------------------------------- resolve

    fun resolveReport(c: Connection, mod: UserPrincipal, reportId: UUID, req: ResolveReportRequest): Report {
        val note = validNote(req.note)
        when (req.action) {
            ModerationAction.DISMISS, ModerationAction.REMOVE_LISTING, ModerationAction.SUSPEND_USER, ModerationAction.WARN_USER -> {}
            else -> invalidField("action", "${req.action} cannot be used to resolve a report")
        }
        val days = req.suspendDays
        if (days != null && (req.action != ModerationAction.SUSPEND_USER)) invalidField("suspendDays", "suspendDays only applies to SUSPEND_USER")
        if (days != null) validDays(days, "suspendDays")

        data class Locked(val type: ReportTargetType, val targetId: UUID, val userId: UUID?, val status: ReportStatus)
        val rep = c.queryOne(
            "SELECT target_type, target_id, target_user_id, status FROM reports WHERE id = ? FOR UPDATE", reportId,
        ) {
            Locked(
                ReportTargetType.valueOf(it.getString("target_type")), it.uuid("target_id"),
                it.uuidOrNull("target_user_id"), ReportStatus.valueOf(it.getString("status")),
            )
        } ?: notFound("Report")
        if (rep.status != ReportStatus.OPEN) conflict("Report is already ${rep.status.name.lowercase()}")

        var logType = rep.type
        var logTarget = rep.targetId
        when (req.action) {
            ModerationAction.DISMISS -> {}
            ModerationAction.REMOVE_LISTING -> {
                if (rep.type != ReportTargetType.LISTING) invalidField("action", "REMOVE_LISTING only applies to listing reports")
                removeListing(c, rep.targetId, allowAlreadyGone = true)
            }
            ModerationAction.SUSPEND_USER -> {
                val user = rep.userId ?: invalidField("action", "This report has no responsible user to suspend")
                suspend(c, mod, user, days)
                logType = ReportTargetType.USER
                logTarget = user
            }
            ModerationAction.WARN_USER -> {
                val user = rep.userId ?: invalidField("action", "This report has no responsible user to warn")
                lockTarget(c, user)
                logType = ReportTargetType.USER
                logTarget = user
            }
            else -> error("unreachable")
        }

        val newStatus = if (req.action == ModerationAction.DISMISS) ReportStatus.DISMISSED else ReportStatus.RESOLVED
        // The report itself plus every other OPEN report on the same target get the same outcome.
        c.update(
            """UPDATE reports SET status = ?, resolved_by = ?, resolution_action = ?, resolution_note = ?, resolved_at = now()
               WHERE status = 'OPEN' AND target_type = ? AND target_id = ?""",
            newStatus, mod.id, req.action, note, rep.type, rep.targetId,
        )
        audit(c, mod, req.action, logType, logTarget, note, reportId)
        return reportDetail(c, reportId)!!
    }

    // ---------------------------------------------------------------- direct actions

    fun suspendUserDirect(c: Connection, mod: UserPrincipal, userId: UUID, req: SuspendUserRequest) {
        val reason = req.reason.withoutControlChars().trim()
        if (reason.isEmpty()) invalidField("reason", "A reason is required")
        if (reason.length > Limits.REPORT_DETAILS_MAX) invalidField("reason", "Reason must be at most ${Limits.REPORT_DETAILS_MAX} characters")
        req.days?.let { validDays(it, "days") }
        suspend(c, mod, userId, req.days)
        audit(c, mod, ModerationAction.SUSPEND_USER, ReportTargetType.USER, userId, reason, null)
    }

    fun unsuspendUser(c: Connection, mod: UserPrincipal, userId: UUID, req: ModerationNoteRequest) {
        val note = validNote(req.note)
        val target = lockTarget(c, userId)
        checkHierarchy(mod, target)
        if (!target.suspended) conflict("User is not suspended")
        c.update("UPDATE users SET suspended = false, suspended_until = NULL, suspension_reason = NULL WHERE id = ?", userId)
        audit(c, mod, ModerationAction.UNSUSPEND_USER, ReportTargetType.USER, userId, note, null)
    }

    fun removeListingDirect(c: Connection, mod: UserPrincipal, listingId: UUID, req: ModerationNoteRequest) {
        val note = validNote(req.note)
        removeListing(c, listingId, allowAlreadyGone = false)
        audit(c, mod, ModerationAction.REMOVE_LISTING, ReportTargetType.LISTING, listingId, note, null)
    }

    fun restoreListing(c: Connection, mod: UserPrincipal, listingId: UUID, req: ModerationNoteRequest) {
        val note = validNote(req.note)
        // Not for a seller who has since deleted their account: deletion takes their listings down for good.
        val n = c.update(
            """UPDATE listings l SET status = 'ACTIVE', updated_at = now()
               WHERE l.id = ? AND l.status = 'REMOVED'
                 AND EXISTS (SELECT 1 FROM users s WHERE s.id = l.seller_id AND s.deleted_at IS NULL)""",
            listingId,
        )
        if (n == 0) {
            val status = listingStatus(c, listingId) ?: notFound("Listing")
            if (status == ListingStatus.REMOVED) conflict("The seller's account has been deleted, so this listing can't be restored")
            conflict("Only removed listings can be restored")
        }
        audit(c, mod, ModerationAction.RESTORE_LISTING, ReportTargetType.LISTING, listingId, note, null)
    }

    // ---------------------------------------------------------------- staff management

    private class StaffRow(val id: UUID, val role: UserRole, val email: String)

    private fun ResultSet.toStaffRow() = StaffRow(uuid("id"), UserRole.valueOf(getString("role")), getString("email"))

    private fun renderStaff(c: Connection, rows: List<StaffRow>): List<StaffMember> {
        val users = c.loadUsersPublic(config, rows.map { it.id })
        return rows.map { StaffMember(users.getValue(it.id), it.role, it.email) }
    }

    /** Admins first, then moderators; each by name. */
    fun listStaff(c: Connection): List<StaffMember> = renderStaff(
        c,
        c.query(
            """SELECT id, role, email FROM users
               WHERE role IN ('ADMIN', 'MODERATOR') AND email_verified AND deleted_at IS NULL
               ORDER BY (role = 'ADMIN') DESC, lower(display_name), id""",
        ) { it.toStaffRow() },
    )

    /** Case-insensitive substring match on email or display name; verified, non-deleted users only; max [SEARCH_MAX]. */
    fun searchUsers(c: Connection, raw: String): List<StaffMember> {
        val q = raw.trim()
        if (q.length < SEARCH_MIN_CHARS) return emptyList()
        if (q.length > SEARCH_MAX_CHARS) invalidField("q", "Search text must be at most $SEARCH_MAX_CHARS characters")
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        // Same two steps as the listing search: a text many users match fills the page from the first users in name
        // order; otherwise its matches come from the gram index, one lookup per field (an OR of the two would have
        // every candidate row's grams computed again).
        var rows = c.query(
            """SELECT id, role, email
               FROM (SELECT id, role, email, display_name, lower(display_name) AS name_key FROM users
                     WHERE email_verified AND deleted_at IS NULL ORDER BY lower(display_name), id LIMIT $SEARCH_WALK) u
               WHERE email ILIKE ? OR display_name ILIKE ?
               ORDER BY name_key, id LIMIT ?""",
            like, like, SEARCH_MAX,
        ) { it.toStaffRow() }
        if (rows.size < SEARCH_MAX) {
            rows = c.query(
                """SELECT id, role, email FROM users
                   WHERE id IN (SELECT id FROM users
                                WHERE email_verified AND deleted_at IS NULL
                                  AND search_grams('', email) @> search_pattern_grams('', ?) AND email ILIKE ?
                                UNION ALL
                                SELECT id FROM users
                                WHERE email_verified AND deleted_at IS NULL
                                  AND search_grams('', display_name) @> search_pattern_grams('', ?) AND display_name ILIKE ?)
                   ORDER BY lower(display_name), id LIMIT ?""",
                q, like, q, like, SEARCH_MAX,
            ) { it.toStaffRow() }
        }
        return renderStaff(c, rows)
    }

    /**
     * Admin-only USER <-> MODERATOR change. Not on yourself, never on/to an ADMIN (admins come only from ADMIN_EMAILS),
     * target must be verified and not deleted. Setting the role a user already has is a no-op (no audit row).
     */
    fun setRole(c: Connection, admin: UserPrincipal, userId: UUID, newRole: UserRole): StaffMember {
        if (newRole == UserRole.ADMIN) invalidField("role", "Admins are configured on the server and can't be assigned here")
        if (userId == admin.id) badRequest("You can't change your own role")
        val row = c.queryOne(
            "SELECT id, role, email FROM users WHERE id = ? AND email_verified AND deleted_at IS NULL FOR UPDATE", userId,
        ) { it.toStaffRow() } ?: notFound("User")
        if (row.role == UserRole.ADMIN) forbidden("An admin's role can't be changed")
        if (row.role != newRole) {
            c.update("UPDATE users SET role = ? WHERE id = ?", newRole, userId)
            audit(c, admin, ModerationAction.CHANGE_ROLE, ReportTargetType.USER, userId, "${row.role.name} -> ${newRole.name}", null)
        }
        return renderStaff(c, listOf(StaffRow(userId, newRole, row.email))).first()
    }

    // ---------------------------------------------------------------- audit log

    fun log(c: Connection, page: PageParams): Page<ModerationLogEntry> {
        class Row(val id: UUID, val mod: UUID, val action: ModerationAction, val type: ReportTargetType, val target: UUID,
                  val note: String, val report: UUID?, val at: Long, val key: KeysetCursor)
        val sql = Sql("SELECT id, created_at, moderator_id, action, target_type, target_id, note, report_id FROM moderation_actions WHERE true")
            .keysetPage("created_at", "id", KeysetCursor.decode(page.cursor), page.limit)
        val rows = c.page(sql, page.limit, { it.key }) { rs ->
            Row(rs.uuid("id"), rs.uuid("moderator_id"), ModerationAction.valueOf(rs.getString("action")),
                ReportTargetType.valueOf(rs.getString("target_type")), rs.uuid("target_id"), rs.getString("note"),
                rs.uuidOrNull("report_id"), rs.millis("created_at"), rs.keyset())
        }
        val users = c.loadUsersPublic(config, rows.items.map { it.mod })
        val items = rows.items.map {
            ModerationLogEntry(it.id.toString(), users.getValue(it.mod), it.action, it.type, it.target.toString(), it.note, it.report?.toString(), it.at)
        }
        return Page(items, rows.nextCursor)
    }

    // ---------------------------------------------------------------- helpers

    private fun audit(c: Connection, mod: UserPrincipal, action: ModerationAction, type: ReportTargetType, target: UUID, note: String, reportId: UUID?) {
        c.update(
            "INSERT INTO moderation_actions (id, moderator_id, action, target_type, target_id, note, report_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), mod.id, action, type, target, note, reportId,
        )
    }

    private fun validNote(raw: String): String {
        val note = raw.withoutControlChars().trim()
        if (note.length > Limits.REPORT_DETAILS_MAX) invalidField("note", "Note must be at most ${Limits.REPORT_DETAILS_MAX} characters")
        return note
    }

    private fun validDays(days: Int, field: String) {
        if (days < 1 || days > MAX_SUSPEND_DAYS) invalidField(field, "$field must be between 1 and $MAX_SUSPEND_DAYS")
    }

    private data class TargetUser(val role: UserRole, val suspended: Boolean, val id: UUID)

    /** Locks the (non-deleted) user row for the rest of the transaction. */
    private fun lockTarget(c: Connection, id: UUID): TargetUser =
        c.queryOne("SELECT role, suspended FROM users WHERE id = ? AND deleted_at IS NULL FOR UPDATE", id) {
            TargetUser(UserRole.valueOf(it.getString("role")), it.getBoolean("suspended"), id)
        } ?: notFound("User")

    /** Moderators can't act on moderators/admins; admins can act on moderators but not other admins; nobody acts on themselves. */
    private fun checkHierarchy(mod: UserPrincipal, target: TargetUser) {
        if (target.id == mod.id) badRequest("You can't do that to yourself")
        val allowed = when (mod.role) {
            UserRole.ADMIN -> target.role != UserRole.ADMIN
            UserRole.MODERATOR -> target.role == UserRole.USER
            UserRole.USER -> false
        }
        if (!allowed) forbidden("You can't act on a ${target.role.name.lowercase()}")
    }

    private fun suspend(c: Connection, mod: UserPrincipal, userId: UUID, days: Int?) {
        val target = lockTarget(c, userId)
        checkHierarchy(mod, target)
        val until = days?.let { Instant.now().plus(Duration.ofDays(it.toLong())) }
        c.update(
            "UPDATE users SET suspended = true, suspended_until = ?, suspension_reason = ? WHERE id = ?",
            until, USER_FACING_SUSPEND_REASON, userId,
        )
        c.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId)
    }

    private fun listingStatus(c: Connection, id: UUID): ListingStatus? =
        c.queryOne("SELECT status FROM listings WHERE id = ?", id) { ListingStatus.valueOf(it.getString("status")) }

    private fun removeListing(c: Connection, id: UUID, allowAlreadyGone: Boolean) {
        val status = c.queryOne("SELECT status FROM listings WHERE id = ? FOR UPDATE", id) { ListingStatus.valueOf(it.getString("status")) }
            ?: notFound("Listing")
        when (status) {
            ListingStatus.ACTIVE -> c.update("UPDATE listings SET status = 'REMOVED', updated_at = now() WHERE id = ?", id)
            ListingStatus.RESERVED, ListingStatus.SOLD -> conflict("A ${status.name.lowercase()} listing can't be removed")
            ListingStatus.REMOVED, ListingStatus.DELETED ->
                if (!allowAlreadyGone) conflict("Listing is already ${status.name.lowercase()}")
        }
    }
}
