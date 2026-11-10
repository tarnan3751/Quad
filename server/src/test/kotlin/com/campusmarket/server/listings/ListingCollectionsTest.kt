package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OkResponse
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.update
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListingCollectionsTest {

    @Test
    fun favoritesAreNewestFavoritedFirstAndHideTakenDownListings() = apiTest { env ->
        val seller = env.user()
        val fan = env.user()
        val listings = (0..5).map { env.listing(seller, title = "Item $it") }
        listings.forEach { client.post(ApiRoutes.listingFavorite(it.toString())) { auth(fan) }.expect<OkResponse>() }
        // favorite order: 3, 1, {4, 0} (same instant), 5, 2
        val base = Instant.parse("2026-09-01T10:00:00Z")
        val favoritedAt = mapOf(
            3 to base.plusSeconds(40), 1 to base.plusSeconds(30), 4 to base.plusSeconds(20), 0 to base.plusSeconds(20),
            5 to base.plusSeconds(10), 2 to base,
        )
        env.db.tx { c ->
            favoritedAt.forEach { (i, at) ->
                c.update("UPDATE favorites SET created_at = ?::timestamptz WHERE user_id = ? AND listing_id = ?", at.toString(), fan.id, listings[i])
            }
        }
        env.setStatus(listings[5], ListingStatus.REMOVED)
        env.setStatus(listings[2], ListingStatus.DELETED)
        env.setStatus(listings[1], ListingStatus.SOLD)
        // favorites of other users don't leak in
        client.post(ApiRoutes.listingFavorite(env.listing(seller).toString())) { auth(env.user()) }.expect<OkResponse>()

        val tie = listOf(listings[4], listings[0]).sortedByDescending { it.toString() }
        val expected = listOf(listings[3], listings[1]) + tie
        for (limit in 1..4) {
            val got = allPages(fan, ApiRoutes.MY_FAVORITES, limit)
            assertEquals(expected, got.ids(), "limit=$limit")
            assertTrue(got.all { it.favorited })
        }
        assertEquals(ListingStatus.SOLD, allPages(fan, ApiRoutes.MY_FAVORITES, 10)[1].status)
        assertEquals(emptyList(), allPages(seller, ApiRoutes.MY_FAVORITES, 10))
    }

    @Test
    fun myListingsCoverEveryStatusButDeleted() = apiTest { env ->
        val me = env.user()
        val byStatus = ListingStatus.entries.associateWith { env.listing(me, title = "Mine ${it.name}", status = it) }
        env.listing(env.user(), title = "Not mine")
        val t = Instant.parse("2026-08-01T00:00:00Z")
        byStatus.values.forEachIndexed { i, id -> env.setCreatedAt(id, t.plusSeconds(i.toLong())) }

        val all = allPages(me, ApiRoutes.MY_LISTINGS, 2)
        assertEquals(byStatus.filterKeys { it != ListingStatus.DELETED }.values.reversed(), all.ids())
        assertEquals(listOf(byStatus.getValue(ListingStatus.SOLD)), allPages(me, ApiRoutes.MY_LISTINGS, 5, mapOf("status" to "SOLD")).ids())
        assertEquals(
            setOf(byStatus.getValue(ListingStatus.ACTIVE), byStatus.getValue(ListingStatus.REMOVED)),
            allPages(me, ApiRoutes.MY_LISTINGS, 5, mapOf("status" to "active,REMOVED")).ids().toSet(),
        )
        assertEquals(emptyList(), allPages(me, ApiRoutes.MY_LISTINGS, 5, mapOf("status" to "DELETED")))
        client.get("${ApiRoutes.MY_LISTINGS}?status=GONE") { auth(me) }.expectError(HttpStatusCode.BadRequest, field = "status")
    }

    @Test
    fun userListingsShowActiveListingsOnTheSameCampusOnly() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val outsider = env.user(domain = "other.edu")
        val a = env.listing(seller)
        val b = env.listing(seller)
        env.setCreatedAt(a, Instant.parse("2026-01-01T00:00:00Z"))
        env.listing(seller, status = ListingStatus.SOLD)
        env.listing(seller, status = ListingStatus.REMOVED)
        env.listing(viewer)

        assertEquals(listOf(b, a), allPages(viewer, ApiRoutes.userListings(seller.id.toString()), 1).ids())
        assertEquals(listOf(b, a), allPages(seller, ApiRoutes.userListings(seller.id.toString()), 5).ids())
        client.get(ApiRoutes.userListings(seller.id.toString())) { auth(outsider) }.expectError(HttpStatusCode.NotFound)
        client.get(ApiRoutes.userListings(UUID.randomUUID().toString())) { auth(viewer) }.expectError(HttpStatusCode.NotFound)
        client.get(ApiRoutes.userListings("nobody")) { auth(viewer) }.expectError(HttpStatusCode.NotFound)

        // a suspended seller's shop window is empty
        env.db.tx { c -> c.update("UPDATE users SET suspended = true WHERE id = ?", seller.id) }
        assertEquals(emptyList(), allPages(viewer, ApiRoutes.userListings(seller.id.toString()), 5))
    }
}
