package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Page
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ListingPaginationTest {

    /** A fixture row with everything the orderings look at. */
    private data class Row(val id: UUID, val price: Int, val createdAt: Instant)

    // Postgres compares uuids bytewise, which matches comparing their canonical lowercase strings.
    private val newest = compareByDescending<Row> { it.createdAt }.thenByDescending { it.id.toString() }

    @Test
    fun everySortPagesWithoutGapsOrDuplicatesUnderTies() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val t = Instant.parse("2026-10-01T12:00:00.123456Z")
        // Heavy ties on price and on created_at, including rows a single microsecond apart.
        val specs = listOf(
            500 to t, 500 to t, 500 to t, 500 to t.plusNanos(1000), 1000 to t, 1000 to t.minusSeconds(60),
            1000 to t.minusSeconds(60), 2000 to t.plusSeconds(5), 2000 to t, 2000 to t.plusNanos(1000), 750 to t.plusNanos(1000),
        )
        val rows = specs.map { (price, at) ->
            val id = env.listing(seller, title = "Lamp", priceCents = price, withPhoto = false)
            env.setCreatedAt(id, at)
            Row(id, price, at)
        }

        val expected = mapOf(
            "NEWEST" to rows.sortedWith(newest),
            "PRICE_ASC" to rows.sortedWith(compareBy<Row> { it.price }.then(newest)),
            "PRICE_DESC" to rows.sortedWith(compareByDescending<Row> { it.price }.then(newest)),
            // identical titles => identical ranks, so relevance degrades to newest-first
            "RELEVANCE" to rows.sortedWith(newest),
        )
        for ((sort, want) in expected) {
            for (limit in listOf(1, 2, 3, 4, 20)) {
                val params = buildMap {
                    put("sort", sort)
                    if (sort == "RELEVANCE") put("q", "lamp")
                }
                val got = allPages(viewer, ApiRoutes.LISTINGS, limit, params)
                assertEquals(want.map { it.id }, got.ids(), "sort=$sort limit=$limit")
            }
        }
    }

    @Test
    fun relevancePagesAcrossDistinctRanks() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        val strong = (1..3).map { env.listing(seller, title = "Desk lamp", description = "desk desk", withPhoto = false) }
        val medium = (1..3).map { env.listing(seller, title = "Desk", description = "plain", withPhoto = false) }
        val weak = (1..3).map { env.listing(seller, title = "Chair", description = "goes with a desk", withPhoto = false) }
        val all = allPages(viewer, ApiRoutes.LISTINGS, 2, mapOf("q" to "desk")).ids()
        assertEquals(9, all.size)
        assertEquals(all.toSet().size, all.size)
        assertEquals(weak.toSet(), all.takeLast(3).toSet())
        assertEquals((strong + medium).toSet(), all.take(6).toSet())
        assertEquals(all, allPages(viewer, ApiRoutes.LISTINGS, 9, mapOf("q" to "desk")).ids())
    }

    @Test
    fun cursorsAreOpaqueAndBoundToTheirOrdering() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        repeat(3) { env.listing(seller, withPhoto = false) }

        val first = client.get(ApiRoutes.LISTINGS) { auth(viewer); parameter("limit", 1) }.expect<Page<ListingSummary>>()
        val cursor = assertNotNull(first.nextCursor)
        assertEquals(1, first.items.size)

        client.get(ApiRoutes.LISTINGS) { auth(viewer); parameter("cursor", cursor); parameter("sort", "PRICE_ASC") }
            .expectError(HttpStatusCode.BadRequest, field = "cursor")
        client.get(ApiRoutes.MY_FAVORITES) { auth(viewer); parameter("cursor", cursor) }
            .expectError(HttpStatusCode.BadRequest, field = "cursor")
        val forged = Base64.getUrlEncoder().withoutPadding().encodeToString("1|NEWEST|yesterday|x|0|0".toByteArray())
        client.get(ApiRoutes.LISTINGS) { auth(viewer); parameter("cursor", forged) }.expectError(HttpStatusCode.BadRequest, field = "cursor")
        client.get(ApiRoutes.LISTINGS) { auth(viewer); parameter("cursor", "x".repeat(2000)) }.expectError(HttpStatusCode.BadRequest, field = "cursor")

        val last = client.get(ApiRoutes.LISTINGS) { auth(viewer); parameter("limit", 3) }.expect<Page<ListingSummary>>()
        assertEquals(3, last.items.size)
        assertNull(last.nextCursor)
    }

    @Test
    fun limitDefaultsAndClamps() = apiTest { env ->
        val seller = env.user()
        val viewer = env.user()
        repeat(Limits.PAGE_MAX + 1) { env.listing(seller, withPhoto = false) }
        suspend fun page(limit: String?) = client.get(ApiRoutes.LISTINGS) {
            auth(viewer)
            limit?.let { parameter("limit", it) }
        }.expect<Page<ListingSummary>>()

        assertEquals(Limits.PAGE_DEFAULT, page(null).items.size)
        assertEquals(Limits.PAGE_MAX, page("1000").items.size)
        assertEquals(1, page("0").items.size)
        assertEquals(1, page("-5").items.size)
        assertEquals(Limits.PAGE_MAX + 1, allPages(viewer, ApiRoutes.LISTINGS, Limits.PAGE_MAX).size)
    }
}
