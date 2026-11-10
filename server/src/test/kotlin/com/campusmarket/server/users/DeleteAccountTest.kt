package com.campusmarket.server.users

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OkResponse
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.RefreshRequest
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.UserPublic
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import com.campusmarket.server.auth.authTest
import com.campusmarket.server.auth.login
import com.campusmarket.server.auth.register
import com.campusmarket.server.auth.signUp
import com.campusmarket.server.json
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class DeleteAccountTest {

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String): ErrorResponse {
        assertEquals(status, this.status)
        return body<ErrorResponse>().also { assertEquals(code, it.code) }
    }

    private fun listingStatus(id: UUID): String = TestEnv.db.txBlocking { c ->
        c.queryOne("SELECT status FROM listings WHERE id = ?", id) { it.getString(1) }!!
    }

    private fun order(listing: UUID, buyer: TestEnv.TestUser, seller: TestEnv.TestUser, status: OrderStatus) {
        TestEnv.db.txBlocking { c ->
            c.update(
                "INSERT INTO orders (id, listing_id, buyer_id, seller_id, amount_cents, fee_cents, status) VALUES (?, ?, ?, ?, 2500, 125, ?)",
                UUID.randomUUID(), listing, buyer.id, seller.id, status,
            )
        }
    }

    @Test
    fun deletionAnonymisesEndsSessionsAndTakesDownListings() = authTest { env ->
        val session = signUp("alice@state.edu", displayName = "Alice Doe")
        val alice = TestEnv.TestUser(UUID.fromString(session.user.id), session.user.email, session.accessToken)
        val bob = env.user("bob")
        val active = env.listing(alice, status = ListingStatus.ACTIVE)
        val reserved = env.listing(alice, status = ListingStatus.RESERVED)
        val sold = env.listing(alice, status = ListingStatus.SOLD)
        val removed = env.listing(alice, status = ListingStatus.REMOVED)
        val bobs = env.listing(bob)
        env.db.tx { c ->
            c.update("INSERT INTO favorites (user_id, listing_id) VALUES (?, ?)", alice.id, bobs)
            c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", alice.id, bob.id)
            c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", bob.id, alice.id)
            c.update("UPDATE users SET bio = 'about me', avatar_photo_id = ? WHERE id = ?", env.photo(alice), alice.id)
        }

        val res = client.delete(ApiRoutes.ME) { auth(alice) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(OkResponse(), res.body<OkResponse>())

        // the still-unexpired access token and the refresh token are both dead
        client.get(ApiRoutes.ME) { auth(alice) }.assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        client.delete(ApiRoutes.ME) { auth(alice) }.assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        client.post(ApiRoutes.REFRESH) { json(RefreshRequest(session.refreshToken)) }.assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        login("alice@state.edu").assertError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS)

        val row = env.db.tx { c ->
            c.queryOne("SELECT email, display_name, bio, avatar_photo_id, deleted_at FROM users WHERE id = ?", alice.id) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getObject(4), it.getTimestamp(5))
            }!!
        }
        assertEquals(listOf<Any?>("deleted+${alice.id}@deleted.invalid", "Deleted user", "", null), row.take(4))
        assertNotNull(row[4])
        assertEquals(0, env.db.tx { c -> c.queryOne("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", alice.id) { it.getInt(1) } })
        assertEquals(0, env.db.tx { c -> c.queryOne("SELECT count(*) FROM favorites WHERE user_id = ?", alice.id) { it.getInt(1) } })

        assertEquals("DELETED", listingStatus(active))
        assertEquals("DELETED", listingStatus(reserved))
        assertEquals("SOLD", listingStatus(sold))
        assertEquals("REMOVED", listingStatus(removed))
        assertEquals("ACTIVE", listingStatus(bobs))

        // others see a tombstone, and the email address is free again
        val profile = client.get(ApiRoutes.user(alice.id.toString())) { auth(bob) }.body<UserPublic>()
        assertEquals("Deleted user", profile.displayName)
        val again = register("alice@state.edu")
        assertEquals(HttpStatusCode.OK, again.status)
        assertNotNull(again.body<RegisterResponse>().devCode)
        val newId = env.db.tx { c -> c.queryOne("SELECT id FROM users WHERE email = 'alice@state.edu'") { it.getObject(1) } }
        assertNotEquals<Any?>(alice.id, newId)
    }

    @Test
    fun deletionIsRefusedWhileOrdersAreInFlight() = authTest { env ->
        for (status in listOf(OrderStatus.PAID, OrderStatus.PENDING_PAYMENT, OrderStatus.DISPUTED)) {
            for (aliceIsBuyer in listOf(true, false)) {
                env.reset()
                val a = env.user("alice")
                val b = env.user("bob")
                val listing = env.listing(if (aliceIsBuyer) b else a, status = ListingStatus.RESERVED)
                if (aliceIsBuyer) order(listing, a, b, status) else order(listing, b, a, status)
                client.delete(ApiRoutes.ME) { auth(a) }.assertError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
                assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.ME) { auth(a) }.status, "$status buyer=$aliceIsBuyer")
                assertEquals("RESERVED", listingStatus(listing))
            }
        }
        // finished orders don't block deletion
        env.reset()
        val a = env.user("alice")
        val b = env.user("bob")
        order(env.listing(b, status = ListingStatus.SOLD), a, b, OrderStatus.COMPLETED)
        order(env.listing(a, status = ListingStatus.ACTIVE), b, a, OrderStatus.CANCELLED)
        order(env.listing(a, status = ListingStatus.ACTIVE), b, a, OrderStatus.REFUNDED)
        assertEquals(HttpStatusCode.OK, client.delete(ApiRoutes.ME) { auth(a) }.status)
    }
}
