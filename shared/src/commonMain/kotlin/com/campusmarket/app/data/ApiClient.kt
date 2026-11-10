package com.campusmarket.app.data

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.Conversation
import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.CreateOrderRequest
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.CreateReportRequest
import com.campusmarket.core.DeleteAccountRequest
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.OpenDisputeRequest
import com.campusmarket.core.ResolveDisputeRequest
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingSort
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.LoginRequest
import com.campusmarket.core.LogoutRequest
import com.campusmarket.core.Message
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.ModerationNoteRequest
import com.campusmarket.core.SetRoleRequest
import com.campusmarket.core.StaffMember
import com.campusmarket.core.UserRole
import com.campusmarket.core.OkResponse
import com.campusmarket.core.OnboardingLinkResponse
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.Page
import com.campusmarket.core.PayoutAccountStatus
import com.campusmarket.core.Photo
import com.campusmarket.core.Rating
import com.campusmarket.core.RefreshRequest
import com.campusmarket.core.RegisterRequest
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.Report
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ResendCodeRequest
import com.campusmarket.core.ResolveReportRequest
import com.campusmarket.core.SendMessageRequest
import com.campusmarket.core.StartConversationRequest
import com.campusmarket.core.SuspendUserRequest
import com.campusmarket.core.UpdateListingRequest
import com.campusmarket.core.UpdateProfileRequest
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.campusmarket.core.VerifyEmailRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/** Filters for `GET /listings`. Null / empty = not applied. */
data class ListingQuery(
    val q: String? = null,
    val category: ListingCategory? = null,
    val conditions: Set<ListingCondition> = emptySet(),
    val minPriceCents: Int? = null,
    val maxPriceCents: Int? = null,
    /** Null = server default (NEWEST, or RELEVANCE when [q] is set). */
    val sort: ListingSort? = null,
)

/**
 * Typed client for every endpoint in docs/CONTRACT.md.
 *
 * - Every function is `suspend` and either returns the decoded body or throws [ApiError] (never anything else,
 *   except `CancellationException`). Endpoints that answer `OkResponse` return `Unit`.
 * - Authenticated calls attach `Authorization: Bearer`. The access token is refreshed proactively shortly before it
 *   expires and reactively on a 401 (single-flight: concurrent callers share one refresh), then the call is retried
 *   once. If the refresh token is rejected the stored session is cleared, which signs the user out app-wide
 *   ([SessionManager.state] → SignedOut) and the call throws [ApiError.SESSION_EXPIRED].
 * - Sign-in / sign-up / sign-out live in [SessionManager], which calls the auth functions here and stores the tokens.
 */
class ApiClient(
    private val http: HttpClient,
    private val server: ServerConfig,
    private val tokens: TokenStore,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    // Callers are mostly view models on the main thread. Requests, token refreshes (Keystore writes) and JSON decoding
    // run on the engine's I/O dispatcher instead (tests give the mock engine their test dispatcher).
    private val requestContext: CoroutineContext =
        http.engine.dispatcher.takeUnless { it == Dispatchers.Unconfined } ?: Dispatchers.Default

    private val refreshMutex = Mutex()
    private val _sessionEndReason = MutableStateFlow<ApiError?>(null)

    /** Why the session was ended by the client (refresh rejected, account suspended); null after a normal sign-out. */
    val sessionEndReason: StateFlow<ApiError?> = _sessionEndReason.asStateFlow()

    /**
     * Email of the account whose session [sessionEndReason] ended (in memory only), so the suspended-account screen
     * can prefill it for [deleteAccountWithCredentials]. Null whenever [sessionEndReason] is.
     */
    var sessionEndEmail: String? = null
        private set

    fun clearSessionEndReason() {
        _sessionEndReason.value = null
        sessionEndEmail = null
    }

    // ------------------------------------------------------------------------------------------------ health

    suspend fun health() {
        request<OkResponse>(HttpMethod.Get, ApiRoutes.HEALTH, auth = false)
    }

    // ------------------------------------------------------------------------------------------------ auth (public)

    suspend fun register(body: RegisterRequest): RegisterResponse =
        request(HttpMethod.Post, ApiRoutes.REGISTER, auth = false) { json(body) }

    suspend fun verifyEmail(body: VerifyEmailRequest): AuthResponse =
        request(HttpMethod.Post, ApiRoutes.VERIFY_EMAIL, auth = false) { json(body) }

    suspend fun resendCode(email: String): RegisterResponse =
        request(HttpMethod.Post, ApiRoutes.RESEND_CODE, auth = false) { json(ResendCodeRequest(email)) }

    suspend fun login(body: LoginRequest): AuthResponse =
        request(HttpMethod.Post, ApiRoutes.LOGIN, auth = false) { json(body) }

    suspend fun logout(refreshToken: String) {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.LOGOUT, auth = false) { json(LogoutRequest(refreshToken)) }
    }

    /**
     * `POST /auth/delete-account`: deletes the account by email + password, without a session (for suspended users,
     * who can't sign in). Same rules as [deleteMe]: 409 `CONFLICT` while orders are in progress; 401
     * `INVALID_CREDENTIALS` for a wrong email or password.
     */
    suspend fun deleteAccountWithCredentials(email: String, password: String) {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.DELETE_ACCOUNT, auth = false) { json(DeleteAccountRequest(email, password)) }
    }

    // ------------------------------------------------------------------------------------------------ users

    suspend fun me(): UserPrivate = request(HttpMethod.Get, ApiRoutes.ME)

    suspend fun updateMe(body: UpdateProfileRequest): UserPrivate =
        request(HttpMethod.Patch, ApiRoutes.ME) { json(body) }

    /** Account deletion. 409 `CONFLICT` while the user has PAID orders. Prefer [SessionManager.deleteAccount]. */
    suspend fun deleteMe() {
        request<OkResponse>(HttpMethod.Delete, ApiRoutes.ME)
    }

    suspend fun user(userId: String): UserPublic = request(HttpMethod.Get, ApiRoutes.user(userId))

    suspend fun blockUser(userId: String) {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.userBlock(userId))
    }

    suspend fun unblockUser(userId: String) {
        request<OkResponse>(HttpMethod.Delete, ApiRoutes.userBlock(userId))
    }

    suspend fun myBlocks(): List<UserPublic> = request(HttpMethod.Get, ApiRoutes.MY_BLOCKS)

    // ------------------------------------------------------------------------------------------------ photos

    /**
     * Uploads one image (JPEG/PNG/WebP, ≤ `Limits.PHOTO_MAX_BYTES`) as multipart part `file`.
     * Use the bytes from [com.campusmarket.app.platform.rememberImagePicker], which already re-encodes to JPEG.
     * [onProgress] (optional) receives the fraction of the request body sent, 0..1.
     */
    suspend fun uploadPhoto(
        bytes: ByteArray,
        fileName: String = "photo.jpg",
        contentType: String = "image/jpeg",
        onProgress: ((Float) -> Unit)? = null,
    ): Photo =
        request(HttpMethod.Post, ApiRoutes.PHOTOS) {
            if (onProgress != null) {
                onUpload { sent, total -> if (total != null && total > 0) onProgress((sent.toFloat() / total).coerceIn(0f, 1f)) }
            }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                            },
                        )
                    },
                ),
            )
        }

    // ------------------------------------------------------------------------------------------------ listings

    suspend fun createListing(body: CreateListingRequest): ListingDetail =
        request(HttpMethod.Post, ApiRoutes.LISTINGS) { json(body) }

    suspend fun listings(query: ListingQuery = ListingQuery(), cursor: String? = null, limit: Int? = null): Page<ListingSummary> =
        request(HttpMethod.Get, ApiRoutes.LISTINGS) {
            query.q?.trim()?.takeIf { it.isNotEmpty() }?.let { parameter("q", it) }
            query.category?.let { parameter("category", it.name) }
            if (query.conditions.isNotEmpty()) parameter("condition", query.conditions.joinToString(",") { it.name })
            query.minPriceCents?.let { parameter("minPrice", it) }
            query.maxPriceCents?.let { parameter("maxPrice", it) }
            query.sort?.let { parameter("sort", it.name) }
            page(cursor, limit)
        }

    suspend fun listing(listingId: String): ListingDetail = request(HttpMethod.Get, ApiRoutes.listing(listingId))

    suspend fun updateListing(listingId: String, body: UpdateListingRequest): ListingDetail =
        request(HttpMethod.Patch, ApiRoutes.listing(listingId)) { json(body) }

    suspend fun deleteListing(listingId: String) {
        request<OkResponse>(HttpMethod.Delete, ApiRoutes.listing(listingId))
    }

    suspend fun favorite(listingId: String) {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.listingFavorite(listingId))
    }

    suspend fun unfavorite(listingId: String) {
        request<OkResponse>(HttpMethod.Delete, ApiRoutes.listingFavorite(listingId))
    }

    suspend fun setFavorite(listingId: String, favorited: Boolean) =
        if (favorited) favorite(listingId) else unfavorite(listingId)

    suspend fun myFavorites(cursor: String? = null, limit: Int? = null): Page<ListingSummary> =
        request(HttpMethod.Get, ApiRoutes.MY_FAVORITES) { page(cursor, limit) }

    suspend fun myListings(status: ListingStatus? = null, cursor: String? = null, limit: Int? = null): Page<ListingSummary> =
        request(HttpMethod.Get, ApiRoutes.MY_LISTINGS) {
            status?.let { parameter("status", it.name) }
            page(cursor, limit)
        }

    suspend fun userListings(userId: String, cursor: String? = null, limit: Int? = null): Page<ListingSummary> =
        request(HttpMethod.Get, ApiRoutes.userListings(userId)) { page(cursor, limit) }

    suspend fun categories(): List<CategoryInfo> = request(HttpMethod.Get, ApiRoutes.CATEGORIES)

    // ------------------------------------------------------------------------------------------------ chat

    /** Idempotent per (listing, buyer): returns the existing conversation if there is one. */
    suspend fun startConversation(listingId: String, initialMessage: String? = null): Conversation =
        request(HttpMethod.Post, ApiRoutes.CONVERSATIONS) { json(StartConversationRequest(listingId, initialMessage)) }

    suspend fun conversations(cursor: String? = null, limit: Int? = null): Page<Conversation> =
        request(HttpMethod.Get, ApiRoutes.CONVERSATIONS) { page(cursor, limit) }

    suspend fun conversation(conversationId: String): Conversation =
        request(HttpMethod.Get, ApiRoutes.conversation(conversationId))

    /** Newest first; pass `nextCursor` to page backwards in time. */
    suspend fun messages(conversationId: String, cursor: String? = null, limit: Int? = null): Page<Message> =
        request(HttpMethod.Get, ApiRoutes.conversationMessages(conversationId)) { page(cursor, limit) }

    /**
     * [clientId] names the message (see [SendMessageRequest]): sending it again with the same id returns the stored
     * message, so a send whose response was lost can be retried without creating a duplicate.
     */
    suspend fun sendMessage(conversationId: String, body: String, clientId: String? = null): Message =
        request(HttpMethod.Post, ApiRoutes.conversationMessages(conversationId)) { json(SendMessageRequest(body, clientId)) }

    suspend fun markConversationRead(conversationId: String) {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.conversationRead(conversationId))
    }

    // ------------------------------------------------------------------------------------------------ orders & payments

    suspend fun payoutAccount(): PayoutAccountStatus = request(HttpMethod.Get, ApiRoutes.PAYOUT_ACCOUNT)

    /** Returns the Stripe onboarding URL; open it with [com.campusmarket.app.platform.ExternalLinks]. */
    suspend fun startPayoutOnboarding(): OnboardingLinkResponse = request(HttpMethod.Post, ApiRoutes.PAYOUT_ONBOARDING)

    /** Creates a PENDING_PAYMENT order; open `checkoutUrl` in the browser. 409 `LISTING_UNAVAILABLE` / `PAYOUTS_NOT_ENABLED`. */
    suspend fun createOrder(listingId: String): Order =
        request(HttpMethod.Post, ApiRoutes.ORDERS) { json(CreateOrderRequest(listingId)) }

    /** [role] null = orders where I'm buyer or seller. Sent as `role=buyer|seller` per the contract. */
    suspend fun orders(role: OrderRole? = null, cursor: String? = null, limit: Int? = null): Page<Order> =
        request(HttpMethod.Get, ApiRoutes.ORDERS) {
            role?.let { parameter("role", it.name.lowercase()) }
            page(cursor, limit)
        }

    suspend fun order(orderId: String): Order = request(HttpMethod.Get, ApiRoutes.order(orderId))

    /** Buyer confirms handoff: PAID / DISPUTED → COMPLETED. */
    suspend fun confirmOrder(orderId: String): Order = request(HttpMethod.Post, ApiRoutes.orderConfirm(orderId))

    /** PENDING_PAYMENT → CANCELLED (buyer or seller); PAID / DISPUTED → REFUNDED (seller only). */
    suspend fun cancelOrder(orderId: String): Order = request(HttpMethod.Post, ApiRoutes.orderCancel(orderId))

    /** Buyer of a PAID order reports a problem: PAID → DISPUTED (the payment stays on hold). */
    suspend fun openDispute(orderId: String, body: OpenDisputeRequest): Order =
        request(HttpMethod.Post, ApiRoutes.orderDispute(orderId)) { json(body) }

    /** Buyer withdraws their open dispute: DISPUTED → PAID (the auto-release clock starts over). */
    suspend fun withdrawDispute(orderId: String): Order = request(HttpMethod.Post, ApiRoutes.orderDisputeWithdraw(orderId))

    suspend fun rateOrder(orderId: String, body: CreateRatingRequest): Rating =
        request(HttpMethod.Post, ApiRoutes.orderRating(orderId)) { json(body) }

    suspend fun userRatings(userId: String, cursor: String? = null, limit: Int? = null): Page<Rating> =
        request(HttpMethod.Get, ApiRoutes.userRatings(userId)) { page(cursor, limit) }

    // ------------------------------------------------------------------------------------------------ reports & moderation

    suspend fun report(body: CreateReportRequest) {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.REPORTS) { json(body) }
    }

    /** Moderators only. [status] null = server default (OPEN). */
    suspend fun modReports(status: ReportStatus? = null, cursor: String? = null, limit: Int? = null): Page<Report> =
        request(HttpMethod.Get, ApiRoutes.MOD_REPORTS) {
            status?.let { parameter("status", it.name) }
            page(cursor, limit)
        }

    suspend fun modReport(reportId: String): Report = request(HttpMethod.Get, ApiRoutes.modReport(reportId))

    suspend fun resolveReport(reportId: String, body: ResolveReportRequest): Report =
        request(HttpMethod.Post, ApiRoutes.modReportResolve(reportId)) { json(body) }

    suspend fun suspendUser(userId: String, body: SuspendUserRequest) {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.modUserSuspend(userId)) { json(body) }
    }

    suspend fun unsuspendUser(userId: String, note: String = "") {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.modUserUnsuspend(userId)) { json(ModerationNoteRequest(note)) }
    }

    suspend fun removeListing(listingId: String, note: String = "") {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.modListingRemove(listingId)) { json(ModerationNoteRequest(note)) }
    }

    suspend fun restoreListing(listingId: String, note: String = "") {
        request<OkResponse>(HttpMethod.Post, ApiRoutes.modListingRestore(listingId)) { json(ModerationNoteRequest(note)) }
    }

    /** Admins only. Current moderators and admins (with emails). */
    suspend fun modStaff(): List<StaffMember> = request(HttpMethod.Get, ApiRoutes.MOD_STAFF)

    /** Admins only. Lookup by email or name (min 2 characters; at most 20 results). */
    suspend fun searchUsers(query: String): List<StaffMember> =
        request(HttpMethod.Get, ApiRoutes.MOD_USER_SEARCH) { parameter("q", query) }

    /** Admins only. USER <-> MODERATOR. Returns the updated member. */
    suspend fun setUserRole(userId: String, role: UserRole): StaffMember =
        request(HttpMethod.Put, ApiRoutes.modUserRole(userId)) { json(SetRoleRequest(role)) }

    suspend fun moderationLog(cursor: String? = null, limit: Int? = null): Page<ModerationLogEntry> =
        request(HttpMethod.Get, ApiRoutes.MOD_LOG) { page(cursor, limit) }

    /** Moderators only. [status] null = server default (OPEN, oldest first); CLOSED = most recently closed first. */
    suspend fun modDisputes(status: DisputeStatus? = null, cursor: String? = null, limit: Int? = null): Page<DisputeCase> =
        request(HttpMethod.Get, ApiRoutes.MOD_DISPUTES) {
            status?.let { parameter("status", it.name) }
            page(cursor, limit)
        }

    /** Moderators only. The order's most recent dispute. */
    suspend fun modDispute(orderId: String): DisputeCase = request(HttpMethod.Get, ApiRoutes.modDispute(orderId))

    /** Moderators only. Refund the buyer or release the payment to the seller; the note is required. */
    suspend fun resolveDispute(orderId: String, body: ResolveDisputeRequest): DisputeCase =
        request(HttpMethod.Post, ApiRoutes.modDisputeResolve(orderId)) { json(body) }

    // ------------------------------------------------------------------------------------------------ tokens

    /**
     * A currently valid access token (refreshing first if it expires within [REFRESH_SKEW_MS]), or null when signed
     * out. Throws [ApiError] NETWORK if a needed refresh couldn't reach the server. Used by [ChatSocket].
     */
    suspend fun freshAccessToken(): String? {
        val session = tokens.current() ?: return null
        if (session.accessTokenExpiresAt - REFRESH_SKEW_MS > nowMillis()) return session.accessToken
        return refreshTokens(staleAccessToken = session.accessToken)
    }

    /**
     * An access token other than [replacing] (the websocket's token is about to expire, or was rejected): refreshes
     * unless someone already has. Null if signed out. Used by [ChatSocket].
     */
    suspend fun refreshedAccessToken(replacing: String): String? = withContext(requestContext) { refreshTokens(staleAccessToken = replacing) }

    /**
     * Single-flight refresh. If another caller already replaced [staleAccessToken] while we waited for the lock, its
     * result is reused. Rejection (400/401/403) ends the session; other failures throw and keep the session.
     */
    private suspend fun refreshTokens(staleAccessToken: String): String? {
        refreshMutex.withLock {
            val current = tokens.current() ?: return null
            if (current.accessToken != staleAccessToken) return current.accessToken
            return doRefresh(current)
        }
    }

    private suspend fun doRefresh(current: StoredSession): String? {

        val response = send {
            method = HttpMethod.Post
            url(server.url(ApiRoutes.REFRESH))
            json(RefreshRequest(current.refreshToken))
        }
        return when {
            response.status.isSuccess() -> {
                val auth = decodeBody<AuthResponse>(response)
                tokens.save(auth.toStoredSession())
                auth.accessToken
            }
            response.status.value in REFRESH_REJECTED -> {
                val error = errorFrom(response)
                endSession(if (error.code == ErrorCodes.ACCOUNT_SUSPENDED) error else ApiError.sessionExpired())
                null
            }
            else -> throw errorFrom(response)
        }
    }

    private fun endSession(reason: ApiError) {
        tokens.current()?.user?.email?.let { sessionEndEmail = it }
        _sessionEndReason.value = reason
        tokens.clear()
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    private suspend inline fun <reified T> request(
        method: HttpMethod,
        path: String,
        auth: Boolean = true,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T = withContext(requestContext) {
        val response = execute(auth) { token ->
            this.method = method
            url(server.url(path))
            if (token != null) bearerAuth(token)
            block()
        }
        if (!response.status.isSuccess()) {
            val error = errorFrom(response)
            if (auth && error.code == ErrorCodes.ACCOUNT_SUSPENDED) endSession(error)
            throw error
        }
        decodeBody<T>(response)
    }

    /** Sends a request; for [auth] requests handles token freshness, 401 → refresh → retry once. */
    private suspend fun execute(auth: Boolean, configure: HttpRequestBuilder.(token: String?) -> Unit): HttpResponse {
        if (!auth) return send { configure(null) }

        val token = freshAccessToken() ?: throw ApiError.sessionExpired()
        val first = send { configure(token) }
        if (first.status != HttpStatusCode.Unauthorized) return first

        val refreshed = refreshTokens(staleAccessToken = token) ?: throw ApiError.sessionExpired()
        val second = send { configure(refreshed) }
        if (second.status == HttpStatusCode.Unauthorized) {
            // A brand-new token was still rejected: the account can't authenticate any more.
            endSession(ApiError.sessionExpired())
            throw ApiError.sessionExpired()
        }
        return second
    }

    // NB: don't name these lambdas `build`: HttpRequestBuilder.build() would shadow them inside request { }.
    private suspend fun send(configure: HttpRequestBuilder.() -> Unit): HttpResponse =
        try {
            http.request { configure() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError.network(e)
        }

    private suspend inline fun <reified T> decodeBody(response: HttpResponse): T =
        try {
            response.body<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiError.unexpected(e)
        }

    private suspend fun errorFrom(response: HttpResponse): ApiError {
        val status = response.status.value
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        val parsed = try {
            ApiJson.decodeFromString(ErrorResponse.serializer(), text)
        } catch (e: Exception) {
            null
        }
        return if (parsed != null) ApiError(parsed.code, parsed.message, parsed.fields, status) else ApiError.fromStatus(status)
    }

    private inline fun <reified B> HttpRequestBuilder.json(body: B) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.page(cursor: String?, limit: Int?) {
        cursor?.let { parameter("cursor", it) }
        limit?.let { parameter("limit", it) }
    }

    companion object {
        /** Refresh this long before the access token's stated expiry. */
        const val REFRESH_SKEW_MS = 30_000L
        private val REFRESH_REJECTED = setOf(400, 401, 403)
    }
}
