package com.campusmarket.server.payments

import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Rating
import com.campusmarket.server.AppConfig
import com.campusmarket.server.loadListingSummaries
import com.campusmarket.server.loadUsersPublic
import com.campusmarket.server.millis
import com.campusmarket.server.query
import com.campusmarket.server.uuid
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/*
 * DTO assembly for orders and ratings (users and listing summaries come from the shared Readers).
 */

/**
 * Orders [ids] as seen by [viewerId] (a participant), in the order of [ids]. [autoRelease]: how long after the clock
 * start a PAID order is released to the seller (for `autoReleaseAt`).
 */
internal fun Connection.loadOrders(config: AppConfig, ids: List<UUID>, viewerId: UUID, autoRelease: Duration): List<Order> {
    if (ids.isEmpty()) return emptyList()
    data class Row(val order: OrderRow, val iHaveRated: Boolean)
    val rows = query(
        """SELECT $ORDER_COLUMNS,
                  EXISTS (SELECT 1 FROM ratings r WHERE r.order_id = o.id AND r.rater_id = ?) AS i_have_rated
           FROM orders o WHERE o.id = ANY(?)""",
        viewerId, ids,
    ) { Row(it.toOrderRow(), it.getBoolean("i_have_rated")) }.associateBy { it.order.id }
    val listings = loadListingSummaries(config, rows.values.map { it.order.listingId }, viewerId)
    val users = loadUsersPublic(config, rows.values.flatMap { listOf(it.order.buyerId, it.order.sellerId) }, viewerId)
    val disputes = latestDisputes(rows.keys)
    return ids.mapNotNull { id ->
        val (o, rated) = rows[id] ?: return@mapNotNull null
        val role = if (viewerId == o.buyerId) OrderRole.BUYER else OrderRole.SELLER
        Order(
            id = o.id.toString(),
            listing = listings.getValue(o.listingId),
            buyer = users.getValue(o.buyerId),
            seller = users.getValue(o.sellerId),
            amountCents = o.amountCents,
            feeCents = o.feeCents,
            currency = o.currency,
            status = o.status,
            myRole = role,
            checkoutUrl = o.checkoutUrl.takeIf { o.status == OrderStatus.PENDING_PAYMENT && role == OrderRole.BUYER },
            iHaveRated = rated,
            createdAt = o.createdAt.toEpochMilli(),
            paidAt = o.paidAt?.toEpochMilli(),
            completedAt = o.completedAt?.toEpochMilli(),
            autoReleaseAt = o.autoReleaseAt(autoRelease)?.toEpochMilli(),
            dispute = disputes[o.id]?.toDto(),
        )
    }
}

internal fun Connection.loadOrder(config: AppConfig, id: UUID, viewerId: UUID, autoRelease: Duration): Order? =
    loadOrders(config, listOf(id), viewerId, autoRelease).firstOrNull()

/** When a PAID order is released to the seller automatically; null in every other status (a dispute freezes it). */
internal fun OrderRow.autoReleaseAt(autoRelease: Duration): Instant? =
    if (status == OrderStatus.PAID) releaseClockStart.plus(autoRelease.toJavaDuration()) else null

/** Ratings [ids] in the order given; raters rendered for [viewerId]. */
internal fun Connection.loadRatings(config: AppConfig, ids: List<UUID>, viewerId: UUID): List<Rating> {
    if (ids.isEmpty()) return emptyList()
    data class Row(val id: UUID, val orderId: UUID, val raterId: UUID, val rateeId: UUID, val stars: Int, val comment: String, val title: String?, val createdAt: Long)
    val rows = query(
        """SELECT r.id, r.order_id, r.rater_id, r.ratee_id, r.stars, r.comment, r.created_at, l.title
           FROM ratings r JOIN orders o ON o.id = r.order_id LEFT JOIN listings l ON l.id = o.listing_id
           WHERE r.id = ANY(?)""",
        ids,
    ) { rs ->
        Row(
            rs.uuid("id"), rs.uuid("order_id"), rs.uuid("rater_id"), rs.uuid("ratee_id"), rs.getInt("stars"),
            rs.getString("comment"), rs.getString("title"), rs.millis("created_at"),
        )
    }.associateBy { it.id }
    val raters = loadUsersPublic(config, rows.values.map { it.raterId }, viewerId)
    return ids.mapNotNull { id ->
        val r = rows[id] ?: return@mapNotNull null
        Rating(
            id = r.id.toString(),
            orderId = r.orderId.toString(),
            rater = raters.getValue(r.raterId),
            rateeId = r.rateeId.toString(),
            stars = r.stars,
            comment = r.comment,
            listingTitle = r.title,
            createdAt = r.createdAt,
        )
    }
}
