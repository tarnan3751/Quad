package com.campusmarket.server.users

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.UpdateProfileRequest
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserRole
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeTest {

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String): ErrorResponse {
        assertEquals(status, this.status)
        return body<ErrorResponse>().also { assertEquals(code, it.code) }
    }

    @Test
    fun getMe() = apiTest { env ->
        val alice = env.user("alice", role = UserRole.MODERATOR, payoutsEnabled = true)
        val res = client.get(ApiRoutes.ME) { auth(alice) }
        assertEquals(HttpStatusCode.OK, res.status)
        val me = res.body<UserPrivate>()
        assertEquals(alice.id.toString(), me.id)
        assertEquals("alice@state.edu", me.email)
        assertEquals("alice", me.displayName)
        assertEquals("state.edu", me.campusDomain)
        assertEquals(UserRole.MODERATOR, me.role)
        assertEquals(true, me.payoutsEnabled)
        assertNull(me.ratingAverage)
    }

    @Test
    fun meRequiresAValidToken() = apiTest {
        client.get(ApiRoutes.ME).assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        client.get(ApiRoutes.ME) { bearerAuth("garbage") }.assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
        client.patch(ApiRoutes.ME) { json(UpdateProfileRequest(bio = "x")) }.assertError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED)
    }

    @Test
    fun patchUpdatesAndNormalisesProfile() = apiTest { env ->
        val alice = env.user("alice")
        val res = client.patch(ApiRoutes.ME) {
            auth(alice)
            json(UpdateProfileRequest(displayName = "  Alice   Liddell ", bio = "  Selling textbooks.\r\nDM me!  "))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val me = res.body<UserPrivate>()
        assertEquals("Alice Liddell", me.displayName)
        assertEquals("Selling textbooks.\nDM me!", me.bio)
        assertEquals(me, client.get(ApiRoutes.ME) { auth(alice) }.body<UserPrivate>())

        // omitted fields are untouched; an empty bio clears it
        val bioOnly = client.patch(ApiRoutes.ME) { auth(alice); json(UpdateProfileRequest(bio = "")) }.body<UserPrivate>()
        assertEquals("Alice Liddell", bioOnly.displayName)
        assertEquals("", bioOnly.bio)

        val nothing = client.patch(ApiRoutes.ME) { auth(alice); json(UpdateProfileRequest()) }
        assertEquals(bioOnly, nothing.body<UserPrivate>())
    }

    @Test
    fun patchReportsEveryInvalidField() = apiTest { env ->
        val alice = env.user("alice")
        val err = client.patch(ApiRoutes.ME) {
            auth(alice)
            json(UpdateProfileRequest(displayName = " A ", bio = "x".repeat(301), avatarPhotoId = "not-a-uuid"))
        }.assertError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertEquals(setOf("displayName", "bio", "avatarPhotoId"), err.fields.keys)

        val long = client.patch(ApiRoutes.ME) { auth(alice); json(UpdateProfileRequest(displayName = "x".repeat(41))) }
        assertEquals(setOf("displayName"), long.assertError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION).fields.keys)
        // nothing was applied
        assertEquals("alice", client.get(ApiRoutes.ME) { auth(alice) }.body<UserPrivate>().displayName)
    }

    @Test
    fun avatarMustBeTheCallersPhoto() = apiTest { env ->
        val alice = env.user("alice")
        val bob = env.user("bob")
        val mine = env.photo(alice)
        val theirs = env.photo(bob)

        val set = client.patch(ApiRoutes.ME) { auth(alice); json(UpdateProfileRequest(avatarPhotoId = mine.toString())) }
        assertEquals(HttpStatusCode.OK, set.status)
        assertEquals("http://localhost/uploads/$mine.jpg", set.body<UserPrivate>().avatarUrl)

        for (bad in listOf(theirs.toString(), UUID.randomUUID().toString())) {
            val err = client.patch(ApiRoutes.ME) { auth(alice); json(UpdateProfileRequest(avatarPhotoId = bad)) }
                .assertError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
            assertEquals(setOf("avatarPhotoId"), err.fields.keys)
        }
        assertEquals("http://localhost/uploads/$mine.jpg", client.get(ApiRoutes.ME) { auth(alice) }.body<UserPrivate>().avatarUrl)

        val cleared = client.patch(ApiRoutes.ME) { auth(alice); json(UpdateProfileRequest(avatarPhotoId = "")) }
        assertNull(cleared.body<UserPrivate>().avatarUrl)
    }
}
