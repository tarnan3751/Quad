package com.campusmarket.server

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.OkResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FoundationTest {
    @Test
    fun healthIsOk() = apiTest {
        val res = client.get(ApiRoutes.HEALTH)
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(OkResponse(), res.body())
    }

    @Test
    fun unknownPathRendersErrorResponse() = apiTest {
        val res = client.get("/api/v1/nope")
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals(ErrorCodes.NOT_FOUND, res.body<ErrorResponse>().code)
    }

    @Test
    fun fixturesAndSharedReadersWork() = apiTest { env ->
        val seller = env.user("sam")
        val listingId = env.listing(seller, title = "Desk lamp")
        val (summary, user) = env.db.tx { c ->
            c.loadListingSummary(env.config, listingId, seller.id) to c.loadUserPublic(env.config, seller.id)
        }
        assertNotNull(summary)
        assertEquals("Desk lamp", summary.title)
        assertEquals("sam", summary.sellerName)
        assertNotNull(summary.thumbnailUrl)
        assertEquals("state.edu", user?.campusDomain)
    }

    @Test
    fun suspendedUsersAreRejected() = apiTest { env ->
        val u = env.user("bad")
        env.db.tx { c -> c.update("UPDATE users SET suspended = true, suspension_reason = 'spam' WHERE id = ?", u.id) }
        assertEquals(null, authenticateToken(env.deps, u.token))
        assertNotNull(authenticateToken(env.deps, env.user("good").token))
    }
}
