package com.campusmarket.server.auth

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.DeleteAccountRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OkResponse
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.RefreshRequest
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.json
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** `POST /auth/delete-account`: deletion by email + password, for accounts that can't hold a session (suspended). */
class DeleteAccountWithCredentialsTest {

    private suspend fun ApiTestScope.deleteAccount(email: String, password: String = PASSWORD): HttpResponse =
        client.post(ApiRoutes.DELETE_ACCOUNT) { json(DeleteAccountRequest(email, password)) }

    private fun userId(email: String): UUID = userColumn(email, "id") as UUID

    private fun deletedAt(id: UUID): Any? = TestEnv.db.txBlocking { c -> c.queryOne("SELECT deleted_at FROM users WHERE id = ?", id) { it.getObject(1) } }

    private fun suspend(id: UUID) = TestEnv.db.txBlocking { c ->
        c.update("UPDATE users SET suspended = true, suspension_reason = 'Violation of community guidelines' WHERE id = ?", id)
        c.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ?", id)
    }

    private fun order(listing: UUID, buyer: UUID, seller: UUID, status: OrderStatus) = TestEnv.db.txBlocking { c ->
        c.update(
            "INSERT INTO orders (id, listing_id, buyer_id, seller_id, amount_cents, fee_cents, status) VALUES (?, ?, ?, ?, 2500, 125, ?)",
            UUID.randomUUID(), listing, buyer, seller, status,
        )
    }

    @Test
    fun deletesAnAccountWithTheSameEffectsAsDeleteMe() = authTest { env ->
        val session = signUp("alice@state.edu")
        val id = UUID.fromString(session.user.id)
        val alice = TestEnv.TestUser(id, session.user.email, session.accessToken)
        val listing = env.listing(alice)

        // no bearer token needed (nor used)
        val res = deleteAccount("Alice@State.edu ")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(OkResponse(), res.body<OkResponse>())

        assertNotNull(deletedAt(id))
        assertEquals("deleted+$id@deleted.invalid", userColumn("deleted+$id@deleted.invalid", "email"))
        assertEquals(ListingStatus.DELETED.name, env.db.txBlocking { c -> c.queryOne("SELECT status FROM listings WHERE id = ?", listing) { it.getString(1) } })
        assertError(client.post(ApiRoutes.REFRESH) { json(RefreshRequest(session.refreshToken)) }, HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        assertError(login("alice@state.edu"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        assertError(client.get(ApiRoutes.ME) { bearerAuth(session.accessToken) }, HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        // and again: the account is gone, indistinguishable from never having existed
        assertError(deleteAccount("alice@state.edu"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        // the address is free again
        assertEquals(HttpStatusCode.OK, register("alice@state.edu").status)
    }

    @Test
    fun aSuspendedUserCanDeleteTheirAccount() = authTest {
        signUp("sam@state.edu")
        val id = userId("sam@state.edu")
        suspend(id)
        assertError(login("sam@state.edu"), HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED)

        val res = deleteAccount("sam@state.edu")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertNotNull(deletedAt(id))
        assertError(login("sam@state.edu"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
    }

    @Test
    fun wrongPasswordAndUnknownEmailLookTheSame() = authTest {
        signUp("bob@state.edu")
        val wrong = assertError(deleteAccount("bob@state.edu", "not the password"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        val unknown = assertError(deleteAccount("nobody@state.edu"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        val notCampus = assertError(deleteAccount("nobody@gmail.com"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        val tooLong = assertError(deleteAccount("bob@state.edu", "x".repeat(500)), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        assertEquals(setOf(wrong), setOf(unknown, notCampus, tooLong))
        assertNull(deletedAt(userId("bob@state.edu")))

        // a suspended account with the wrong password says nothing about the suspension
        suspend(userId("bob@state.edu"))
        assertEquals(wrong, assertError(deleteAccount("bob@state.edu", "nope nope"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS))

        // validation
        val v = assertError(deleteAccount("", ""), HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertNotNull(v.fields["email"])
        assertNotNull(v.fields["password"])
    }

    @Test
    fun unverifiedAccountsAreOnlyRevealedToTheirPasswordHolder() = authTest {
        assertEquals(HttpStatusCode.OK, register("una@state.edu").status)
        assertError(deleteAccount("una@state.edu", "wrong password"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        assertError(deleteAccount("una@state.edu"), HttpStatusCode.Forbidden, ErrorCodes.EMAIL_NOT_VERIFIED)
        assertNull(deletedAt(userId("una@state.edu")))
    }

    @Test
    fun refusedWhileOrdersAreInFlightEvenWhenSuspended() = authTest { env ->
        for (status in listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.DISPUTED)) {
            for (asBuyer in listOf(true, false)) {
                env.reset()
                signUp("carol@state.edu")
                val carol = userId("carol@state.edu")
                val other = env.user("dave")
                val carolUser = TestEnv.TestUser(carol, "carol@state.edu", "")
                val listing = env.listing(if (asBuyer) other else carolUser, status = ListingStatus.RESERVED)
                if (asBuyer) order(listing, carol, other.id, status) else order(listing, other.id, carol, status)
                suspend(carol)

                // wrong password: still just 401 (the orders aren't revealed)
                assertError(deleteAccount("carol@state.edu", "wrong wrong"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
                val err = assertError(deleteAccount("carol@state.edu"), HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
                assertEquals("You have orders in progress. Complete or cancel them before deleting your account.", err.message)
                assertNull(deletedAt(carol), "$status buyer=$asBuyer")
            }
        }
        // finished orders don't block it
        env.reset()
        signUp("carol@state.edu")
        val carol = userId("carol@state.edu")
        val other = env.user("dave")
        order(env.listing(other, status = ListingStatus.SOLD), carol, other.id, OrderStatus.COMPLETED)
        order(env.listing(other, status = ListingStatus.ACTIVE), carol, other.id, OrderStatus.REFUNDED)
        assertEquals(HttpStatusCode.OK, deleteAccount("carol@state.edu").status)
    }

    @Test
    fun sharesTheLoginRateLimit() = authTest(testAuthSettings(rateLimits = RelaxedRateLimits.copy(window = 10.minutes, login = 3))) {
        signUp("eve@state.edu")
        assertError(login("eve@state.edu", "guess one"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        assertError(deleteAccount("eve@state.edu", "guess two"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        assertError(deleteAccount("eve@state.edu", "guess three"), HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)
        // the budget is spent across both endpoints
        assertError(deleteAccount("eve@state.edu"), HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)
        assertError(login("eve@state.edu"), HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)
        assertNull(deletedAt(userId("eve@state.edu")))
    }
}
