package com.campusmarket.server.users

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.UpdateProfileRequest
import com.campusmarket.core.UserPrivate
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import io.ktor.client.call.body
import io.ktor.client.request.patch
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileAuditTest {

    @Test
    fun invisibleCharactersInDisplayNamesAreRejected() = apiTest { env ->
        val alice = env.user("alice")
        // look-alikes of another account's name: zero-width space, word joiner, soft hyphen, Arabic letter mark
        for (name in listOf("Mod​erator", "Mod⁠erator", "Mod­erator", "Moderator؜")) {
            val res = client.patch(ApiRoutes.ME) { auth(alice); json(UpdateProfileRequest(displayName = name)) }
            assertEquals(HttpStatusCode.BadRequest, res.status, name)
            val err = res.body<ErrorResponse>()
            assertEquals(ErrorCodes.VALIDATION, err.code)
            assertEquals(setOf("displayName"), err.fields.keys)
        }
        // emoji joined with ZWJ are fine
        val family = "Ali 👨‍👩‍👧"
        val ok = client.patch(ApiRoutes.ME) { auth(alice); json(UpdateProfileRequest(displayName = family)) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(family, ok.body<UserPrivate>().displayName)
    }
}
