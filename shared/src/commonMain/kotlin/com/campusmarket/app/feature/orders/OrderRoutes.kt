package com.campusmarket.app.feature.orders

import kotlinx.serialization.Serializable

// Owner: feature B (chat + orders).

/** Buy flow for a listing: review price → `createOrder` → open `checkoutUrl` in the browser → [OrderDetailRoute]. */
@Serializable
data class CheckoutRoute(val listingId: String)

/** Orders list. [role] = "buyer", "seller", or null for both (see [com.campusmarket.core.OrderRole]). */
@Serializable
data class OrdersRoute(val role: String? = null)

/** Order detail: confirm receipt / cancel / rate. Also the target of `campusmarket://orders/{id}`. */
@Serializable
data class OrderDetailRoute(val orderId: String)

/** Rate the other party of a COMPLETED order. */
@Serializable
data class RateOrderRoute(val orderId: String)

/** Buyer of a PAID order reports a problem (opens a dispute, which puts the payment on hold). */
@Serializable
data class ReportProblemRoute(val orderId: String)

/** Seller payout onboarding / status. Also the target of `campusmarket://payouts`. */
@Serializable
data object PayoutsRoute
