package com.campusmarket.core

/** Path constants for the HTTP API. All paths are relative to the server origin. */
object ApiRoutes {
    const val BASE = "/api/v1"

    const val HEALTH = "$BASE/health"

    // auth (public)
    const val REGISTER = "$BASE/auth/register"
    const val VERIFY_EMAIL = "$BASE/auth/verify"
    const val RESEND_CODE = "$BASE/auth/resend-code"
    const val LOGIN = "$BASE/auth/login"
    const val REFRESH = "$BASE/auth/refresh"
    const val LOGOUT = "$BASE/auth/logout"
    /** credential-authenticated account deletion (works for suspended accounts, which can't hold a session) */
    const val DELETE_ACCOUNT = "$BASE/auth/delete-account"

    // users
    const val ME = "$BASE/me"
    const val MY_LISTINGS = "$BASE/me/listings"
    const val MY_FAVORITES = "$BASE/me/favorites"
    const val MY_BLOCKS = "$BASE/me/blocks"
    fun user(id: String) = "$BASE/users/$id"
    fun userListings(id: String) = "$BASE/users/$id/listings"
    fun userRatings(id: String) = "$BASE/users/$id/ratings"
    fun userBlock(id: String) = "$BASE/users/$id/block"

    // photos
    const val PHOTOS = "$BASE/photos"
    /** static file prefix for uploaded photos (not under /api) */
    const val UPLOADS = "/uploads"

    // listings
    const val LISTINGS = "$BASE/listings"
    const val CATEGORIES = "$BASE/categories"
    fun listing(id: String) = "$BASE/listings/$id"
    fun listingFavorite(id: String) = "$BASE/listings/$id/favorite"

    // chat
    const val CONVERSATIONS = "$BASE/conversations"
    fun conversation(id: String) = "$BASE/conversations/$id"
    fun conversationMessages(id: String) = "$BASE/conversations/$id/messages"
    fun conversationRead(id: String) = "$BASE/conversations/$id/read"
    /** websocket; authenticate with ?token=<accessToken> */
    const val WS = "$BASE/ws"

    // orders & payments
    const val ORDERS = "$BASE/orders"
    fun order(id: String) = "$BASE/orders/$id"
    fun orderConfirm(id: String) = "$BASE/orders/$id/confirm"
    fun orderCancel(id: String) = "$BASE/orders/$id/cancel"
    fun orderRating(id: String) = "$BASE/orders/$id/rating"
    fun orderDispute(id: String) = "$BASE/orders/$id/dispute"
    fun orderDisputeWithdraw(id: String) = "$BASE/orders/$id/dispute/withdraw"
    const val PAYOUT_ACCOUNT = "$BASE/payments/account"
    const val PAYOUT_ONBOARDING = "$BASE/payments/account/onboarding"
    const val STRIPE_WEBHOOK = "$BASE/stripe/webhook"

    // reports & moderation
    const val REPORTS = "$BASE/reports"
    const val MOD_REPORTS = "$BASE/mod/reports"
    fun modReport(id: String) = "$BASE/mod/reports/$id"
    fun modReportResolve(id: String) = "$BASE/mod/reports/$id/resolve"
    fun modUserSuspend(id: String) = "$BASE/mod/users/$id/suspend"
    fun modUserUnsuspend(id: String) = "$BASE/mod/users/$id/unsuspend"
    fun modListingRemove(id: String) = "$BASE/mod/listings/$id/remove"
    fun modListingRestore(id: String) = "$BASE/mod/listings/$id/restore"
    const val MOD_LOG = "$BASE/mod/log"
    const val MOD_STAFF = "$BASE/mod/staff"
    const val MOD_USER_SEARCH = "$BASE/mod/users/search"
    fun modUserRole(id: String) = "$BASE/mod/users/$id/role"
    const val MOD_DISPUTES = "$BASE/mod/disputes"
    fun modDispute(orderId: String) = "$BASE/mod/disputes/$orderId"
    fun modDisputeResolve(orderId: String) = "$BASE/mod/disputes/$orderId/resolve"

    // legal pages: public server-rendered HTML (not under /api)
    const val LEGAL_TERMS = "/legal/terms"
    const val LEGAL_PRIVACY = "/legal/privacy"
    const val LEGAL_GUIDELINES = "/legal/guidelines"
}

/** Validation limits shared by client-side forms and server-side checks. */
object Limits {
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 128
    const val DISPLAY_NAME_MIN = 2
    const val DISPLAY_NAME_MAX = 40
    const val BIO_MAX = 300
    const val TITLE_MIN = 3
    const val TITLE_MAX = 80
    const val DESCRIPTION_MAX = 4000
    const val PICKUP_MAX = 120
    const val PRICE_MIN_CENTS = 100          // Stripe minimum-ish; $1.00
    const val PRICE_MAX_CENTS = 500_000      // $5,000.00
    const val PHOTOS_MIN = 1
    const val PHOTOS_MAX = 8
    const val PHOTO_MAX_BYTES = 8 * 1024 * 1024
    const val MESSAGE_MAX = 2000
    const val RATING_COMMENT_MAX = 500
    const val REPORT_DETAILS_MAX = 1000
    /** Buyer's description of a problem with an order (`OpenDisputeRequest.details`). */
    const val DISPUTE_DETAILS_MAX = 1000
    /** Moderator's internal note when resolving a dispute (`ResolveDisputeRequest.note`). */
    const val DISPUTE_NOTE_MAX = 1000
    const val PAGE_DEFAULT = 20
    const val PAGE_MAX = 50
    const val VERIFICATION_CODE_LENGTH = 6
}

val ApiJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

fun formatPrice(cents: Int, currency: String = "usd"): String {
    val symbol = if (currency.lowercase() == "usd") "$" else ""
    val whole = cents / 100
    val frac = cents % 100
    val wholeStr = whole.toString().reversed().chunked(3).joinToString(",").reversed()
    val base = if (frac == 0) "$symbol$wholeStr" else "$symbol$wholeStr.${frac.toString().padStart(2, '0')}"
    return if (symbol.isEmpty()) "$base ${currency.uppercase()}" else base
}
