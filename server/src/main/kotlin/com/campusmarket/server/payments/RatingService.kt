package com.campusmarket.server.payments

import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.Rating
import com.campusmarket.server.AppDeps
import com.campusmarket.server.KeysetCursor
import com.campusmarket.server.PageParams
import com.campusmarket.server.Sql
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.asUuid
import com.campusmarket.server.conflict
import com.campusmarket.server.invalidField
import com.campusmarket.server.notFound
import com.campusmarket.server.pageOfIds
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import com.campusmarket.server.withoutControlChars
import java.util.UUID

/** Ratings: each party of a COMPLETED order rates the other once; `users.rating_sum/rating_count` move in the same transaction. */
internal class RatingService(private val deps: AppDeps) {
    private val db = deps.db
    private val config = deps.config

    suspend fun rate(user: UserPrincipal, orderIdRaw: String, req: CreateRatingRequest): Rating {
        val orderId = orderIdRaw.asUuid("Order")
        if (req.stars !in 1..5) invalidField("stars", "Rating must be between 1 and 5 stars")
        val comment = req.comment.withoutControlChars().trim()
        if (comment.length > Limits.RATING_COMMENT_MAX) invalidField("comment", "Comment must be at most ${Limits.RATING_COMMENT_MAX} characters")

        return db.tx { c ->
            val order = c.findOrder(orderId)?.takeIf { it.isParticipant(user.id) } ?: notFound("Order")
            if (order.status != OrderStatus.COMPLETED) conflict("You can rate once the order is completed", ErrorCodes.INVALID_ORDER_STATE)
            val rateeId = if (user.id == order.buyerId) order.sellerId else order.buyerId
            val id = UUID.randomUUID()
            val inserted = c.update(
                """INSERT INTO ratings (id, order_id, rater_id, ratee_id, stars, comment) VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT (order_id, rater_id) DO NOTHING""",
                id, orderId, user.id, rateeId, req.stars, comment,
            )
            if (inserted == 0) conflict("You've already rated this order")
            c.update("UPDATE users SET rating_sum = rating_sum + ?, rating_count = rating_count + 1 WHERE id = ?", req.stars, rateeId)
            c.loadRatings(config, listOf(id), user.id).single()
        }
    }

    /** Ratings [userIdRaw] received, newest first. Same campus only. */
    suspend fun received(user: UserPrincipal, userIdRaw: String, page: PageParams): Page<Rating> {
        val rateeId = userIdRaw.asUuid("User")
        val sql = Sql("SELECT r.id, r.created_at FROM ratings r WHERE r.ratee_id = ?", rateeId)
            .keysetPage("r.created_at", "r.id", KeysetCursor.decode(page.cursor), page.limit)
        return db.tx { c ->
            val campus = c.queryOne("SELECT campus_domain FROM users WHERE id = ?", rateeId) { it.getString(1) }
            if (campus == null || campus != user.campusDomain) notFound("User")
            c.pageOfIds(sql, page.limit) { ids -> loadRatings(config, ids, user.id) }
        }
    }
}
