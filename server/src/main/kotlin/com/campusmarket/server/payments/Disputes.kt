package com.campusmarket.server.payments

import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.Limits
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.OrderDispute
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.ResolveDisputeRequest
import com.campusmarket.server.AppConfig
import com.campusmarket.server.AppDeps
import com.campusmarket.server.KeysetCursor
import com.campusmarket.server.PageParams
import com.campusmarket.server.Sql
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.asUuid
import com.campusmarket.server.forbidden
import com.campusmarket.server.instant
import com.campusmarket.server.instantOrNull
import com.campusmarket.server.invalidField
import com.campusmarket.server.loadListingSummaries
import com.campusmarket.server.loadUsersPublic
import com.campusmarket.server.notFound
import com.campusmarket.server.pageOfIds
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import com.campusmarket.server.uuid
import com.campusmarket.server.uuidOrNull
import com.campusmarket.server.withoutControlChars
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/*
 * Disputes: rows, their rendering for the order's parties and for moderators, and the moderators' queue and
 * resolution (which moves money, so it lives with the order state machine rather than in the moderation feature;
 * its audit row goes into `moderation_actions` like every other moderator action).
 */

internal data class DisputeRow(
    val id: UUID,
    val orderId: UUID,
    val openedBy: UUID?,
    val reason: DisputeReason,
    val details: String,
    val status: DisputeStatus,
    val outcome: DisputeOutcome?,
    val resolvedBy: UUID?,
    val resolutionNote: String?,
    val openedAt: Instant,
    val closedAt: Instant?,
) {
    /** As both parties of the order see it: no moderator identity or note. */
    fun toDto() = OrderDispute(
        id = id.toString(),
        reason = reason,
        details = details,
        status = status,
        outcome = outcome,
        openedBySystem = openedBy == null,
        openedAt = openedAt.toEpochMilli(),
        closedAt = closedAt?.toEpochMilli(),
    )
}

private const val DISPUTE_COLUMNS =
    "d.id, d.order_id, d.opened_by, d.reason, d.details, d.status, d.outcome, d.resolved_by, d.resolution_note, d.opened_at, d.closed_at"

private fun ResultSet.toDisputeRow() = DisputeRow(
    id = uuid("id"),
    orderId = uuid("order_id"),
    openedBy = uuidOrNull("opened_by"),
    reason = DisputeReason.valueOf(getString("reason")),
    details = getString("details"),
    status = DisputeStatus.valueOf(getString("status")),
    outcome = getString("outcome")?.let(DisputeOutcome::valueOf),
    resolvedBy = uuidOrNull("resolved_by"),
    resolutionNote = getString("resolution_note"),
    openedAt = instant("opened_at"),
    closedAt = instantOrNull("closed_at"),
)

/** The most recent dispute of each of [orderIds] that has one. */
internal fun Connection.latestDisputes(orderIds: Collection<UUID>): Map<UUID, DisputeRow> {
    if (orderIds.isEmpty()) return emptyMap()
    return query(
        """SELECT DISTINCT ON (d.order_id) $DISPUTE_COLUMNS FROM order_disputes d
           WHERE d.order_id = ANY(?) ORDER BY d.order_id, d.opened_at DESC, d.id DESC""",
        orderIds.distinct(),
    ) { it.toDisputeRow() }.associateBy { it.orderId }
}

internal fun Connection.openDisputeOf(orderId: UUID): DisputeRow? =
    queryOne("SELECT $DISPUTE_COLUMNS FROM order_disputes d WHERE d.order_id = ? AND d.status = 'OPEN'", orderId) { it.toDisputeRow() }

/** Moderator views of disputes [ids], in that order. Suspension flags are as of [now]. */
internal fun Connection.loadDisputeCases(config: AppConfig, ids: List<UUID>, now: Instant): List<DisputeCase> {
    if (ids.isEmpty()) return emptyList()
    val disputes = query("SELECT $DISPUTE_COLUMNS FROM order_disputes d WHERE d.id = ANY(?)", ids) { it.toDisputeRow() }.associateBy { it.id }
    val orders = query("SELECT $ORDER_COLUMNS FROM orders o WHERE o.id = ANY(?)", disputes.values.map { it.orderId }.distinct()) { it.toOrderRow() }
        .associateBy { it.id }
    val userIds = orders.values.flatMap { listOf(it.buyerId, it.sellerId) } + disputes.values.mapNotNull { it.resolvedBy }
    val users = loadUsersPublic(config, userIds)
    val suspended = query(
        "SELECT id FROM users WHERE id = ANY(?) AND suspended AND (suspended_until IS NULL OR suspended_until > ?)",
        userIds.distinct().ifEmpty { listOf(UUID(0, 0)) }, now,
    ) { it.uuid("id") }.toSet()
    val listings = loadListingSummaries(config, orders.values.map { it.listingId })
    return ids.mapNotNull { id ->
        val d = disputes[id] ?: return@mapNotNull null
        val o = orders.getValue(d.orderId)
        DisputeCase(
            orderId = o.id.toString(),
            listing = listings.getValue(o.listingId),
            buyer = users.getValue(o.buyerId),
            seller = users.getValue(o.sellerId),
            amountCents = o.amountCents,
            feeCents = o.feeCents,
            currency = o.currency,
            orderStatus = o.status,
            dispute = d.toDto(),
            orderCreatedAt = o.createdAt.toEpochMilli(),
            paidAt = o.paidAt?.toEpochMilli(),
            buyerSuspended = o.buyerId in suspended,
            sellerSuspended = o.sellerId in suspended,
            resolvedBy = d.resolvedBy?.let(users::get),
            resolutionNote = d.resolutionNote,
        )
    }
}

/** Moderators' dispute queue and resolutions. Callers check `requireModerator()`. */
internal class DisputeService(private val deps: AppDeps, private val orders: OrderService) {
    private val db = deps.db
    private val config = deps.config

    /** OPEN: oldest first (the queue). CLOSED: most recently closed first. */
    suspend fun list(status: DisputeStatus, page: PageParams): Page<DisputeCase> {
        val column = if (status == DisputeStatus.OPEN) "d.opened_at" else "d.closed_at"
        val sql = Sql("SELECT d.id, $column FROM order_disputes d WHERE d.status = ?", status)
            .keysetPage(column, "d.id", KeysetCursor.decode(page.cursor), page.limit, ascending = status == DisputeStatus.OPEN)
        val now = orders.now()
        return db.tx { c -> c.pageOfIds(sql, page.limit) { ids -> loadDisputeCases(config, ids, now) } }
    }

    /** The order's most recent dispute (open or closed). */
    suspend fun case(orderIdRaw: String): DisputeCase {
        val orderId = orderIdRaw.asUuid("Dispute")
        val now = orders.now()
        return db.tx { c ->
            val latest = c.latestDisputes(listOf(orderId))[orderId] ?: notFound("Dispute")
            c.loadDisputeCases(config, listOf(latest.id), now).single()
        }
    }

    /**
     * Refunds the buyer (→ REFUNDED) or releases the payment to the seller (→ COMPLETED) of a DISPUTED order, with
     * the same claim-based money movement as the parties' own actions, and writes a `RESOLVE_DISPUTE` row to the
     * moderation log in the same transaction as the outcome. Moderators can't resolve disputes on their own orders.
     */
    suspend fun resolve(mod: UserPrincipal, orderIdRaw: String, req: ResolveDisputeRequest): DisputeCase {
        val orderId = orderIdRaw.asUuid("Dispute")
        val note = req.note.withoutControlChars().trim()
        if (note.isEmpty()) invalidField("note", "Add a note explaining the decision")
        if (note.length > Limits.DISPUTE_NOTE_MAX) invalidField("note", "Note must be at most ${Limits.DISPUTE_NOTE_MAX} characters")
        db.tx { c ->
            val row = c.findOrder(orderId) ?: notFound("Dispute")
            if (c.latestDisputes(listOf(orderId)).isEmpty()) notFound("Dispute")
            if (mod.id == row.buyerId || mod.id == row.sellerId) forbidden("You can't resolve a dispute on your own order")
            if (row.status != OrderStatus.DISPUTED) invalidState("This dispute is no longer open")
        }
        val notOpen: () -> Nothing = { invalidState("This dispute is no longer open") }
        val (outcome, finalStatus, logNote) = when (req.resolution) {
            DisputeResolution.REFUND_BUYER -> Triple(DisputeOutcome.REFUNDED_BY_MODERATOR, OrderStatus.REFUNDED, "Refunded the buyer: $note")
            DisputeResolution.RELEASE_TO_SELLER -> Triple(DisputeOutcome.RELEASED_BY_MODERATOR, OrderStatus.COMPLETED, "Released the payment to the seller: $note")
        }
        val closure = DisputeClosure(outcome, mod.id, note)
        val audit: (Connection) -> Unit = { c -> c.auditDisputeResolution(mod.id, orderId, logNote) }
        val applied = when (req.resolution) {
            DisputeResolution.REFUND_BUYER -> orders.refundBuyer(orderId, setOf(OrderStatus.DISPUTED), closure, audit, notOpen)
            DisputeResolution.RELEASE_TO_SELLER -> orders.releaseToSeller(orderId, setOf(OrderStatus.DISPUTED), closure, audit, notOpen)
        }
        if (!applied) {
            db.tx { c ->
                val now = c.findOrder(orderId)!!
                if (now.status != finalStatus) invalidState("This dispute is no longer open")
                // Our own refund's `charge.refunded` webhook can apply it a moment before we do; it closes the dispute
                // with the generic outcome, which is this resolution: record it as such.
                val latest = c.latestDisputes(listOf(orderId))[orderId]
                if (req.resolution == DisputeResolution.REFUND_BUYER && latest?.outcome == DisputeOutcome.REFUNDED && latest.resolvedBy == null) {
                    c.update(
                        "UPDATE order_disputes SET outcome = ?, resolved_by = ?, resolution_note = ? WHERE id = ?",
                        outcome, mod.id, note, latest.id,
                    )
                    audit(c)
                }
                // Otherwise the parties got there first with the same effect (buyer confirmed / seller refunded).
            }
        }
        return case(orderId.toString())
    }
}

/** Moderation-log entry for a dispute resolution (target type ORDER, the order id). */
private fun Connection.auditDisputeResolution(moderatorId: UUID, orderId: UUID, note: String) {
    update(
        "INSERT INTO moderation_actions (id, moderator_id, action, target_type, target_id, note, report_id) VALUES (?, ?, ?, ?, ?, ?, NULL)",
        UUID.randomUUID(), moderatorId, ModerationAction.RESOLVE_DISPUTE, ReportTargetType.ORDER, orderId, note,
    )
}
