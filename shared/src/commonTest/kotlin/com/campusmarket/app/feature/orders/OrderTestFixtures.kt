package com.campusmarket.app.feature.orders

import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.NOW
import com.campusmarket.app.feature.chat.listingSummary
import com.campusmarket.app.feature.chat.publicUser
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.Order
import com.campusmarket.core.OrderDispute
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus

val buyerUser = publicUser("u1", "Liam Park")
val sellerUser = publicUser("u2", "Maya Chen")

fun order(
    status: OrderStatus,
    role: OrderRole = OrderRole.BUYER,
    id: String = "o1",
    iHaveRated: Boolean = false,
    checkoutUrl: String? = if (status == OrderStatus.PENDING_PAYMENT && role == OrderRole.BUYER) "http://localhost:8080/api/v1/dev/fake-stripe/checkout/$id" else null,
    dispute: OrderDispute? = if (status == OrderStatus.DISPUTED) dispute() else null,
    autoReleaseAt: Long? = if (status == OrderStatus.PAID) NOW + 72 * HOUR else null,
) = Order(
    id = id,
    listing = listingSummary(sellerId = sellerUser.id, sellerName = sellerUser.displayName),
    buyer = buyerUser,
    seller = sellerUser,
    amountCents = 2500,
    feeCents = 125,
    status = status,
    myRole = role,
    checkoutUrl = checkoutUrl,
    iHaveRated = iHaveRated,
    createdAt = NOW - 60_000,
    paidAt = if (status in setOf(OrderStatus.PAID, OrderStatus.DISPUTED, OrderStatus.COMPLETED, OrderStatus.REFUNDED)) NOW - 30_000 else null,
    completedAt = if (status == OrderStatus.COMPLETED) NOW - 10_000 else null,
    autoReleaseAt = autoReleaseAt,
    dispute = dispute,
)

/** An open buyer dispute by default; pass [outcome] for a closed one, [system] for a platform hold. */
fun dispute(
    reason: DisputeReason = DisputeReason.SELLER_NO_SHOW,
    details: String = "Waited 30 minutes, no show",
    outcome: DisputeOutcome? = null,
    system: Boolean = false,
) = OrderDispute(
    id = "d1",
    reason = if (system) DisputeReason.SELLER_SUSPENDED else reason,
    details = if (system) "" else details,
    status = if (outcome == null) DisputeStatus.OPEN else DisputeStatus.CLOSED,
    outcome = outcome,
    openedBySystem = system,
    openedAt = NOW - 20_000,
    closedAt = if (outcome == null) null else NOW - 5_000,
)
