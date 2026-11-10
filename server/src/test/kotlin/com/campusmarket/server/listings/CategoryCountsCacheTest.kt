package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CategoryInfo
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.UserRole
import com.campusmarket.server.AppDeps
import com.campusmarket.server.TestEnv
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** `GET /categories` with its campus counts kept in memory (the other tests run with the cache off). */
class CategoryCountsCacheTest {
    private val cached = AppDeps(TestEnv.config.copy(categoryCountsTtlSeconds = 30), TestEnv.db)

    private fun TestEnv.TestUser.principal() = UserPrincipal(id, UserRole.USER, email.substringAfter('@'))

    private fun List<CategoryInfo>.count(category: ListingCategory) = single { it.category == category }.activeCount

    @Test
    fun campusCountsLagUntilTheyExpireWhileBlocksApplyAtOnce() = apiTest { env ->
        var now = 1_000_000_000_000L
        val browse = ListingBrowse(cached) { now }
        val viewer = env.user()
        val seller = env.user()
        val other = env.user()
        val elsewhere = env.user(domain = "other.edu")
        repeat(2) { env.listing(seller, category = ListingCategory.TEXTBOOKS) }
        env.listing(other, category = ListingCategory.BIKES)
        env.listing(elsewhere, category = ListingCategory.BIKES)

        val first = browse.categories(viewer.principal())
        assertEquals(ListingCategory.entries, first.map { it.category })
        assertEquals(2, first.count(ListingCategory.TEXTBOOKS))
        assertEquals(1, first.count(ListingCategory.BIKES))
        assertEquals(0, first.count(ListingCategory.FURNITURE))

        // new and sold listings wait for the next count...
        env.listing(seller, category = ListingCategory.TEXTBOOKS)
        env.listing(seller, category = ListingCategory.FURNITURE)
        now += 29_000_000_000L
        assertEquals(first, browse.categories(viewer.principal()))
        // ...each campus has its own counts...
        assertEquals(1, browse.categories(elsewhere.principal()).count(ListingCategory.BIKES))
        assertEquals(0, browse.categories(elsewhere.principal()).count(ListingCategory.TEXTBOOKS))
        // ...and a block is taken off them right away, for the blocker only
        env.block(viewer, seller)
        assertEquals(0, browse.categories(viewer.principal()).count(ListingCategory.TEXTBOOKS))
        assertEquals(1, browse.categories(viewer.principal()).count(ListingCategory.BIKES))
        assertEquals(2, browse.categories(other.principal()).count(ListingCategory.TEXTBOOKS))
        // a blocked seller's listing newer than the count can't push a number below zero
        env.block(other, seller)
        env.block(viewer, other)
        env.listing(other, category = ListingCategory.BIKES)
        assertEquals(0, browse.categories(viewer.principal()).count(ListingCategory.BIKES))

        now += 2_000_000_000L
        val fresh = browse.categories(other.principal())
        assertEquals(0, fresh.count(ListingCategory.TEXTBOOKS)) // blocked the seller
        assertEquals(2, fresh.count(ListingCategory.BIKES))
        val unblocked = browse.categories(env.user().principal())
        assertEquals(3, unblocked.count(ListingCategory.TEXTBOOKS))
        assertEquals(1, unblocked.count(ListingCategory.FURNITURE))
    }

    @Test
    fun theETagFollowsWhatIsServed() = apiTest(cached) { env ->
        val viewer = env.user()
        val seller = env.user()
        env.listing(seller, category = ListingCategory.TEXTBOOKS)
        val first = client.get(ApiRoutes.CATEGORIES) { auth(viewer) }
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        assertEquals(1, first.expect<List<CategoryInfo>>().count(ListingCategory.TEXTBOOKS))

        // within the cache's lifetime the same counts are served, so the client's copy still validates
        env.listing(seller, category = ListingCategory.TEXTBOOKS)
        val same = client.get(ApiRoutes.CATEGORIES) { auth(viewer); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, same.status)
        assertEquals(etag, same.headers[HttpHeaders.ETag])

        // what the caller sees changed: a new ETag and a body
        env.block(viewer, seller)
        val changed = client.get(ApiRoutes.CATEGORIES) { auth(viewer); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.OK, changed.status)
        assertNotEquals(etag, changed.headers[HttpHeaders.ETag])
        assertEquals(0, changed.expect<List<CategoryInfo>>().count(ListingCategory.TEXTBOOKS))
    }
}
