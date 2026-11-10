package com.campusmarket.server.auth

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.ResendCodeRequest
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserRole
import com.campusmarket.server.AppDeps
import com.campusmarket.server.TestEnv
import com.campusmarket.server.json
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RegisterVerifyTest {

    // ---------- register ----------

    @Test
    fun registerCreatesUnverifiedUserAndSendsHashedCode() {
        val settings = testAuthSettings()
        authTest(settings) {
            val res = register("  Alice.Doe@State.EDU ", displayName = "  Alice   Doe ")
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.body<RegisterResponse>()
            assertEquals("alice.doe@state.edu", body.email)
            assertTrue(body.verificationRequired)
            val code = assertNotNull(body.devCode)
            assertTrue(Regex("\\d{6}").matches(code))

            val mail = settings.recorder.next()
            assertEquals("alice.doe@state.edu", mail.to)
            assertTrue(code in mail.text)
            assertFalse("Alice" in mail.text, "user-controlled text must not be echoed into the email")

            val email = "alice.doe@state.edu"
            assertEquals(email, userColumn(email, "email"))
            assertEquals("Alice Doe", userColumn(email, "display_name"))
            assertEquals("state.edu", userColumn(email, "campus_domain"))
            assertEquals(false, userColumn(email, "email_verified"))
            assertEquals("USER", userColumn(email, "role"))
            val hash = userColumn(email, "password_hash") as String
            assertTrue(hash.startsWith("\$2a\$04\$"), hash)
            val codeHash = userColumn(email, "verification_code_hash") as String
            assertNotEquals(code, codeHash)
            assertFalse(code in codeHash)
            assertEquals(64, codeHash.length)
        }
    }

    @Test
    fun registerValidatesEveryField() = authTest {
        val res = register("not-an-email", password = "short", displayName = " x ")
        val err = assertError(res, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertEquals(setOf("email", "password", "displayName"), err.fields.keys)

        val tooLong = register("bob@state.edu", password = "p".repeat(129), displayName = "B".repeat(41))
        assertEquals(setOf("password", "displayName"), assertError(tooLong, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION).fields.keys)

        val spaces = register("bob@state.edu", password = " ".repeat(10))
        assertEquals(setOf("password"), assertError(spaces, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION).fields.keys)

        val bidi = register("bob@state.edu", displayName = "Bob‮gnp.exe")
        assertEquals(setOf("displayName"), assertError(bidi, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION).fields.keys)

        for (bad in listOf("a@b", "a..b@state.edu", ".a@state.edu", "a@-state.edu", "a b@state.edu", "a@state.edu.", "@state.edu")) {
            assertEquals(setOf("email"), assertError(register(bad), HttpStatusCode.BadRequest, ErrorCodes.VALIDATION).fields.keys, bad)
        }
        // long passwords (> bcrypt's 72 bytes) are fine
        assertEquals(HttpStatusCode.OK, register("long@state.edu", password = "é".repeat(128)).status)
    }

    @Test
    fun registerRejectsNonCampusDomains() = authTest {
        for (email in listOf("a@gmail.com", "a@edu.evil.com", "a@stateedu.com", "a@state.edu.evil.com")) {
            val err = assertError(register(email), HttpStatusCode.BadRequest, ErrorCodes.EMAIL_NOT_ALLOWED)
            assertTrue("email" in err.fields, email)
        }
        assertEquals(HttpStatusCode.OK, register("a@cs.state.edu").status)
    }

    @Test
    fun allowedSuffixesCanBeSpecificDomains() =
        authTest(deps = AppDeps(TestEnv.config.copy(allowedEmailSuffixes = listOf("mit.edu", ".ac.uk")), TestEnv.db)) {
            assertEquals(HttpStatusCode.OK, register("a@mit.edu").status)
            assertEquals(HttpStatusCode.OK, register("b@alum.mit.edu").status)
            assertEquals(HttpStatusCode.OK, register("c@ox.ac.uk").status)
            assertError(register("d@notmit.edu"), HttpStatusCode.BadRequest, ErrorCodes.EMAIL_NOT_ALLOWED)
            assertError(register("e@state.edu"), HttpStatusCode.BadRequest, ErrorCodes.EMAIL_NOT_ALLOWED)
        }

    @Test
    fun verifiedDuplicateIsTaken() = authTest {
        signUp("alice@state.edu")
        assertError(register("ALICE@state.edu "), HttpStatusCode.Conflict, ErrorCodes.EMAIL_TAKEN)
    }

    @Test
    fun unverifiedDuplicateIsOverwrittenAndGetsNewCode() = authTest {
        val first = register("alice@state.edu", password = "first password", displayName = "First").body<RegisterResponse>().devCode!!
        val second = register("Alice@State.edu", password = "second password", displayName = "Second").body<RegisterResponse>().devCode!!
        assertEquals("Second", userColumn("alice@state.edu", "display_name"))
        if (first != second) assertError(verify("alice@state.edu", first, "second password"), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
        val session = verify("alice@state.edu", second, "second password").body<AuthResponse>()
        assertEquals("Second", session.user.displayName)
        assertError(login("alice@state.edu", "first password"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        assertEquals(HttpStatusCode.OK, login("alice@state.edu", "second password").status)
    }

    @Test
    fun concurrentRegistrationsOfOneEmailSettleOnOneAccount() = authTest {
        val results = coroutineScope {
            (1..4).map { i -> async { register("race@state.edu", displayName = "Racer $i").status } }.map { it.await() }
        }
        assertEquals(List(4) { HttpStatusCode.OK }, results)
        val count = TestEnv.db.txBlocking { c ->
            c.prepareStatement("SELECT count(*) FROM users WHERE lower(email) = 'race@state.edu'").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(1, count)
    }

    @Test
    fun outsideDevModeTheCodeIsOnlyEmailed() {
        val settings = testAuthSettings()
        authTest(settings, deps = AppDeps(TestEnv.config.copy(devMode = false), TestEnv.db)) {
            val body = register("alice@state.edu").body<RegisterResponse>()
            assertNull(body.devCode)
            val code = Regex("\\b(\\d{6})\\b").find(settings.recorder.next().text)!!.groupValues[1]
            assertEquals(HttpStatusCode.OK, verify("alice@state.edu", code).status)
        }
    }

    @Test
    fun malformedBodiesAreValidationErrors() = authTest {
        val notJson = client.post(ApiRoutes.REGISTER) { contentType(ContentType.Application.Json); setBody("{nope") }
        assertError(notJson, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        val missing = client.post(ApiRoutes.REGISTER) { contentType(ContentType.Application.Json); setBody("""{"email":"a@state.edu"}""") }
        assertError(missing, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        val wrongType = client.post(ApiRoutes.LOGIN) { contentType(ContentType.Text.Plain); setBody("hello") }
        assertError(wrongType, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
    }

    // ---------- verify ----------

    @Test
    fun verifyIssuesSessionAndMarksVerified() = authTest {
        val code = register("alice@state.edu").body<RegisterResponse>().devCode!!
        val res = verify(" Alice@STATE.edu", code.substring(0, 3) + " " + code.substring(3))
        assertEquals(HttpStatusCode.OK, res.status)
        val session = res.body<AuthResponse>()
        assertEquals("alice@state.edu", session.user.email)
        assertEquals(UserRole.USER, session.user.role)
        assertTrue(session.refreshToken.length >= 43)
        assertTrue(session.accessTokenExpiresAt > System.currentTimeMillis())
        assertEquals(true, userColumn("alice@state.edu", "email_verified"))
        assertNull(userColumn("alice@state.edu", "verification_code_hash"))

        val me = client.get(ApiRoutes.ME) { bearerAuth(session.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(session.user, me.body<UserPrivate>())

        // the code is single-use
        assertError(verify("alice@state.edu", code), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
    }

    @Test
    fun wrongCodesAreLimitedToFiveAttempts() = authTest {
        val code = register("alice@state.edu").body<RegisterResponse>().devCode!!
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        repeat(4) { i ->
            assertError(verify("alice@state.edu", wrong), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
            assertEquals(i + 1, userColumn("alice@state.edu", "verification_attempts"))
        }
        assertError(verify("alice@state.edu", wrong), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
        // five failures burn the code: even the right one no longer works
        assertNull(userColumn("alice@state.edu", "verification_code_hash"))
        assertError(verify("alice@state.edu", code), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)

        // a fresh code works again
        val fresh = client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("alice@state.edu")) }.body<RegisterResponse>().devCode!!
        assertEquals(0, userColumn("alice@state.edu", "verification_attempts"))
        assertEquals(HttpStatusCode.OK, verify("alice@state.edu", fresh).status)
    }

    @Test
    fun correctCodeWithWrongPasswordIsInvalidCodeAndCountsAsAttempt() = authTest {
        val code = register("alice@state.edu").body<RegisterResponse>().devCode!!
        val wrongPassword = assertError(verify("alice@state.edu", code, "not the password"), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
        val wrongCode = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertEquals(wrongPassword, assertError(verify("alice@state.edu", wrongCode), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE))
        assertEquals(false, userColumn("alice@state.edu", "email_verified"))
        assertEquals(2, userColumn("alice@state.edu", "verification_attempts"))
        // empty and over-long passwords behave the same
        assertError(verify("alice@state.edu", code, ""), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
        assertError(verify("alice@state.edu", code, "p".repeat(500)), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
        // a missing password field is a malformed request, never a success
        val missing = client.post(ApiRoutes.VERIFY_EMAIL) {
            contentType(ContentType.Application.Json); setBody("""{"email":"alice@state.edu","code":"$code"}""")
        }
        assertError(missing, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertEquals(false, userColumn("alice@state.edu", "email_verified"))
        // the right password with the right code still works (attempts were not exhausted)
        assertEquals(HttpStatusCode.OK, verify("alice@state.edu", code).status)
    }

    @Test
    fun preHijackIsBlocked() {
        val settings = testAuthSettings()
        authTest(settings) {
            val email = "victim@state.edu"
            val attackerPw = "attacker password"
            val victimPw = "victim password"
            // attacker registers the victim's address, the victim (unaware) registers too, the attacker overwrites again
            register(email, attackerPw, "Attacker").body<RegisterResponse>()
            val victimCode = register(email, victimPw, "Victim").body<RegisterResponse>().devCode!!
            val attackerCode = register(email, attackerPw, "Attacker").body<RegisterResponse>().devCode!!
            settings.recorder.drain()

            // the attacker never sees the inbox: without the code they can't verify (nor guess it within 5 tries)
            val wrongCode = ((attackerCode.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
            assertError(verify(email, wrongCode, attackerPw), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
            // the victim's code is stale (the attacker's re-registration replaced it), and the victim's password
            // no longer matches the pending account either
            if (victimCode != attackerCode) assertError(verify(email, victimCode, victimPw), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
            // even holding the code that is currently valid, the victim's password is rejected: nothing is verified
            assertError(verify(email, attackerCode, victimPw), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
            assertEquals(false, userColumn(email, "email_verified"))

            // the victim re-registers (password and name are theirs again) and verifies with the code from their inbox
            val freshCode = register(email, victimPw, "Victim").body<RegisterResponse>().devCode!!
            // the attacker's password is now useless even with the right code
            assertError(verify(email, freshCode, attackerPw), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
            val session = verify(email, freshCode, victimPw).body<AuthResponse>()
            assertEquals("Victim", session.user.displayName)
            assertEquals(true, userColumn(email, "email_verified"))
            assertError(login(email, attackerPw), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
            assertEquals(HttpStatusCode.OK, login(email, victimPw).status)
        }
    }

    @Test
    fun malformedCodesAreInvalidWithoutUsingAnAttempt() = authTest {
        register("alice@state.edu")
        for (bad in listOf("", "12345", "1234567", "abcdef", "12345a")) {
            assertError(verify("alice@state.edu", bad), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
        }
        assertEquals(0, userColumn("alice@state.edu", "verification_attempts"))
    }

    @Test
    fun expiredCodeIsInvalid() = authTest { env ->
        val code = register("alice@state.edu").body<RegisterResponse>().devCode!!
        env.db.tx { c -> c.update("UPDATE users SET verification_expires_at = ? WHERE email = ?", Instant.now().minusSeconds(1), "alice@state.edu") }
        assertError(verify("alice@state.edu", code), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
    }

    @Test
    fun codeExpiresAfterFifteenMinutes() = authTest {
        register("alice@state.edu")
        val expires = (userColumn("alice@state.edu", "verification_expires_at") as java.sql.Timestamp).toInstant()
        val minutes = java.time.Duration.between(Instant.now(), expires).toSeconds() / 60.0
        assertTrue(minutes in 14.9..15.0, "expires in $minutes minutes")
    }

    @Test
    fun unknownAndAlreadyVerifiedEmailsGetTheSameInvalidCode() = authTest { env ->
        val unknown = assertError(verify("ghost@state.edu", "123456"), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
        val fixture = env.user("bob")
        val verified = assertError(verify(fixture.email, "123456"), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
        assertEquals(unknown, verified)
    }

    @Test
    fun adminEmailsBecomeAdminOnVerification() =
        authTest(deps = AppDeps(TestEnv.config.copy(adminEmails = setOf("dean@state.edu")), TestEnv.db)) {
            assertEquals(UserRole.ADMIN, signUp("Dean@State.edu").user.role)
            assertEquals(UserRole.USER, signUp("student@state.edu").user.role)
        }

    // ---------- resend ----------

    @Test
    fun resendNeverRevealsWhetherAnAccountExists() {
        val settings = testAuthSettings()
        authTest(settings) { env ->
            val verified = env.user("bob")
            val unknown = client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("ghost@state.edu")) }
            val known = client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest(verified.email)) }
            assertEquals(HttpStatusCode.OK, unknown.status)
            assertEquals(HttpStatusCode.OK, known.status)
            assertEquals(RegisterResponse("ghost@state.edu", true, null), unknown.body<RegisterResponse>())
            assertEquals(RegisterResponse(verified.email, true, null), known.body<RegisterResponse>())
            delay(200)
            assertEquals(emptyList(), settings.recorder.drain())
        }
    }

    @Test
    fun resendReplacesTheCode() {
        val settings = testAuthSettings()
        authTest(settings) {
            val old = register("alice@state.edu").body<RegisterResponse>().devCode!!
            settings.recorder.next()
            val res = client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest(" ALICE@state.edu")) }
            val fresh = res.body<RegisterResponse>().devCode!!
            assertTrue(fresh in settings.recorder.next().text)
            if (old != fresh) assertError(verify("alice@state.edu", old), HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE)
            assertEquals(HttpStatusCode.OK, verify("alice@state.edu", fresh).status)
        }
    }

    @Test
    fun resendRejectsMalformedEmail() = authTest {
        val res = client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("nope")) }
        assertEquals(setOf("email"), assertError(res, HttpStatusCode.BadRequest, ErrorCodes.VALIDATION).fields.keys)
    }

    @Test
    fun resendHasACooldown() {
        val settings = testAuthSettings(resendCooldown = 30.seconds)
        authTest(settings) { env ->
            register("alice@state.edu")
            settings.recorder.next()
            val during = client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("alice@state.edu")) }
            assertEquals(HttpStatusCode.OK, during.status)
            assertNull(during.body<RegisterResponse>().devCode)

            env.db.tx { c -> c.update("UPDATE users SET verification_sent_at = ? WHERE email = ?", Instant.now().minusSeconds(31), "alice@state.edu") }
            val after = client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("alice@state.edu")) }
            assertNotNull(after.body<RegisterResponse>().devCode)
            settings.recorder.next()
            assertEquals(emptyList(), settings.recorder.drain())
        }
    }

    @Test
    fun codesPerAccountPerDayAreCapped() {
        val settings = testAuthSettings(maxCodesPerDay = 2)
        authTest(settings) { env ->
            register("alice@state.edu")                                                             // 1
            client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("alice@state.edu")) }       // 2
            val capped = client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("alice@state.edu")) }
            assertEquals(HttpStatusCode.OK, capped.status)
            assertNull(capped.body<RegisterResponse>().devCode)
            assertError(register("alice@state.edu"), HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)

            // the window rolls over after 24h
            env.db.tx { c -> c.update("UPDATE users SET verification_window_start = ? WHERE email = ?", Instant.now().minusSeconds(86_401), "alice@state.edu") }
            assertNotNull(client.post(ApiRoutes.RESEND_CODE) { json(ResendCodeRequest("alice@state.edu")) }.body<RegisterResponse>().devCode)
        }
    }
}
