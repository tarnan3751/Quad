package com.campusmarket.server

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Regression tests for the cross-cutting fixes of the security / seams audit. */
class AuditFoundationTest {

    // ---------- request size guard

    @Test
    fun oversizedBodiesAreRejectedBeforeBeingRead() = apiTest {
        val huge = """{"email":"a@state.edu","password":"${"x".repeat(2 shl 20)}"}"""
        val res = client.post(ApiRoutes.LOGIN) { contentType(ContentType.Application.Json); setBody(huge) }
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertEquals(ErrorCodes.VALIDATION, res.body<ErrorResponse>().code)
    }

    /** Against the real Netty engine (the in-memory test engine never sends chunked bodies). */
    @Test
    fun chunkedBodiesAreRejectedByTheRealEngine() {
        TestEnv.db
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(TestEnv.deps) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            fun statusLine(request: String): String = Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 10_000
                s.getOutputStream().apply { write(request.toByteArray()); flush() }
                s.getInputStream().bufferedReader().readLine()
            }
            val body = """{"email":"a@state.edu","password":"whatever1"}"""
            val chunked = "POST ${ApiRoutes.LOGIN} HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n"
            assertTrue(statusLine(chunked).contains(" 411"), "chunked body")
            val declaredHuge = "POST ${ApiRoutes.LOGIN} HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${50L shl 20}\r\nConnection: close\r\n\r\n{"
            assertTrue(statusLine(declaredHuge).contains(" 413"), "declared oversized body")
            val normal = "POST ${ApiRoutes.LOGIN} HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            assertTrue(statusLine(normal).contains(" 401"), "ordinary body")
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun ordinaryBodiesStillWork() = apiTest {
        val res = client.post(ApiRoutes.LOGIN) { json(com.campusmarket.core.LoginRequest("ghost@state.edu", "whatever1")) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    // ---------- production config fails fast

    private val prodEnv = mapOf(
        "DEV_MODE" to "false",
        "JWT_SECRET" to "0123456789abcdef0123456789abcdef-prod",
        "STRIPE_SECRET_KEY" to "sk_live_abc",
        "STRIPE_WEBHOOK_SECRET" to "whsec_abc",
        "SMTP_HOST" to "smtp.example.edu",
    )

    @Test
    fun completeProductionConfigIsAccepted() {
        val cfg = AppConfig.fromEnv(prodEnv)
        assertFalse(cfg.devMode)
        assertEquals(emptyList(), cfg.productionProblems())
    }

    @Test
    fun productionConfigMissingPaymentsOrMailIsRefused() {
        for (missing in listOf("STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET", "SMTP_HOST", "JWT_SECRET")) {
            val e = assertFailsWith<IllegalArgumentException>(missing) { AppConfig.fromEnv(prodEnv - missing) }
            assertTrue(missing in e.message.orEmpty(), e.message)
        }
    }

    @Test
    fun liveStripeKeyInDevModeIsRefused() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnv(mapOf("STRIPE_SECRET_KEY" to "sk_live_abc")) }
        AppConfig.fromEnv(mapOf("STRIPE_SECRET_KEY" to "sk_test_abc")) // a test key is fine in dev
        AppConfig.fromEnv(emptyMap()) // plain local dev is fine
    }

    @Test
    fun platformFeeMustBeAFractionOfThePrice() {
        for (bps in listOf("-1", "10001", "20000")) {
            assertFailsWith<IllegalArgumentException>(bps) { AppConfig.fromEnv(mapOf("PLATFORM_FEE_BPS" to bps)) }
        }
        assertEquals(10_000, AppConfig.fromEnv(mapOf("PLATFORM_FEE_BPS" to "10000")).platformFeeBps)
    }

    @Test
    fun userRateLimitsCanBeSwitchedOff() {
        assertFalse(AppConfig.fromEnv(mapOf("USER_RATE_LIMITS_ENABLED" to "false")).userRateLimits.enabled)
        assertTrue(AppConfig.fromEnv(emptyMap()).userRateLimits.enabled)
    }

    // ---------- throttle

    @Test
    fun throttleIsAPerUserTokenBucket() {
        var now = 0L
        val t = UserThrottle(UserRateLimits(messages = Budget(3, 1.minutes)), nanoTime = { now })
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        repeat(3) { assertTrue(t.tryAcquire(UserThrottle.Action.MESSAGE, a)) }
        assertFalse(t.tryAcquire(UserThrottle.Action.MESSAGE, a))
        assertTrue(t.tryAcquire(UserThrottle.Action.MESSAGE, b), "budgets are per user")
        assertTrue(t.tryAcquire(UserThrottle.Action.REPORT, a), "and per action")
        now += 20.seconds.inWholeNanoseconds // a third of the period refills one token
        assertTrue(t.tryAcquire(UserThrottle.Action.MESSAGE, a))
        assertFalse(t.tryAcquire(UserThrottle.Action.MESSAGE, a))
        val e = assertFailsWith<ApiException> { t.acquire(UserThrottle.Action.MESSAGE, a, "slow down") }
        assertEquals(HttpStatusCode.TooManyRequests, e.status)
        assertEquals(ErrorCodes.RATE_LIMITED, e.code)
    }

    @Test
    fun disabledThrottleNeverLimits() {
        val t = UserThrottle(UserRateLimits(enabled = false, messages = Budget(1, 1.minutes)))
        val a = UUID.randomUUID()
        repeat(10) { assertTrue(t.tryAcquire(UserThrottle.Action.MESSAGE, a)) }
    }

    // ---------- text sanitising

    @Test
    fun controlCharactersAreDroppedButLineBreaksKept() {
        assertEquals("ab\nc\td\ne", "a\u0000b\r\nc\td\re\u0007".withoutControlChars())
    }

    @Test
    fun invisibleFormattingIsDroppedButJoinersKept() {
        val family = "👨‍👩‍👧" // ZWJ emoji sequence
        assertEquals("Admin $family", "Ad​min‮ ⁦$family⁩﻿­".withoutInvisibleFormatChars())
        assertTrue(isInvisibleFormatChar(0x061C)) // Arabic letter mark (bidi)
        assertFalse(isInvisibleFormatChar(0x200D))
        assertFalse(isInvisibleFormatChar('a'.code))
    }
}
