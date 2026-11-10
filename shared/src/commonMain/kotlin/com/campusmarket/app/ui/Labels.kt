package com.campusmarket.app.ui

import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserRole

// User-facing names for enums that don't carry a `label` in core. (ListingCategory, ListingCondition and
// ReportReason already have `.label`.)

val ListingStatus.label: String
    get() = when (this) {
        ListingStatus.ACTIVE -> "Active"
        ListingStatus.RESERVED -> "Reserved"
        ListingStatus.SOLD -> "Sold"
        ListingStatus.REMOVED -> "Removed"
        ListingStatus.DELETED -> "Deleted"
    }

val OrderStatus.label: String
    get() = when (this) {
        OrderStatus.PENDING_PAYMENT -> "Awaiting payment"
        OrderStatus.PAID -> "Paid · awaiting handoff"
        OrderStatus.COMPLETED -> "Completed"
        OrderStatus.CANCELLED -> "Cancelled"
        OrderStatus.REFUNDED -> "Refunded"
        OrderStatus.DISPUTED -> "Problem reported · on hold"
    }

val OrderRole.label: String
    get() = when (this) {
        OrderRole.BUYER -> "Buying"
        OrderRole.SELLER -> "Selling"
    }

val ReportStatus.label: String
    get() = when (this) {
        ReportStatus.OPEN -> "Open"
        ReportStatus.RESOLVED -> "Resolved"
        ReportStatus.DISMISSED -> "Dismissed"
    }

val ReportTargetType.label: String
    get() = when (this) {
        ReportTargetType.LISTING -> "Listing"
        ReportTargetType.USER -> "User"
        ReportTargetType.MESSAGE -> "Message"
        ReportTargetType.ORDER -> "Order"
    }

val ModerationAction.label: String
    get() = when (this) {
        ModerationAction.DISMISS -> "Dismiss"
        ModerationAction.REMOVE_LISTING -> "Remove listing"
        ModerationAction.RESTORE_LISTING -> "Restore listing"
        ModerationAction.SUSPEND_USER -> "Suspend user"
        ModerationAction.UNSUSPEND_USER -> "Unsuspend user"
        ModerationAction.WARN_USER -> "Warn user"
        ModerationAction.CHANGE_ROLE -> "Change role"
        ModerationAction.RESOLVE_DISPUTE -> "Resolve dispute"
    }

val DisputeStatus.label: String
    get() = when (this) {
        DisputeStatus.OPEN -> "Open"
        DisputeStatus.CLOSED -> "Closed"
    }

/** How a dispute ended, in neutral words (both parties and moderators see it). */
val DisputeOutcome.label: String
    get() = when (this) {
        DisputeOutcome.WITHDRAWN -> "Withdrawn by the buyer"
        DisputeOutcome.BUYER_CONFIRMED -> "Buyer confirmed receipt"
        DisputeOutcome.SELLER_REFUNDED -> "Seller refunded the buyer"
        DisputeOutcome.REFUNDED_BY_MODERATOR -> "Buyer refunded after review"
        DisputeOutcome.RELEASED_BY_MODERATOR -> "Payment released to the seller after review"
        DisputeOutcome.REFUNDED -> "Refunded"
    }

val DisputeResolution.label: String
    get() = when (this) {
        DisputeResolution.REFUND_BUYER -> "Refund buyer"
        DisputeResolution.RELEASE_TO_SELLER -> "Release to seller"
    }

val UserRole.label: String
    get() = when (this) {
        UserRole.USER -> "Student"
        UserRole.MODERATOR -> "Moderator"
        UserRole.ADMIN -> "Admin"
    }
