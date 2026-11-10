package com.campusmarket.server.auth

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.LogoutRequest
import com.campusmarket.core.OkResponse
import com.campusmarket.core.RefreshRequest
import com.campusmarket.core.UserRole
import com.campusmarket.server.AppDeps
import com.campusmarket.server.TestEnv
import com.campusmarket.server.json
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginSessionTest {

    private suspend fun com.campusmarket.server.ApiTestScope.refresh(token: String): HttpResponse =
        client.post(ApiRoutes.REFRESH) { json(RefreshRequest(token)) }

    private suspend fun com.campusmarket.server.ApiTestScope.logout(token: String): HttpResponse =
        client.post(ApiRoutes.LOGOUT) { json(LogoutRequest(token)) }

    private fun activeTokens(email: String): Int = TestEnv.db.txBlocking { c ->
        c.queryOne(
            "SELECT count(*) FROM refresh_tokens t JOIN users u ON u.id = t.user_id WHERE u.email = ? AND t.revoked_at IS NULL",
            email,
        ) { it.getInt(1) }!!
    }

    // ---------- login ----------

    @Test
    fun loginWithNormalisedEmail() = authTest {
        val signup = signUp("alice@state.edu")
        val res = login("  ALICE@State.Edu ")
        assertEquals(HttpStatusCode.OK, res.status)
        val session = res.body<AuthResponse>()
        assertEquals(signup.user.id, session.user.id)
        assertNotEquals(signup.refreshToken, session.refreshToken)
        assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.ME) { bearerAuth(session.accessToken) }.status)
        assertEquals(2, activeTokens("alice@state.edu"))
    }

    @Test
    fun wrongPasswordAndUnknownEmailLookTheSame() = authTest { env ->
        signUp("alice@state.edu")
        val wrong = assertError(login("alice@state.edu", "not the password"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        val unknown = assertError(login("ghost@state.edu"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        val malformed = assertError(login("ghost"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        val fixture = assertError(login(env.user("bob").email), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS) // invalid stored hash
        assertEquals(wrong, unknown)
        assertEquals(wrong, malformed)
        assertEquals(wrong, fixture)
        // password is case-sensitive and not trimmed
        assertError(login("alice@state.edu", PASSWORD.uppercase()), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        assertError(login("alice@state.edu", " $PASSWORD"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
    }

    @Test
    fun blankFieldsAreValidationErrors() = authTest {
        val err = assertError(login(" ", ""), HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertEquals(setOf("email", "password"), err.fields.keys)
    }

    @Test
    fun unverifiedAccountsMustVerifyFirst() = authTest {
        register("alice@state.edu")
        assertError(login("alice@state.edu"), HttpStatusCode.Forbidden, ErrorCodes.EMAIL_NOT_VERIFIED)
        // without the right password nothing about the account is revealed
        assertError(login("alice@state.edu", "wrong password"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
    }

    @Test
    fun suspendedAccountsCannotLogIn() = authTest { env ->
        signUp("alice@state.edu")
        env.db.tx { c -> c.update("UPDATE users SET suspended = true, suspension_reason = 'scamming' WHERE email = ?", "alice@state.edu") }
        val err = assertError(login("alice@state.edu"), HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED)
        assertTrue("scamming" in err.message)
        assertError(login("alice@state.edu", "wrong password"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)

        // a timed suspension that has run out no longer applies
        env.db.tx { c -> c.update("UPDATE users SET suspended_until = ? WHERE email = ?", Instant.now().minusSeconds(60), "alice@state.edu") }
        assertEquals(HttpStatusCode.OK, login("alice@state.edu").status)
    }

    @Test
    fun adminEmailIsPromotedOnLogin() =
        authTest(deps = AppDeps(TestEnv.config.copy(adminEmails = setOf("DEAN@state.edu")), TestEnv.db)) { env ->
            signUp("dean@state.edu")
            // e.g. ADMIN_EMAILS was changed after the account was verified
            env.db.tx { c -> c.update("UPDATE users SET role = 'USER' WHERE email = 'dean@state.edu'") }
            assertEquals(UserRole.ADMIN, login("dean@state.edu").body<AuthResponse>().user.role)
            signUp("student@state.edu")
            assertEquals(UserRole.USER, login("student@state.edu").body<AuthResponse>().user.role)
        }

    @Test
    fun weakerHashesAreUpgradedOnLogin() = authTest(testAuthSettings(bcryptCost = 5)) { env ->
        val id = UUID.randomUUID()
        val cost4 = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray())
        env.db.tx { c ->
            c.update(
                "INSERT INTO users (id, email, password_hash, display_name, campus_domain, email_verified) VALUES (?, ?, ?, 'Old', 'state.edu', true)",
                id, "old@state.edu", cost4,
            )
        }
        assertEquals(HttpStatusCode.OK, login("old@state.edu").status)
        val upgraded = userColumn("old@state.edu", "password_hash") as String
        assertTrue(upgraded.startsWith("\$2a\$05\$"), upgraded)
        assertEquals(HttpStatusCode.OK, login("old@state.edu").status)
    }

    @Test
    fun deletedAccountsCannotLogIn() = authTest { env ->
        signUp("alice@state.edu")
        env.db.tx { c -> c.update("UPDATE users SET deleted_at = now() WHERE email = ?", "alice@state.edu") }
        assertError(login("alice@state.edu"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
    }

    // ---------- refresh ----------

    @Test
    fun refreshRotatesTheToken() = authTest {
        val first = signUp("alice@state.edu")
        val res = refresh(first.refreshToken)
        assertEquals(HttpStatusCode.OK, res.status)
        val second = res.body<AuthResponse>()
        assertNotEquals(first.refreshToken, second.refreshToken)
        assertEquals(first.user.id, second.user.id)
        assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.ME) { bearerAuth(second.accessToken) }.status)
        assertEquals(1, activeTokens("alice@state.edu"))
        // the new one keeps working
        assertEquals(HttpStatusCode.OK, refresh(second.refreshToken).status)
    }

    @Test
    fun tokensAreStoredOnlyAsDigests() = authTest { env ->
        val session = signUp("alice@state.edu")
        val stored = env.db.tx { c -> c.queryOne("SELECT token_hash FROM refresh_tokens") { it.getString(1) } }
        assertNotNull(stored)
        assertNotEquals(session.refreshToken, stored)
        assertTrue(session.refreshToken !in stored)
    }

    @Test
    fun reusingARotatedTokenRevokesEverySession() = authTest {
        val stolen = signUp("alice@state.edu")
        val otherDevice = login("alice@state.edu").body<AuthResponse>()
        val rotated = refresh(stolen.refreshToken).body<AuthResponse>()
        assertEquals(2, activeTokens("alice@state.edu"))

        assertError(refresh(stolen.refreshToken), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        assertEquals(0, activeTokens("alice@state.edu"))
        assertError(refresh(rotated.refreshToken), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        assertError(refresh(otherDevice.refreshToken), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        // signing in again works
        assertEquals(HttpStatusCode.OK, login("alice@state.edu").status)
    }

    @Test
    fun concurrentUseOfOneTokenRotatesOnlyOnce() = authTest {
        val session = signUp("alice@state.edu")
        val statuses = coroutineScope {
            (1..2).map { async { refresh(session.refreshToken).status } }.map { it.await() }
        }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized), statuses.sortedBy { it.value })
        assertEquals(0, activeTokens("alice@state.edu"))
    }

    @Test
    fun unknownMalformedAndExpiredTokensAreRejected() = authTest { env ->
        assertError(refresh("not-a-token"), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        assertError(refresh(""), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        assertError(refresh("x".repeat(5000)), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)

        val session = signUp("alice@state.edu")
        env.db.tx { c -> c.update("UPDATE refresh_tokens SET expires_at = ?", Instant.now().minusSeconds(1)) }
        assertError(refresh(session.refreshToken), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun anExpiredRotatedTokenIsJustInvalid() = authTest { env ->
        val old = signUp("alice@state.edu")
        val current = refresh(old.refreshToken).body<AuthResponse>()
        // the rotated-away token has since expired: presenting it is stale, not evidence of theft
        env.db.tx { c ->
            c.update("UPDATE refresh_tokens SET expires_at = ? WHERE revoked_at IS NOT NULL", Instant.now().minusSeconds(1))
        }
        assertError(refresh(old.refreshToken), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        assertEquals(HttpStatusCode.OK, refresh(current.refreshToken).status)
    }

    @Test
    fun refreshRespectsAccountState() = authTest { env ->
        val session = signUp("alice@state.edu")
        env.db.tx { c -> c.update("UPDATE users SET suspended = true, suspension_reason = 'spam' WHERE email = ?", "alice@state.edu") }
        assertError(refresh(session.refreshToken), HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED)
        env.db.tx { c -> c.update("UPDATE users SET suspended = false, deleted_at = now() WHERE email = ?", "alice@state.edu") }
        assertError(refresh(session.refreshToken), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun refreshTokenLifetimeComesFromConfig() = authTest {
        signUp("alice@state.edu")
        val expires = TestEnv.db.txBlocking { c -> c.queryOne("SELECT expires_at FROM refresh_tokens") { it.getTimestamp(1).toInstant() } }!!
        val days = java.time.Duration.between(Instant.now(), expires).toHours() / 24.0
        assertTrue(days in 29.9..30.0, "refresh token lives $days days")
    }

    // ---------- logout ----------

    @Test
    fun logoutRevokesOnlyThatSession() = authTest {
        val phone = signUp("alice@state.edu")
        val laptop = login("alice@state.edu").body<AuthResponse>()
        val res = logout(phone.refreshToken)
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(OkResponse(), res.body<OkResponse>())
        assertEquals(1, activeTokens("alice@state.edu"))
        assertEquals(HttpStatusCode.OK, refresh(laptop.refreshToken).status)
    }

    @Test
    fun logoutIsIdempotent() = authTest {
        val session = signUp("alice@state.edu")
        assertEquals(HttpStatusCode.OK, logout(session.refreshToken).status)
        assertEquals(HttpStatusCode.OK, logout(session.refreshToken).status)
        assertEquals(HttpStatusCode.OK, logout("unknown").status)
        assertEquals(HttpStatusCode.OK, logout("").status)
        assertError(refresh(session.refreshToken), HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
    }
}
