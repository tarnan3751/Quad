package com.campusmarket.app.data

import com.campusmarket.core.ErrorCodes

/**
 * The only exception type [ApiClient] throws (besides coroutine cancellation).
 *
 * - [code] is one of [ErrorCodes] for server errors, or [NETWORK] / [UNEXPECTED] / [SESSION_EXPIRED] for client-side failures.
 * - [message] is human-readable and safe to show to the user.
 * - [fields] holds per-field validation messages (400 `VALIDATION`), keyed by request property name
 *   (e.g. `"email"`, `"priceCents"`); show them under the matching form field via [field].
 * - [status] is the HTTP status, or null when no response was received.
 */
class ApiError(
    val code: String,
    override val message: String,
    val fields: Map<String, String> = emptyMap(),
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Validation message for one request field, if the server reported one. */
    fun field(name: String): String? = fields[name]

    val isNetwork: Boolean get() = code == NETWORK
    val isValidation: Boolean get() = code == ErrorCodes.VALIDATION
    val isNotFound: Boolean get() = code == ErrorCodes.NOT_FOUND
    val isSessionExpired: Boolean get() = code == SESSION_EXPIRED

    override fun toString(): String = "ApiError(code=$code, status=$status, message=$message, fields=$fields)"

    companion object {
        /** No response: offline, DNS, timeout, connection refused. */
        const val NETWORK = "NETWORK"

        /** A response arrived but couldn't be understood (bad JSON, wrong shape). */
        const val UNEXPECTED = "UNEXPECTED"

        /** The user is (now) signed out: no tokens, or the refresh token was rejected. The app has already navigated to sign-in. */
        const val SESSION_EXPIRED = "SESSION_EXPIRED"

        fun network(cause: Throwable? = null) =
            ApiError(NETWORK, "Can't reach Quad. Check your connection and try again.", cause = cause)

        fun unexpected(cause: Throwable? = null) =
            ApiError(UNEXPECTED, "Something went wrong. Please try again.", cause = cause)

        fun sessionExpired() = ApiError(SESSION_EXPIRED, "Your session has ended. Please sign in again.", status = 401)

        /** Fallback mapping when a non-2xx response has no parseable [com.campusmarket.core.ErrorResponse]. */
        fun fromStatus(status: Int): ApiError = when (status) {
            400 -> ApiError(ErrorCodes.VALIDATION, "That request wasn't valid.", status = status)
            401 -> ApiError(ErrorCodes.UNAUTHORIZED, "Please sign in again.", status = status)
            403 -> ApiError(ErrorCodes.FORBIDDEN, "You're not allowed to do that.", status = status)
            404 -> ApiError(ErrorCodes.NOT_FOUND, "We couldn't find that.", status = status)
            409 -> ApiError(ErrorCodes.CONFLICT, "That conflicts with the current state. Refresh and try again.", status = status)
            429 -> ApiError(ErrorCodes.RATE_LIMITED, "Too many requests. Slow down and try again shortly.", status = status)
            else -> ApiError(ErrorCodes.INTERNAL, "The server had a problem. Please try again.", status = status)
        }
    }
}

/** Wraps any throwable into an [ApiError] (non-ApiErrors become [ApiError.UNEXPECTED]). Never use on CancellationException. */
fun Throwable.asApiError(): ApiError = this as? ApiError ?: ApiError.unexpected(this)
