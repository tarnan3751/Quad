package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OkResponse
import com.campusmarket.core.UpdateListingRequest
import com.campusmarket.core.UserRole
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.queryOne
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingCrudTest {

    @Test
    fun createNormalisesInputAndReturnsDetail() = apiTest { env ->
        val sam = env.user("sam")
        val p1 = env.photo(sam)
        val p2 = env.photo(sam)
        val req = createRequest(
            photoIds = listOf(p2, p1),
            title = "  Desk \n  lamp\t",
            description = "Warm light\r\nworks\u0007 great  ",
            category = ListingCategory.DORM,
            condition = ListingCondition.LIKE_NEW,
            priceCents = 1500,
            pickupLocation = "  North   dorm ",
        )
        val d = client.post(ApiRoutes.LISTINGS) { auth(sam); json(req) }.expect<ListingDetail>(HttpStatusCode.Created)

        assertEquals("Desk lamp", d.title)
        assertEquals("Warm light\nworks great", d.description)
        assertEquals("North dorm", d.pickupLocation)
        assertEquals(ListingCategory.DORM, d.category)
        assertEquals(ListingCondition.LIKE_NEW, d.condition)
        assertEquals(1500, d.priceCents)
        assertEquals("usd", d.currency)
        assertEquals(ListingStatus.ACTIVE, d.status)
        assertEquals(listOf(p2.toString(), p1.toString()), d.photos.map { it.id })
        assertEquals("${env.config.publicBaseUrl}${ApiRoutes.UPLOADS}/$p2.jpg", d.photos[0].url)
        assertEquals(sam.id.toString(), d.seller.id)
        assertEquals("sam", d.seller.displayName)
        assertTrue(d.isMine)
        assertFalse(d.favorited)
        assertEquals(0, d.favoriteCount)

        val campus = env.db.tx { c -> c.queryOne("SELECT campus_domain FROM listings WHERE id = ?", UUID.fromString(d.id)) { it.getString(1) } }
        assertEquals("state.edu", campus)

        val fetched = client.get(ApiRoutes.listing(d.id)) { auth(sam) }.expect<ListingDetail>()
        assertEquals(d, fetched)
    }

    @Test
    fun createReportsEveryInvalidField() = apiTest { env ->
        val sam = env.user()
        val foreignPhoto = env.photo(env.user())
        val req = createRequest(
            photoIds = listOf(foreignPhoto),
            title = " ab ",
            description = "x".repeat(Limits.DESCRIPTION_MAX + 1),
            priceCents = Limits.PRICE_MIN_CENTS - 1,
            pickupLocation = "y".repeat(Limits.PICKUP_MAX + 1),
        )
        val err = client.post(ApiRoutes.LISTINGS) { auth(sam); json(req) }.expectError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        assertEquals(setOf("title", "description", "priceCents", "pickupLocation", "photoIds"), err.fields.keys)
        assertEquals(0, env.db.tx { c -> c.queryOne("SELECT count(*) FROM listings") { it.getInt(1) } })
    }

    @Test
    fun createValidatesPhotosAndBounds() = apiTest { env ->
        val sam = env.user()
        val mine = (1..Limits.PHOTOS_MAX + 1).map { env.photo(sam) }
        suspend fun post(req: com.campusmarket.core.CreateListingRequest) = client.post(ApiRoutes.LISTINGS) { auth(sam); json(req) }

        post(createRequest(emptyList())).expectError(HttpStatusCode.BadRequest, field = "photoIds")
        post(createRequest(mine)).expectError(HttpStatusCode.BadRequest, field = "photoIds")
        post(createRequest(listOf(mine[0], mine[0]))).expectError(HttpStatusCode.BadRequest, field = "photoIds")
        post(createRequest(listOf(UUID.randomUUID()))).expectError(HttpStatusCode.BadRequest, field = "photoIds")
        post(createRequest(emptyList()).copy(photoIds = listOf("not-a-uuid"))).expectError(HttpStatusCode.BadRequest, field = "photoIds")
        post(createRequest(listOf(mine[0]), priceCents = Limits.PRICE_MAX_CENTS + 1)).expectError(HttpStatusCode.BadRequest, field = "priceCents")
        post(createRequest(listOf(mine[0]), title = "x".repeat(Limits.TITLE_MAX + 1))).expectError(HttpStatusCode.BadRequest, field = "title")

        // boundaries are inclusive
        post(createRequest(mine.take(Limits.PHOTOS_MAX), priceCents = Limits.PRICE_MAX_CENTS, title = "x".repeat(Limits.TITLE_MAX)))
            .expect<ListingDetail>(HttpStatusCode.Created)
        post(createRequest(listOf(mine[0]), priceCents = Limits.PRICE_MIN_CENTS, title = "abc", description = ""))
            .expect<ListingDetail>(HttpStatusCode.Created)
    }

    @Test
    fun malformedBodiesAndMissingAuth() = apiTest { env ->
        val sam = env.user()
        val bad = client.post(ApiRoutes.LISTINGS) {
            auth(sam)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Boat","description":"","category":"BOATS","condition":"GOOD","priceCents":100,"photoIds":[]}""")
        }
        bad.expectError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        val missing = client.post(ApiRoutes.LISTINGS) {
            auth(sam)
            contentType(ContentType.Application.Json)
            setBody("""{"description":""}""")
        }
        missing.expectError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)
        val notJson = client.post(ApiRoutes.LISTINGS) {
            auth(sam)
            contentType(ContentType.Text.Plain)
            setBody("title=Boat")
        }
        notJson.expectError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION)

        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.LISTINGS).status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(ApiRoutes.LISTINGS) { json(createRequest(emptyList())) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.listing(UUID.randomUUID().toString())).status)
    }

    @Test
    fun detailVisibilityFollowsCampusAndStatus() = apiTest { env ->
        val seller = env.user("seller")
        val neighbour = env.user("neighbour")
        val outsider = env.user("outsider", domain = "other.edu")
        val mod = env.user("mod", domain = "other.edu", role = UserRole.MODERATOR)

        val active = env.listing(seller)
        neighbour.let { assertFalse(client.get(ApiRoutes.listing(active.toString())) { auth(it) }.expect<ListingDetail>().isMine) }
        client.get(ApiRoutes.listing(active.toString())) { auth(outsider) }.expectError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)

        for (status in listOf(ListingStatus.RESERVED, ListingStatus.SOLD)) {
            val id = env.listing(seller, status = status)
            assertEquals(status, client.get(ApiRoutes.listing(id.toString())) { auth(neighbour) }.expect<ListingDetail>().status)
        }
        for (status in listOf(ListingStatus.REMOVED, ListingStatus.DELETED)) {
            val id = env.listing(seller, status = status).toString()
            client.get(ApiRoutes.listing(id)) { auth(neighbour) }.expectError(HttpStatusCode.NotFound)
            client.get(ApiRoutes.listing(id)) { auth(outsider) }.expectError(HttpStatusCode.NotFound)
            assertEquals(status, client.get(ApiRoutes.listing(id)) { auth(seller) }.expect<ListingDetail>().status)
            assertEquals(status, client.get(ApiRoutes.listing(id)) { auth(mod) }.expect<ListingDetail>().status)
        }

        client.get(ApiRoutes.listing("not-a-uuid")) { auth(neighbour) }.expectError(HttpStatusCode.NotFound)
        client.get(ApiRoutes.listing(UUID.randomUUID().toString())) { auth(neighbour) }.expectError(HttpStatusCode.NotFound)
    }

    @Test
    fun sellerCanEditActiveListing() = apiTest { env ->
        val sam = env.user()
        val p1 = env.photo(sam)
        val p2 = env.photo(sam)
        val created = client.post(ApiRoutes.LISTINGS) { auth(sam); json(createRequest(listOf(p1))) }.expect<ListingDetail>(HttpStatusCode.Created)

        val updated = client.patch(ApiRoutes.listing(created.id)) {
            auth(sam)
            json(UpdateListingRequest(title = " Brass desk lamp ", priceCents = 1200, photoIds = listOf(p2.toString(), p1.toString())))
        }.expect<ListingDetail>()
        assertEquals("Brass desk lamp", updated.title)
        assertEquals(1200, updated.priceCents)
        assertEquals(created.description, updated.description)
        assertEquals(created.category, updated.category)
        assertEquals(created.pickupLocation, updated.pickupLocation)
        assertEquals(listOf(p2.toString(), p1.toString()), updated.photos.map { it.id })
        assertTrue(updated.updatedAt >= created.updatedAt)

        // empty patch is a no-op
        assertEquals(updated, client.patch(ApiRoutes.listing(created.id)) { auth(sam); json(UpdateListingRequest()) }.expect<ListingDetail>())

        // clearing the pickup location is allowed; invalid values are not
        assertEquals("", client.patch(ApiRoutes.listing(created.id)) { auth(sam); json(UpdateListingRequest(pickupLocation = "")) }.expect<ListingDetail>().pickupLocation)
        client.patch(ApiRoutes.listing(created.id)) { auth(sam); json(UpdateListingRequest(title = "x")) }
            .expectError(HttpStatusCode.BadRequest, field = "title")
        client.patch(ApiRoutes.listing(created.id)) { auth(sam); json(UpdateListingRequest(photoIds = emptyList())) }
            .expectError(HttpStatusCode.BadRequest, field = "photoIds")
        client.patch(ApiRoutes.listing(created.id)) { auth(sam); json(UpdateListingRequest(photoIds = listOf(env.photo(env.user()).toString()))) }
            .expectError(HttpStatusCode.BadRequest, field = "photoIds")
        assertEquals("Brass desk lamp", client.get(ApiRoutes.listing(created.id)) { auth(sam) }.expect<ListingDetail>().title)
    }

    @Test
    fun onlyTheSellerCanEditAndOnlyWhileActive() = apiTest { env ->
        val seller = env.user()
        val neighbour = env.user()
        val outsider = env.user(domain = "other.edu")
        val id = env.listing(seller).toString()
        val change = UpdateListingRequest(priceCents = 999)

        client.patch(ApiRoutes.listing(id)) { auth(neighbour); json(change) }.expectError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN)
        client.patch(ApiRoutes.listing(id)) { auth(outsider); json(change) }.expectError(HttpStatusCode.NotFound)
        client.patch(ApiRoutes.listing("nope")) { auth(seller); json(change) }.expectError(HttpStatusCode.NotFound)

        for (status in listOf(ListingStatus.RESERVED, ListingStatus.SOLD, ListingStatus.REMOVED, ListingStatus.DELETED)) {
            val other = env.listing(seller, status = status).toString()
            client.patch(ApiRoutes.listing(other)) { auth(seller); json(change) }.expectError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT)
        }
        // a non-seller learns nothing about hidden listings
        val removed = env.listing(seller, status = ListingStatus.REMOVED).toString()
        client.patch(ApiRoutes.listing(removed)) { auth(neighbour); json(change) }.expectError(HttpStatusCode.NotFound)
        assertEquals(2500, client.get(ApiRoutes.listing(id)) { auth(seller) }.expect<ListingDetail>().priceCents)
    }

    @Test
    fun deleteRules() = apiTest { env ->
        val seller = env.user()
        val neighbour = env.user()
        val outsider = env.user(domain = "other.edu")
        val id = env.listing(seller).toString()

        client.delete(ApiRoutes.listing(id)) { auth(neighbour) }.expectError(HttpStatusCode.Forbidden)
        client.delete(ApiRoutes.listing(id)) { auth(outsider) }.expectError(HttpStatusCode.NotFound)
        for (status in listOf(ListingStatus.RESERVED, ListingStatus.SOLD)) {
            val busy = env.listing(seller, status = status).toString()
            client.delete(ApiRoutes.listing(busy)) { auth(seller) }.expectError(HttpStatusCode.Conflict)
            assertEquals(status, client.get(ApiRoutes.listing(busy)) { auth(seller) }.expect<ListingDetail>().status)
        }

        assertEquals(OkResponse(), client.delete(ApiRoutes.listing(id)) { auth(seller) }.expect<OkResponse>())
        assertEquals(ListingStatus.DELETED, client.get(ApiRoutes.listing(id)) { auth(seller) }.expect<ListingDetail>().status)
        client.get(ApiRoutes.listing(id)) { auth(neighbour) }.expectError(HttpStatusCode.NotFound)
        client.delete(ApiRoutes.listing(id)) { auth(seller) }.expect<OkResponse>() // idempotent

        val removed = env.listing(seller, status = ListingStatus.REMOVED).toString()
        client.delete(ApiRoutes.listing(removed)) { auth(seller) }.expect<OkResponse>()
        assertEquals(ListingStatus.DELETED, client.get(ApiRoutes.listing(removed)) { auth(seller) }.expect<ListingDetail>().status)
    }

    @Test
    fun favoritesAreIdempotentAndCounted() = apiTest { env ->
        val seller = env.user()
        val fan = env.user()
        val outsider = env.user(domain = "other.edu")
        val id = env.listing(seller).toString()

        repeat(2) { client.post(ApiRoutes.listingFavorite(id)) { auth(fan) }.expect<OkResponse>() }
        client.get(ApiRoutes.listing(id)) { auth(fan) }.expect<ListingDetail>().let {
            assertTrue(it.favorited)
            assertEquals(1, it.favoriteCount)
        }
        client.get(ApiRoutes.listing(id)) { auth(seller) }.expect<ListingDetail>().let {
            assertFalse(it.favorited)
            assertEquals(1, it.favoriteCount)
        }
        repeat(2) { client.delete(ApiRoutes.listingFavorite(id)) { auth(fan) }.expect<OkResponse>() }
        assertEquals(0, client.get(ApiRoutes.listing(id)) { auth(fan) }.expect<ListingDetail>().favoriteCount)

        client.post(ApiRoutes.listingFavorite(id)) { auth(outsider) }.expectError(HttpStatusCode.NotFound)
        client.delete(ApiRoutes.listingFavorite(id)) { auth(outsider) }.expectError(HttpStatusCode.NotFound)
        client.post(ApiRoutes.listingFavorite("nope")) { auth(fan) }.expectError(HttpStatusCode.NotFound)
        client.post(ApiRoutes.listingFavorite(UUID.randomUUID().toString())) { auth(fan) }.expectError(HttpStatusCode.NotFound)

        val removed = env.listing(seller, status = ListingStatus.REMOVED).toString()
        client.post(ApiRoutes.listingFavorite(removed)) { auth(fan) }.expectError(HttpStatusCode.NotFound)
        val sold = env.listing(seller, status = ListingStatus.SOLD).toString()
        client.post(ApiRoutes.listingFavorite(sold)) { auth(fan) }.expect<OkResponse>()

        // a favorite on a listing that later gets taken down can still be cleared
        client.post(ApiRoutes.listingFavorite(id)) { auth(fan) }.expect<OkResponse>()
        env.setStatus(UUID.fromString(id), ListingStatus.REMOVED)
        client.delete(ApiRoutes.listingFavorite(id)) { auth(fan) }.expect<OkResponse>()
        assertNull(env.db.tx { c -> c.queryOne("SELECT 1 FROM favorites WHERE listing_id = ?", UUID.fromString(id)) { 1 } })
    }
}
