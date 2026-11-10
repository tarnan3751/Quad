package com.campusmarket.app.data

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.RefreshRequest
import com.campusmarket.core.UserPrivate
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ApiClientAuthTest {

    @Test
    fun attachesBearerTokenToAuthenticatedCalls() = runTest {
        val h = Harness { respondJson(UserPrivate.serializer(), testUser) }
        h.api.me()
        assertEquals("access-1", h.requests.single().bearer)
    }

    @Test
    fun publicCallsSendNoAuthorizationHeader() = runTest {
        val h = Harness { respondJson(AuthResponse.serializer(), authResponse("a", "r")) }
        h.api.login(com.campusmarket.core.LoginRequest("ada@uni.edu", "password1"))
        assertNull(h.requests.single().bearer)
    }

    @Test
    fun unauthorizedTriggersRefreshAndRetriesWithNewToken() = runTest {
        val h = Harness { req ->
            when (req.url.encodedPath) {
                ApiRoutes.REFRESH -> {
                    val body = ApiJson.decodeFromString(RefreshRequest.serializer(), req.body.toByteArray().decodeToString())
                    assertEquals("refresh-1", body.refreshToken)
                    respondJson(AuthResponse.serializer(), authResponse("access-2", "refresh-2"))
                }
                ApiRoutes.ME ->
                    if (req.bearer == "access-2") respondJson(UserPrivate.serializer(), testUser.copy(displayName = "Ada L"))
                    else respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
                else -> error("unexpected ${req.url}")
            }
        }

        val me = h.api.me()

        assertEquals("Ada L", me.displayName)
        assertEquals(listOf("access-1", "access-2"), h.requestsTo(ApiRoutes.ME).map { it.bearer })
        assertEquals(1, h.requestsTo(ApiRoutes.REFRESH).size)
        assertEquals("access-2", h.tokens.current()?.accessToken)
        assertEquals("refresh-2", h.tokens.current()?.refreshToken)
    }

    @Test
    fun concurrentUnauthorizedCallsShareOneRefresh() = runTest {
        val refreshMayAnswer = CompletableDeferred<Unit>()
        val h = Harness { req ->
            when (req.url.encodedPath) {
                ApiRoutes.REFRESH -> {
                    refreshMayAnswer.await() // keep the refresh in flight while the other callers hit 401
                    respondJson(AuthResponse.serializer(), authResponse("access-2", "refresh-2"))
                }
                else ->
                    if (req.bearer == "access-2") respondJson(UserPrivate.serializer(), testUser)
                    else respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
            }
        }

        val calls = (1..5).map { async { h.api.me() } }
        runCurrent() // all five got their 401 and wait for the one refresh
        refreshMayAnswer.complete(Unit)
        val results = calls.awaitAll()

        assertEquals(5, results.size)
        assertEquals(1, h.requestsTo(ApiRoutes.REFRESH).size, "refresh must be single-flight")
        assertTrue(h.requestsTo(ApiRoutes.ME).filter { it.bearer == "access-2" }.size == 5)
    }

    @Test
    fun rejectedRefreshSignsOutAndThrowsSessionExpired() = runTest {
        val h = Harness { req ->
            when (req.url.encodedPath) {
                ApiRoutes.REFRESH -> respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Invalid refresh token")
                else -> respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
            }
        }

        val error = assertFailsWith<ApiError> { h.api.me() }

        assertEquals(ApiError.SESSION_EXPIRED, error.code)
        assertNull(h.tokens.current(), "session must be cleared")
        assertEquals(ApiError.SESSION_EXPIRED, h.api.sessionEndReason.value?.code)
        // Further calls fail fast without touching the network.
        val before = h.requests.size
        assertFailsWith<ApiError> { h.api.me() }
        assertEquals(before, h.requests.size)
    }

    @Test
    fun refreshServerErrorKeepsSession() = runTest {
        val h = Harness { req ->
            when (req.url.encodedPath) {
                ApiRoutes.REFRESH -> respond("upstream down", HttpStatusCode.BadGateway)
                else -> respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
            }
        }

        val error = assertFailsWith<ApiError> { h.api.me() }

        assertEquals(ErrorCodes.INTERNAL, error.code)
        assertEquals(502, error.status)
        assertEquals("access-1", h.tokens.current()?.accessToken, "transient refresh failure must not sign out")
    }

    @Test
    fun stillUnauthorizedAfterRefreshSignsOut() = runTest {
        val h = Harness { req ->
            when (req.url.encodedPath) {
                ApiRoutes.REFRESH -> respondJson(AuthResponse.serializer(), authResponse("access-2", "refresh-2"))
                else -> respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
            }
        }

        val error = assertFailsWith<ApiError> { h.api.me() }

        assertEquals(ApiError.SESSION_EXPIRED, error.code)
        assertNull(h.tokens.current())
    }

    @Test
    fun refreshesProactivelyWhenTokenIsAboutToExpire() = runTest {
        val h = Harness(initial = session(expiresAt = NOW + 10_000)) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.REFRESH -> respondJson(AuthResponse.serializer(), authResponse("access-2", "refresh-2"))
                else -> respondJson(UserPrivate.serializer(), testUser)
            }
        }

        h.api.me()

        assertEquals(listOf(ApiRoutes.REFRESH, ApiRoutes.ME), h.requests.map { it.url.encodedPath })
        assertEquals("access-2", h.requestsTo(ApiRoutes.ME).single().bearer)
    }

    @Test
    fun noSessionFailsFastWithSessionExpired() = runTest {
        val h = Harness(initial = null) { error("no request expected") }
        val error = assertFailsWith<ApiError> { h.api.me() }
        assertEquals(ApiError.SESSION_EXPIRED, error.code)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun suspendedAccountEndsSession() = runTest {
        val h = Harness { respondError(HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED, "Your account is suspended: spam") }

        val error = assertFailsWith<ApiError> { h.api.me() }

        assertEquals(ErrorCodes.ACCOUNT_SUSPENDED, error.code)
        assertNull(h.tokens.current())
        assertEquals("Your account is suspended: spam", h.api.sessionEndReason.value?.message)
    }

    @Test
    fun forbiddenWithOtherCodeKeepsSession() = runTest {
        val h = Harness { respondError(HttpStatusCode.Forbidden, ErrorCodes.BLOCKED, "Blocked") }
        val error = assertFailsWith<ApiError> { h.api.sendMessage("c1", "hi") }
        assertEquals(ErrorCodes.BLOCKED, error.code)
        assertEquals(403, error.status)
        assertEquals("access-1", h.tokens.current()?.accessToken)
    }
}
