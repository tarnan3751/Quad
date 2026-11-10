package com.campusmarket.server.auth

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.LoginRequest
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.json
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class ProxyRateLimitAuditTest {
    private val tight = AuthRateLimits(window = 10.minutes, register = 100, verify = 100, resend = 100, login = 2, refresh = 100)

    private suspend fun ApiTestScope.loginVia(forwardedFor: String?) = client.post(ApiRoutes.LOGIN) {
        forwardedFor?.let { header("X-Forwarded-For", it) }
        json(LoginRequest("ghost@state.edu", "whatever1"))
    }.status

    @Test
    fun behindATrustedProxyEachClientHasItsOwnBudget() = authTest(testAuthSettings(rateLimits = tight.copy(trustedProxyHops = 1))) {
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, loginVia("203.0.113.7")) }
        assertEquals(HttpStatusCode.TooManyRequests, loginVia("203.0.113.7"))
        // a different client behind the same proxy is unaffected
        assertEquals(HttpStatusCode.Unauthorized, loginVia("198.51.100.4"))
        // a client can't escape its budget by sending its own X-Forwarded-For: the proxy appends the real address
        assertEquals(HttpStatusCode.TooManyRequests, loginVia("10.9.9.9, 203.0.113.7"))
    }

    @Test
    fun forwardedHeadersAreIgnoredUnlessAProxyIsConfigured() = authTest(testAuthSettings(rateLimits = tight)) {
        repeat(2) { i -> assertEquals(HttpStatusCode.Unauthorized, loginVia("203.0.113.$i")) }
        assertEquals(HttpStatusCode.TooManyRequests, loginVia("203.0.113.99"))
    }

    @Test
    fun clientAddressPicksTheEntryAppendedByTheOutermostTrustedProxy() {
        assertEquals("peer", clientAddress("peer", listOf("1.1.1.1"), trustedHops = 0))
        assertEquals("1.1.1.1", clientAddress("peer", listOf("1.1.1.1"), trustedHops = 1))
        assertEquals("2.2.2.2", clientAddress("peer", listOf("spoofed, 2.2.2.2"), trustedHops = 1))
        assertEquals("2.2.2.2", clientAddress("peer", listOf("spoofed, 2.2.2.2", "10.0.0.5"), trustedHops = 2))
        assertEquals("peer", clientAddress("peer", emptyList(), trustedHops = 1))
        assertEquals("peer", clientAddress("peer", listOf("1.1.1.1"), trustedHops = 2))
    }
}
