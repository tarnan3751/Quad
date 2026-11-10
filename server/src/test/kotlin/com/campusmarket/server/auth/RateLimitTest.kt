package com.campusmarket.server.auth

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.RefreshRequest
import com.campusmarket.core.ResendCodeRequest
import com.campusmarket.core.ApiJson
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.json
import com.campusmarket.server.module
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes

class RateLimitTest {

    private val tight = AuthRateLimits(window = 10.minutes, register = 2, verify = 3, resend = 2, login = 3, refresh = 2)

    @Test
    fun eachAuthEndpointIsLimitedPerClient() = authTest(testAuthSettings(rateLimits = tight)) {
        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, login("ghost@state.edu").status) }
        val limited = assertError(login("ghost@state.edu"), HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)
        assertEquals(ErrorCodes.RATE_LIMITED, limited.code)

        // budgets are per endpoint group: register is unaffected by the exhausted login budget
        repeat(2) { i -> assertEquals(HttpStatusCode.OK, register("user$i@state.edu").status) }
        assertError(register("user9@state.edu"), HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)

        repeat(3) { assertEquals(HttpStatusCode.BadRequest, verify("ghost@state.edu", "123456").status) }
        assertError(verify("ghost@state.edu", "123456"), HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)

        repeat(2) { assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("a@state.edu")) }.status) }
        assertError(client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("a@state.edu")) }, HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)

        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.post(ApiRoutes.REFRESH) { json(RefreshRequest("x")) }.status) }
        val res = client.post(ApiRoutes.REFRESH) { json(RefreshRequest("x")) }
        assertError(res, HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)
        assertNotNull(res.headers["Retry-After"] ?: res.headers["X-RateLimit-Reset"])
    }

    @Test
    fun rateLimitingCanBeDisabled() = authTest(testAuthSettings(rateLimits = tight.copy(enabled = false))) {
        repeat(10) { assertEquals(HttpStatusCode.Unauthorized, login("ghost@state.edu").status) }
    }

    @Test
    fun aCentrallyInstalledRateLimitIsReused() = testApplication {
        TestEnv.db
        TestEnv.reset()
        application {
            install(RateLimit) { registerAuthRateLimits(tight.copy(login = 1)) }
            attributes.put(AuthSettingsKey, testAuthSettings(rateLimits = tight))
            module(TestEnv.deps)
        }
        val scope = ApiTestScope(this, createClient { install(ContentNegotiation) { json(ApiJson) } })
        assertEquals(HttpStatusCode.Unauthorized, scope.login("ghost@state.edu").status)
        assertError(scope.login("ghost@state.edu"), HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)
    }

    @Test
    fun defaultApplicationWiringServesAuthRoutes() = apiTest {
        // the production path: settings from the environment, RateLimit installed by the auth feature itself
        assertEquals(HttpStatusCode.Unauthorized, login("ghost@state.edu").status)
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("ghost@state.edu")) }.status)
    }
}
