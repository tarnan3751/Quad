package com.campusmarket.core

import kotlinx.serialization.Serializable

/*
 * Wire contract shared by the server and the apps.
 *
 * Conventions:
 *  - ids are UUID strings
 *  - timestamps are epoch milliseconds (Long, UTC)
 *  - money is integer minor units (cents) + ISO currency code (always "usd" for now)
 *  - enums travel as their Kotlin name
 */

// ---------- Common ----------

@Serializable
data class ErrorResponse(val code: String, val message: String, val fields: Map<String, String> = emptyMap())

/** Stable machine-readable error codes used in [ErrorResponse.code]. */
object ErrorCodes {
    const val VALIDATION = "VALIDATION"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val NOT_FOUND = "NOT_FOUND"
    const val CONFLICT = "CONFLICT"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL = "INTERNAL"

    const val EMAIL_NOT_ALLOWED = "EMAIL_NOT_ALLOWED"       // not a campus (.edu) address
    const val EMAIL_TAKEN = "EMAIL_TAKEN"
    const val EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED"
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val INVALID_CODE = "INVALID_CODE"
    const val ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED"
    const val BLOCKED = "BLOCKED"                           // interaction prevented by a user block
    const val LISTING_UNAVAILABLE = "LISTING_UNAVAILABLE"
    const val PAYOUTS_NOT_ENABLED = "PAYOUTS_NOT_ENABLED"   // seller hasn't finished Stripe onboarding
    const val INVALID_ORDER_STATE = "INVALID_ORDER_STATE"
    const val PAYMENT_FAILED = "PAYMENT_FAILED"
}

@Serializable
data class Page<T>(val items: List<T>, val nextCursor: String? = null)

@Serializable
data class OkResponse(val ok: Boolean = true)

// ---------- Auth & users ----------

enum class UserRole { USER, MODERATOR, ADMIN }

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String)

/** Returned by register / resend-code. In dev mode [devCode] carries the verification code. */
@Serializable
data class RegisterResponse(val email: String, val verificationRequired: Boolean = true, val devCode: String? = null)

@Serializable
data class VerifyEmailRequest(val email: String, val code: String, val password: String)

@Serializable
data class ResendCodeRequest(val email: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

/**
 * `POST /auth/delete-account`: deletes the account identified by email + password, without a session (a suspended
 * user can't hold one). Same rules and effects as `DELETE /me`.
 */
@Serializable
data class DeleteAccountRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
    val user: UserPrivate,
)

@Serializable
data class UserPublic(
    val id: String,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val campusDomain: String,
    val ratingAverage: Double? = null,
    val ratingCount: Int = 0,
    val joinedAt: Long,
    val blockedByMe: Boolean = false,
)

@Serializable
data class UserPrivate(
    val id: String,
    val email: String,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val campusDomain: String,
    val role: UserRole = UserRole.USER,
    val ratingAverage: Double? = null,
    val ratingCount: Int = 0,
    val payoutsEnabled: Boolean = false,
    val joinedAt: Long,
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val bio: String? = null,
    /** id of a photo previously uploaded via POST /photos; empty string clears the avatar */
    val avatarPhotoId: String? = null,
)

// ---------- Photos ----------

@Serializable
data class Photo(val id: String, val url: String, val width: Int? = null, val height: Int? = null)

// ---------- Listings ----------

enum class ListingCategory(val label: String) {
    TEXTBOOKS("Textbooks"),
    ELECTRONICS("Electronics"),
    FURNITURE("Furniture"),
    CLOTHING("Clothing"),
    DORM("Dorm & Kitchen"),
    BIKES("Bikes & Transport"),
    TICKETS("Tickets & Events"),
    SPORTS("Sports & Outdoors"),
    OTHER("Other"),
}

enum class ListingCondition(val label: String) {
    NEW("New"),
    LIKE_NEW("Like new"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("For parts"),
}

enum class ListingStatus {
    ACTIVE,   // visible and purchasable
    RESERVED, // an order is awaiting payment / handoff
    SOLD,
    REMOVED,  // taken down by a moderator
    DELETED,  // taken down by the seller
}

enum class ListingSort { NEWEST, PRICE_ASC, PRICE_DESC, RELEVANCE }

@Serializable
data class CreateListingRequest(
    val title: String,
    val description: String,
    val category: ListingCategory,
    val condition: ListingCondition,
    val priceCents: Int,
    val pickupLocation: String = "",
    /** ordered photo ids from POST /photos (1..8) */
    val photoIds: List<String>,
)

@Serializable
data class UpdateListingRequest(
    val title: String? = null,
    val description: String? = null,
    val category: ListingCategory? = null,
    val condition: ListingCondition? = null,
    val priceCents: Int? = null,
    val pickupLocation: String? = null,
    val photoIds: List<String>? = null,
)

@Serializable
data class ListingSummary(
    val id: String,
    val title: String,
    val priceCents: Int,
    val currency: String = "usd",
    val category: ListingCategory,
    val condition: ListingCondition,
    val status: ListingStatus,
    val thumbnailUrl: String? = null,
    val sellerId: String,
    val sellerName: String,
    val favorited: Boolean = false,
    val createdAt: Long,
)

@Serializable
data class ListingDetail(
    val id: String,
    val title: String,
    val description: String,
    val priceCents: Int,
    val currency: String = "usd",
    val category: ListingCategory,
    val condition: ListingCondition,
    val status: ListingStatus,
    val pickupLocation: String = "",
    val photos: List<Photo>,
    val seller: UserPublic,
    val favorited: Boolean = false,
    val favoriteCount: Int = 0,
    val isMine: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * The viewer and the seller have blocked each other, in either direction: messaging and buying are refused
     * (403 `BLOCKED`). `seller.blockedByMe` tells whether the viewer did it. Always false on your own listing.
     */
    val blocked: Boolean = false,
)

@Serializable
data class CategoryInfo(val category: ListingCategory, val label: String, val activeCount: Int = 0)

// ---------- Chat ----------

@Serializable
data class StartConversationRequest(val listingId: String, val initialMessage: String? = null)

@Serializable
data class Conversation(
    val id: String,
    val listing: ListingSummary,
    val otherUser: UserPublic,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long? = null,
    val unreadCount: Int = 0,
    val createdAt: Long,
)

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val createdAt: Long,
)

@Serializable
data class SendMessageRequest(val body: String)

/** Frames pushed by the server over the /ws websocket (JSON text frames, polymorphic on "type"). */
@Serializable
sealed class ChatEvent {
    @Serializable
    @kotlinx.serialization.SerialName("message")
    data class MessageCreated(val message: Message) : ChatEvent()

    @Serializable
    @kotlinx.serialization.SerialName("read")
    data class ConversationRead(val conversationId: String, val readerId: String, val readAt: Long) : ChatEvent()

    @Serializable
    @kotlinx.serialization.SerialName("pong")
    data object Pong : ChatEvent()
}

/** Frames the client may send over /ws. */
@Serializable
sealed class ChatCommand {
    @Serializable
    @kotlinx.serialization.SerialName("send")
    data class Send(val conversationId: String, val body: String) : ChatCommand()

    @Serializable
    @kotlinx.serialization.SerialName("read")
    data class MarkRead(val conversationId: String) : ChatCommand()

    @Serializable
    @kotlinx.serialization.SerialName("ping")
    data object Ping : ChatCommand()
}

// ---------- Orders & payments ----------

enum class OrderStatus {
    PENDING_PAYMENT, // checkout session created, buyer hasn't paid
    PAID,            // funds held by the platform; waiting for handoff
    COMPLETED,       // buyer confirmed receipt; funds transferred to the seller
    CANCELLED,       // cancelled before payment (or checkout expired)
    REFUNDED,        // cancelled after payment; buyer refunded
    DISPUTED,        // paid, but the buyer reported a problem (or the seller was suspended): funds frozen until resolved
}

enum class OrderRole { BUYER, SELLER }

@Serializable
data class CreateOrderRequest(val listingId: String)

@Serializable
data class Order(
    val id: String,
    val listing: ListingSummary,
    val buyer: UserPublic,
    val seller: UserPublic,
    val amountCents: Int,
    val feeCents: Int,
    val currency: String = "usd",
    val status: OrderStatus,
    val myRole: OrderRole,
    /** hosted Stripe Checkout URL while status == PENDING_PAYMENT and I am the buyer */
    val checkoutUrl: String? = null,
    val iHaveRated: Boolean = false,
    val createdAt: Long,
    val paidAt: Long? = null,
    val completedAt: Long? = null,
    /**
     * While PAID: when the held payment is released to the seller automatically unless the buyer confirms first or
     * reports a problem. Null in every other status (a dispute freezes the clock; withdrawing it restarts it).
     */
    val autoReleaseAt: Long? = null,
    /** The order's most recent dispute (open or closed), or null if none was ever opened. */
    val dispute: OrderDispute? = null,
)

/** Why the buyer reported a problem with a paid order. [SELLER_SUSPENDED] is set by the platform only. */
enum class DisputeReason(val label: String) {
    SELLER_NO_SHOW("The seller didn't show up"),
    ITEM_NOT_AS_DESCRIBED("Item not as described"),
    OTHER("Something else"),
    SELLER_SUSPENDED("The seller's account was suspended"),
}

enum class DisputeStatus { OPEN, CLOSED }

/** How a dispute ended. */
enum class DisputeOutcome {
    WITHDRAWN,             // the buyer withdrew it; the order went back to PAID
    BUYER_CONFIRMED,       // the buyer confirmed receipt after all; the seller was paid
    SELLER_REFUNDED,       // the seller cancelled the order and the buyer was refunded
    REFUNDED_BY_MODERATOR, // a moderator refunded the buyer
    RELEASED_BY_MODERATOR, // a moderator released the payment to the seller
    REFUNDED,              // the payment was refunded outside the app (payment processor)
}

/** A dispute as both parties of the order see it (the moderator's identity and note stay internal). */
@Serializable
data class OrderDispute(
    val id: String,
    val reason: DisputeReason,
    val details: String = "",
    val status: DisputeStatus,
    val outcome: DisputeOutcome? = null,
    /** true when the platform opened it (e.g. the seller was suspended) rather than the buyer */
    val openedBySystem: Boolean = false,
    val openedAt: Long,
    val closedAt: Long? = null,
)

/** `POST /orders/{id}/dispute` (buyer of a PAID order). */
@Serializable
data class OpenDisputeRequest(val reason: DisputeReason, val details: String = "")

enum class DisputeResolution { REFUND_BUYER, RELEASE_TO_SELLER }

/** `POST /mod/disputes/{orderId}/resolve`. [note] is required and stays internal (moderation log). */
@Serializable
data class ResolveDisputeRequest(val resolution: DisputeResolution, val note: String)

/** A dispute in the moderators' queue: the order, both parties, the listing, amounts and the dispute itself. */
@Serializable
data class DisputeCase(
    val orderId: String,
    val listing: ListingSummary,
    val buyer: UserPublic,
    val seller: UserPublic,
    val amountCents: Int,
    val feeCents: Int,
    val currency: String = "usd",
    val orderStatus: OrderStatus,
    val dispute: OrderDispute,
    val orderCreatedAt: Long,
    val paidAt: Long? = null,
    val buyerSuspended: Boolean = false,
    val sellerSuspended: Boolean = false,
    /** the moderator who resolved it (moderator outcomes only) */
    val resolvedBy: UserPublic? = null,
    val resolutionNote: String? = null,
)

@Serializable
data class PayoutAccountStatus(
    val hasAccount: Boolean,
    val detailsSubmitted: Boolean = false,
    val payoutsEnabled: Boolean = false,
)

@Serializable
data class OnboardingLinkResponse(val url: String)

// ---------- Ratings ----------

@Serializable
data class CreateRatingRequest(val stars: Int, val comment: String = "")

@Serializable
data class Rating(
    val id: String,
    val orderId: String,
    val rater: UserPublic,
    val rateeId: String,
    val stars: Int,
    val comment: String = "",
    val listingTitle: String? = null,
    val createdAt: Long,
)

// ---------- Reports & moderation ----------

/** [ORDER] only appears in the moderation log (dispute resolutions); orders can't be reported. */
enum class ReportTargetType { LISTING, USER, MESSAGE, ORDER }

enum class ReportReason(val label: String) {
    PROHIBITED_ITEM("Prohibited or illegal item"),
    SCAM("Scam or fraud"),
    HARASSMENT("Harassment or hate"),
    SPAM("Spam"),
    MISLEADING("Misleading or counterfeit"),
    OTHER("Something else"),
}

enum class ReportStatus { OPEN, RESOLVED, DISMISSED }

enum class ModerationAction {
    DISMISS,         // no violation
    REMOVE_LISTING,
    RESTORE_LISTING,
    SUSPEND_USER,
    UNSUSPEND_USER,
    WARN_USER,
    CHANGE_ROLE,     // admin promoted/demoted a user (log only; appended last, never valid for resolving a report)
    RESOLVE_DISPUTE, // moderator refunded the buyer or released the payment to the seller (log only; target ORDER)
}

@Serializable
data class CreateReportRequest(
    val targetType: ReportTargetType,
    val targetId: String,
    val reason: ReportReason,
    val details: String = "",
)

@Serializable
data class Report(
    val id: String,
    val reporter: UserPublic,
    val targetType: ReportTargetType,
    val targetId: String,
    val reason: ReportReason,
    val details: String = "",
    val status: ReportStatus,
    /** human-readable snapshot of the target (listing title / user name / message body) */
    val targetSummary: String = "",
    /** the user responsible for the reported content (listing seller / the user / message sender) */
    val targetUser: UserPublic? = null,
    val targetListing: ListingSummary? = null,
    val openReportsAgainstTargetUser: Int = 0,
    val resolutionAction: ModerationAction? = null,
    val resolutionNote: String? = null,
    val resolvedBy: UserPublic? = null,
    val createdAt: Long,
    val resolvedAt: Long? = null,
)

@Serializable
data class ResolveReportRequest(
    val action: ModerationAction,
    val note: String = "",
    /** for SUSPEND_USER: days of suspension; null = indefinite */
    val suspendDays: Int? = null,
)

@Serializable
data class SuspendUserRequest(val reason: String, val days: Int? = null)

/** `PUT /mod/users/{id}/role` (admin only). Only USER and MODERATOR are assignable; ADMIN comes from `ADMIN_EMAILS`. */
@Serializable
data class SetRoleRequest(val role: UserRole)

/** A staff member (or a user found by the admin lookup). [email] is only ever sent to admins. */
@Serializable
data class StaffMember(val user: UserPublic, val role: UserRole, val email: String)

@Serializable
data class ModerationNoteRequest(val note: String = "")

@Serializable
data class ModerationLogEntry(
    val id: String,
    val moderator: UserPublic,
    val action: ModerationAction,
    val targetType: ReportTargetType,
    val targetId: String,
    val note: String = "",
    val reportId: String? = null,
    val createdAt: Long,
)
