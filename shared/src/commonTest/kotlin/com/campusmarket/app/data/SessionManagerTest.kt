package com.campusmarket.app.data

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.UserPrivate
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    @Test
    fun sessionEndedByApiClientSignsOut() = runTest {
        val h = Harness { respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED) }
        val sm = SessionManager(h.api, h.tokens, backgroundScope)
        assertIs<SessionState.SignedIn>(sm.state.value)

        assertFailsWith<ApiError> { h.api.me() }
        runCurrent()

        assertEquals(SessionState.SignedOut, sm.state.value)
        assertEquals(ApiError.SESSION_EXPIRED, sm.signOutReason.value?.code)
    }


    @Test
    fun anUnchangedProfileIsNotWrittenAgain() = runTest {
        val h = Harness { error("no request") }
        var saves = 0
        val tokens = object : TokenStore by h.tokens {
            override fun save(session: StoredSession) {
                saves++
                h.tokens.save(session)
            }
        }
        val sm = SessionManager(h.api, tokens, backgroundScope)

        sm.updateUser(testUser.copy())
        assertEquals(0, saves)

        sm.updateUser(testUser.copy(bio = "Selling my old desk"))
        assertEquals(1, saves)
        assertEquals("Selling my old desk", sm.currentUser?.bio)
    }

    @Test
    fun restoresSignedInStateSynchronouslyFromStorage() = runTest {
        val h = Harness { error("no request") }
        val sm = SessionManager(h.api, h.tokens, backgroundScope)
        assertEquals(SessionState.SignedIn(testUser), sm.state.value)
    }

    @Test
    fun loginStoresTokensAndSignsIn() = runTest {
        val h = Harness(initial = null) { respondJson(AuthResponse.serializer(), authResponse("a2", "r2")) }
        val sm = SessionManager(h.api, h.tokens, backgroundScope)
        assertEquals(SessionState.SignedOut, sm.state.value)

        sm.login(" ada@uni.edu ", "password1")

        assertEquals("a2", h.tokens.current()?.accessToken)
        assertIs<SessionState.SignedIn>(sm.state.value)
    }

    @Test
    fun failedLoginThrowsAndStaysSignedOut() = runTest {
        val h = Harness(initial = null) { respondError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "Wrong email or password") }
        val sm = SessionManager(h.api, h.tokens, backgroundScope)
        val e = assertFailsWith<ApiError> { sm.login("ada@uni.edu", "nope") }
        assertEquals(ErrorCodes.INVALID_CREDENTIALS, e.code)
        assertNull(h.tokens.current())
        assertNull(h.requests.single().headers[HttpHeaders.Authorization], "login must not try to refresh or send a token")
    }

    @Test
    fun signOutRevokesRefreshTokenAndClears() = runTest {
        val h = Harness { respond("{\"ok\":true}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val sm = SessionManager(h.api, h.tokens, backgroundScope)

        sm.signOut()

        assertNull(h.tokens.current())
        assertEquals(ApiRoutes.LOGOUT, h.requests.single().url.encodedPath)
    }

    @Test
    fun signOutSucceedsOffline() = runTest {
        val h = Harness { throw kotlinx.io.IOException("offline") }
        val sm = SessionManager(h.api, h.tokens, backgroundScope)
        sm.signOut()
        assertNull(h.tokens.current())
    }

    @Test
    fun updateUserKeepsTokens() = runTest {
        val h = Harness { respondJson(UserPrivate.serializer(), testUser.copy(bio = "hi")) }
        val sm = SessionManager(h.api, h.tokens, backgroundScope)
        sm.refreshUser()
        assertEquals("hi", h.tokens.current()?.user?.bio)
        assertEquals("access-1", h.tokens.current()?.accessToken)
    }
}
