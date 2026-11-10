package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.Limits
import com.campusmarket.core.Order
import com.campusmarket.core.Page
import com.campusmarket.core.Rating
import com.campusmarket.core.UserPublic
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.loadUserPublic
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatingTest {

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String) {
        assertEquals(status, this.status, bodyAsText())
        assertEquals(code, body<ErrorResponse>().code)
    }

    private suspend fun PaymentsScope.rate(user: TestEnv.TestUser, orderId: Any, stars: Int, comment: String = "") =
        client.post(ApiRoutes.orderRating(orderId.toString())) { auth(user); json(CreateRatingRequest(stars, comment)) }

    private fun PaymentsScope.publicUser(user: TestEnv.TestUser): UserPublic = env.db.txBlocking { c -> c.loadUserPublic(env.config, user.id)!! }

    @Test
    fun bothPartiesRateEachOtherOnceAndAggregatesMove() = paymentsTest {
        val buyer = buyer("bea")
        val seller = seller("sam")
        val orderId = completedOrder(buyer, seller, title = "Mini fridge")

        val res = rate(buyer, orderId, 5, "  Smooth handoff!  ")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val rating = res.body<Rating>()
        assertEquals(orderId.toString(), rating.orderId)
        assertEquals(buyer.id.toString(), rating.rater.id)
        assertEquals(seller.id.toString(), rating.rateeId)
        assertEquals(5, rating.stars)
        assertEquals("Smooth handoff!", rating.comment)
        assertEquals("Mini fridge", rating.listingTitle)
        assertEquals(1, publicUser(seller).ratingCount)
        assertEquals(5.0, publicUser(seller).ratingAverage)

        // iHaveRated is per viewer
        assertEquals(true, client.get(ApiRoutes.order(orderId.toString())) { auth(buyer) }.body<Order>().iHaveRated)
        assertEquals(false, client.get(ApiRoutes.order(orderId.toString())) { auth(seller) }.body<Order>().iHaveRated)

        rate(buyer, orderId, 1).assertError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        assertEquals(5.0, publicUser(seller).ratingAverage)

        assertEquals(HttpStatusCode.OK, rate(seller, orderId, 4).status)
        assertEquals(4.0, publicUser(buyer).ratingAverage)
        assertEquals(true, client.get(ApiRoutes.order(orderId.toString())) { auth(seller) }.body<Order>().iHaveRated)

        // a second order with the same seller averages in
        assertEquals(HttpStatusCode.OK, rate(buyer, completedOrder(buyer, seller), 2).status)
        assertEquals(2, publicUser(seller).ratingCount)
        assertEquals(3.5, publicUser(seller).ratingAverage)
    }

    @Test
    fun onlyCompletedOrdersOfParticipantsCanBeRated() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val pending = buyOk(buyer, env.listing(seller))
        rate(buyer, pending.id, 5).assertError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE)

        val done = completedOrder(buyer, seller)
        rate(buyer(), done, 5).assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        rate(buyer, UUID.randomUUID(), 5).assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
        rate(buyer, "bogus", 5).assertError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
    }

    @Test
    fun validatesStarsAndComment() = paymentsTest {
        val buyer = buyer()
        val done = completedOrder(buyer, seller())
        for (stars in listOf(0, 6, -1)) {
            val res = rate(buyer, done, stars)
            res.assertError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        }
        val tooLong = rate(buyer, done, 3, "x".repeat(Limits.RATING_COMMENT_MAX + 1))
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertEquals(setOf("comment"), tooLong.body<ErrorResponse>().fields.keys)
        assertEquals(HttpStatusCode.OK, rate(buyer, done, 3, "x".repeat(Limits.RATING_COMMENT_MAX)).status)
    }

    @Test
    fun concurrentDoubleSubmitCountsOnce() = paymentsTest {
        val buyer = buyer()
        val seller = seller()
        val done = completedOrder(buyer, seller)
        val results = coroutineScope { (1..4).map { async { rate(buyer, done, 4) } }.awaitAll() }
        assertEquals(1, results.count { it.status == HttpStatusCode.OK })
        assertEquals(1, publicUser(seller).ratingCount)
        assertEquals(4.0, publicUser(seller).ratingAverage)
    }

    @Test
    fun listsRatingsReceivedNewestFirstWithinCampus() = paymentsTest {
        val seller = seller("popular")
        val raters = (1..3).map { buyer("r$it") }
        val ids = raters.mapIndexed { i, r ->
            rate(r, completedOrder(r, seller, title = "Item $i"), i + 3).body<Rating>().id
        }
        // the seller's own rating of a buyer isn't "received" by the seller
        rate(seller, completedOrder(raters[0], seller), 1)

        val viewer = buyer("viewer")
        suspend fun page(q: String = "") = client.get(ApiRoutes.userRatings(seller.id.toString()) + q) { auth(viewer) }.body<Page<Rating>>()

        val all = page()
        assertEquals(ids.reversed(), all.items.map { it.id })
        assertNull(all.nextCursor)
        assertEquals("Item 2", all.items.first().listingTitle)
        assertEquals(raters[2].id.toString(), all.items.first().rater.id)

        val p1 = page("?limit=2")
        assertEquals(ids.reversed().take(2), p1.items.map { it.id })
        val p2 = page("?limit=2&cursor=${p1.nextCursor}")
        assertEquals(listOf(ids[0]), p2.items.map { it.id })
        assertNull(p2.nextCursor)

        val outsider = buyer(domain = "other.edu")
        assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.userRatings(seller.id.toString())) { auth(outsider) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.userRatings(UUID.randomUUID().toString())) { auth(viewer) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.userRatings("x")) { auth(viewer) }.status)
        assertEquals(Page<Rating>(emptyList(), null), client.get(ApiRoutes.userRatings(viewer.id.toString())) { auth(viewer) }.body<Page<Rating>>())
    }
}
