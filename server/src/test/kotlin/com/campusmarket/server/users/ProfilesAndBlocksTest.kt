package com.campusmarket.server.users

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.OkResponse
import com.campusmarket.core.UserPublic
import com.campusmarket.core.UserRole
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilesAndBlocksTest {

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String): ErrorResponse {
        assertEquals(status, this.status)
        return body<ErrorResponse>().also { assertEquals(code, it.code) }
    }

    // ---------- GET /users/{id} ----------

    @Test
    fun publicProfileOnSameCampus() = apiTest { env ->
        val alice = env.user("alice")
        val bob = env.user("bob")
        env.db.tx { c -> c.update("UPDATE users SET bio = 'Physics major', rating_sum = 9, rating_count = 2 WHERE id = ?", bob.id) }
        val res = client.get(ApiRoutes.user(bob.id.toString())) { auth(alice) }
        assertEquals(HttpStatusCode.OK, res.status)
        val profile = res.body<UserPublic>()
        assertEquals(bob.id.toString(), profile.id)
        assertEquals("bob", profile.displayName)
        assertEquals("Physics major", profile.bio)
        assertEquals("state.edu", profile.campusDomain)
        assertEquals(4.5, profile.ratingAverage)
        assertEquals(2, profile.ratingCount)
        assertFalse(profile.blockedByMe)
        assertFalse("bob@state.edu" in res.body<String>(), "public profile must not expose the email")
        // own profile is visible too
        assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.user(alice.id.toString())) { auth(alice) }.status)
    }

    @Test
    fun otherCampusesUnknownAndUnverifiedUsersAreNotFound() = apiTest { env ->
        val alice = env.user("alice")
        val carol = env.user("carol", domain = "other.edu")
        val pending = env.user("pending")
        env.db.tx { c -> c.update("UPDATE users SET email_verified = false WHERE id = ?", pending.id) }
        for (id in listOf(carol.id.toString(), pending.id.toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            client.get(ApiRoutes.user(id)) { auth(alice) }.assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        }
        client.get(ApiRoutes.user(alice.id.toString())).assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun moderatorsCanViewProfilesOnAnyCampus() = apiTest { env ->
        val mod = env.user("mod", role = UserRole.MODERATOR)
        val carol = env.user("carol", domain = "other.edu")
        assertEquals("carol", client.get(ApiRoutes.user(carol.id.toString())) { auth(mod) }.body<UserPublic>().displayName)
    }

    @Test
    fun deletedUsersRenderAsDeleted() = apiTest { env ->
        val alice = env.user("alice")
        val bob = env.user("bob")
        env.db.tx { c -> c.update("UPDATE users SET deleted_at = ?, bio = 'secret' WHERE id = ?", Instant.now(), bob.id) }
        val profile = client.get(ApiRoutes.user(bob.id.toString())) { auth(alice) }.body<UserPublic>()
        assertEquals("Deleted user", profile.displayName)
        assertEquals("", profile.bio)
    }

    // ---------- blocks ----------

    @Test
    fun blockAndUnblockAreIdempotent() = apiTest { env ->
        val alice = env.user("alice")
        val bob = env.user("bob")
        repeat(2) {
            val res = client.post(ApiRoutes.userBlock(bob.id.toString())) { auth(alice) }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(OkResponse(), res.body<OkResponse>())
        }
        assertTrue(client.get(ApiRoutes.user(bob.id.toString())) { auth(alice) }.body<UserPublic>().blockedByMe)
        val blocks = client.get(ApiRoutes.MY_BLOCKS) { auth(alice) }.body<List<UserPublic>>()
        assertEquals(listOf(bob.id.toString()), blocks.map { it.id })
        assertTrue(blocks.single().blockedByMe)
        // one-directional: bob hasn't blocked alice
        assertFalse(client.get(ApiRoutes.user(alice.id.toString())) { auth(bob) }.body<UserPublic>().blockedByMe)
        assertEquals(emptyList(), client.get(ApiRoutes.MY_BLOCKS) { auth(bob) }.body<List<UserPublic>>())

        repeat(2) { assertEquals(HttpStatusCode.OK, client.delete(ApiRoutes.userBlock(bob.id.toString())) { auth(alice) }.status) }
        assertEquals(emptyList(), client.get(ApiRoutes.MY_BLOCKS) { auth(alice) }.body<List<UserPublic>>())
        assertFalse(client.get(ApiRoutes.user(bob.id.toString())) { auth(alice) }.body<UserPublic>().blockedByMe)
    }

    @Test
    fun blockedUsersAreListedNewestFirst() = apiTest { env ->
        val alice = env.user("alice")
        val others = (1..3).map { env.user("u$it") }
        others.forEachIndexed { i, u ->
            env.db.tx { c ->
                c.update("INSERT INTO blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)", alice.id, u.id, Instant.now().minusSeconds(100L - i))
            }
        }
        val listed = client.get(ApiRoutes.MY_BLOCKS) { auth(alice) }.body<List<UserPublic>>()
        assertEquals(others.reversed().map { it.id.toString() }, listed.map { it.id })
    }

    @Test
    fun blockRules() = apiTest { env ->
        val alice = env.user("alice")
        val carol = env.user("carol", domain = "other.edu")
        client.post(ApiRoutes.userBlock(alice.id.toString())) { auth(alice) }.assertError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        client.delete(ApiRoutes.userBlock(alice.id.toString())) { auth(alice) }.assertError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        client.post(ApiRoutes.userBlock(carol.id.toString())) { auth(alice) }.assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.post(ApiRoutes.userBlock(UUID.randomUUID().toString())) { auth(alice) }.assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.post(ApiRoutes.userBlock("nope")) { auth(alice) }.assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.delete(ApiRoutes.userBlock(UUID.randomUUID().toString())) { auth(alice) }.assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        client.post(ApiRoutes.userBlock(carol.id.toString())).assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        client.get(ApiRoutes.MY_BLOCKS).assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)

        // moderators may view other campuses but blocking stays campus-scoped
        val mod = env.user("mod", role = UserRole.MODERATOR)
        client.post(ApiRoutes.userBlock(carol.id.toString())) { auth(mod) }.assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
    }

    @Test
    fun aBlockCanBeRemovedEvenIfTheUserIsNoLongerVisible() = apiTest { env ->
        val alice = env.user("alice")
        val bob = env.user("bob")
        assertEquals(HttpStatusCode.OK, client.post(ApiRoutes.userBlock(bob.id.toString())) { auth(alice) }.status)
        env.db.tx { c -> c.update("UPDATE users SET campus_domain = 'moved.edu' WHERE id = ?", bob.id) }
        assertEquals(HttpStatusCode.OK, client.delete(ApiRoutes.userBlock(bob.id.toString())) { auth(alice) }.status)
        assertEquals(emptyList(), client.get(ApiRoutes.MY_BLOCKS) { auth(alice) }.body<List<UserPublic>>())
    }
}
