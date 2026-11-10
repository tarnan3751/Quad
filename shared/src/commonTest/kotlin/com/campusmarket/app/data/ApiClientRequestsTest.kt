package com.campusmarket.app.data

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingSort
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.Page
import com.campusmarket.core.Photo
import com.campusmarket.core.RegisterRequest
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Error mapping and request shapes. */
class ApiClientRequestsTest {

    @Test
    fun validationErrorCarriesFieldMessages() = runTest {
        val h = Harness(initial = null) {
            respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, "Invalid input", mapOf("email" to "Not a campus email"))
        }

        val error = assertFailsWith<ApiError> { h.api.register(RegisterRequest("a@b.com", "password1", "Ada")) }

        assertEquals(ErrorCodes.VALIDATION, error.code)
        assertEquals("Invalid input", error.message)
        assertEquals("Not a campus email", error.field("email"))
        assertEquals(400, error.status)
        assertTrue(error.isValidation)
    }

    @Test
    fun domainErrorCodesPassThrough() = runTest {
        val h = Harness { respondError(HttpStatusCode.Conflict, ErrorCodes.PAYOUTS_NOT_ENABLED, "Seller can't accept payments yet") }
        val error = assertFailsWith<ApiError> { h.api.createOrder("l1") }
        assertEquals(ErrorCodes.PAYOUTS_NOT_ENABLED, error.code)
        assertEquals(409, error.status)
        assertEquals("Seller can't accept payments yet", error.message)
    }

    @Test
    fun nonJsonErrorFallsBackToStatusMapping() = runTest {
        val h = Harness { respond("<html>Not Found</html>", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/html")) }
        val error = assertFailsWith<ApiError> { h.api.listing("missing") }
        assertEquals(ErrorCodes.NOT_FOUND, error.code)
        assertEquals(404, error.status)
        assertTrue(error.isNotFound)
    }

    @Test
    fun transportFailureBecomesNetworkError() = runTest {
        val h = Harness { throw kotlinx.io.IOException("connection refused") }
        val error = assertFailsWith<ApiError> { h.api.categories() }
        assertEquals(ApiError.NETWORK, error.code)
        assertTrue(error.isNetwork)
        assertEquals(null, error.status)
    }

    @Test
    fun malformedSuccessBodyBecomesUnexpected() = runTest {
        val h = Harness { respond("{\"nope\":1}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val error = assertFailsWith<ApiError> { h.api.me() }
        assertEquals(ApiError.UNEXPECTED, error.code)
    }

    @Test
    fun listingQueryIsEncodedPerContract() = runTest {
        val h = Harness { respondJson(Page.serializer(ListingSummary.serializer()), Page(emptyList(), null)) }

        h.api.listings(
            ListingQuery(
                q = "  calc textbook ",
                category = ListingCategory.TEXTBOOKS,
                conditions = linkedSetOf(ListingCondition.NEW, ListingCondition.LIKE_NEW),
                minPriceCents = 500,
                maxPriceCents = 5000,
                sort = ListingSort.PRICE_ASC,
            ),
            cursor = "abc",
            limit = 10,
        )

        val url = h.requests.single().url
        assertEquals(BASE + ApiRoutes.LISTINGS, url.toString().substringBefore('?'))
        val p = url.parameters
        assertEquals("calc textbook", p["q"])
        assertEquals("TEXTBOOKS", p["category"])
        assertEquals("NEW,LIKE_NEW", p["condition"])
        assertEquals("500", p["minPrice"])
        assertEquals("5000", p["maxPrice"])
        assertEquals("PRICE_ASC", p["sort"])
        assertEquals("abc", p["cursor"])
        assertEquals("10", p["limit"])
    }

    @Test
    fun emptyQueryOmitsParameters() = runTest {
        val h = Harness { respondJson(Page.serializer(ListingSummary.serializer()), Page(emptyList(), null)) }
        h.api.listings()
        assertTrue(h.requests.single().url.parameters.isEmpty())
    }

    @Test
    fun ordersRoleIsLowercase() = runTest {
        val h = Harness { respondJson(Page.serializer(Order.serializer()), Page(emptyList(), null)) }
        h.api.orders(OrderRole.SELLER)
        assertEquals("seller", h.requests.single().url.parameters["role"])
    }

    @Test
    fun okEndpointsUseRightMethodAndPath() = runTest {
        val h = Harness { respond("{\"ok\":true}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        h.api.unfavorite("l1")
        h.api.blockUser("u2")
        val (unfav, block) = h.requests
        assertEquals(HttpMethod.Delete, unfav.method)
        assertEquals(ApiRoutes.listingFavorite("l1"), unfav.url.encodedPath)
        assertEquals(HttpMethod.Post, block.method)
        assertEquals(ApiRoutes.userBlock("u2"), block.url.encodedPath)
    }

    @Test
    fun photoUploadIsMultipartWithFilePart() = runTest {
        val h = Harness { respondJson(Photo.serializer(), Photo("p1", "http://localhost:8080/uploads/x.jpg", 10, 10)) }
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

        val photo = h.api.uploadPhoto(bytes, "pic.jpg", "image/jpeg")

        assertEquals("p1", photo.id)
        val req = h.requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals(ApiRoutes.PHOTOS, req.url.encodedPath)
        assertTrue(req.body.contentType.toString().startsWith("multipart/form-data"))
        val body = req.body.toByteArray().decodeToString()
        assertTrue("name=file" in body || "name=\"file\"" in body, body)
        assertTrue("filename=\"pic.jpg\"" in body, body)
        assertTrue("image/jpeg" in body, body)
    }

    @Test
    fun disputeAndCredentialDeletionRequests() = runTest {
        val h = Harness { req ->
            when (req.url.encodedPath) {
                ApiRoutes.DELETE_ACCOUNT -> respondJson(com.campusmarket.core.OkResponse.serializer(), com.campusmarket.core.OkResponse())
                else -> respondError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE, "nope")
            }
        }
        assertFailsWith<ApiError> {
            h.api.openDispute("o1", com.campusmarket.core.OpenDisputeRequest(com.campusmarket.core.DisputeReason.OTHER, "broken"))
        }
        assertFailsWith<ApiError> { h.api.withdrawDispute("o1") }
        assertFailsWith<ApiError> { h.api.modDisputes(com.campusmarket.core.DisputeStatus.CLOSED, cursor = "c", limit = 5) }
        assertFailsWith<ApiError> {
            h.api.resolveDispute("o1", com.campusmarket.core.ResolveDisputeRequest(com.campusmarket.core.DisputeResolution.RELEASE_TO_SELLER, "ok"))
        }
        h.api.deleteAccountWithCredentials("ada@uni.edu", "pw")

        val (open, withdraw, queue, resolve, delete) = h.requests
        assertEquals(HttpMethod.Post to ApiRoutes.orderDispute("o1"), open.method to open.url.encodedPath)
        assertEquals("""{"reason":"OTHER","details":"broken"}""", open.body.toByteArray().decodeToString())
        assertEquals(HttpMethod.Post to ApiRoutes.orderDisputeWithdraw("o1"), withdraw.method to withdraw.url.encodedPath)
        assertEquals(HttpMethod.Get to ApiRoutes.MOD_DISPUTES, queue.method to queue.url.encodedPath)
        assertEquals(listOf("CLOSED", "c", "5"), listOf("status", "cursor", "limit").map { queue.url.parameters[it] })
        assertEquals(HttpMethod.Post to ApiRoutes.modDisputeResolve("o1"), resolve.method to resolve.url.encodedPath)
        assertEquals(HttpMethod.Post to ApiRoutes.DELETE_ACCOUNT, delete.method to delete.url.encodedPath)
        assertEquals(null, delete.headers[HttpHeaders.Authorization], "credential deletion never sends the session")
        assertEquals(null, h.api.sessionEndReason.value, "a 409 on an order doesn't end the session")
    }

    @Test
    fun listResponsesDecode() = runTest {
        val h = Harness {
            respondJson(ListSerializer(com.campusmarket.core.UserPublic.serializer()), listOf(com.campusmarket.core.UserPublic("u2", "Bo", campusDomain = "uni.edu", joinedAt = 1)))
        }
        assertEquals("Bo", h.api.myBlocks().single().displayName)
    }
}
