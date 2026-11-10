package com.campusmarket.app.feature.moderation

import kotlinx.serialization.Serializable

// Owner: feature C (profile + moderation). Moderator-only screens; the server enforces it (403).

/** Reports, filter by status (default OPEN, oldest first). */
@Serializable
data object ModerationQueueRoute

/** One report: target summary, reporter, history; resolve with an action. */
@Serializable
data class ReportDetailRoute(val reportId: String)

@Serializable
data object ModerationLogRoute

/** Admin-only: list staff, look users up, promote to moderator / demote to student. */
@Serializable
data object StaffRoute

/** Disputed orders (buyers' problem reports, suspended sellers' held payments): Open (oldest first) / Closed. */
@Serializable
data object DisputesRoute

/** One dispute: both parties, listing, amounts, reason; refund the buyer or release the payment to the seller. */
@Serializable
data class DisputeDetailRoute(val orderId: String)
