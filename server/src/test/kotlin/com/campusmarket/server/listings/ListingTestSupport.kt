package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Page
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.auth
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal fun createRequest(
    photoIds: List<UUID>,
    title: String = "Desk lamp",
    description: String = "Warm light, works great",
    category: ListingCategory = ListingCategory.FURNITURE,
    condition: ListingCondition = ListingCondition.GOOD,
    priceCents: Int = 1500,
    pickupLocation: String = "North dorm",
) = CreateListingRequest(title, description, category, condition, priceCents, pickupLocation, photoIds.map { it.toString() })

internal suspend inline fun <reified T> HttpResponse.expect(status: HttpStatusCode = HttpStatusCode.OK): T {
    if (this.status != status) fail("expected $status but got ${this.status}: ${bodyAsText()}")
    return body()
}

internal suspend fun HttpResponse.expectError(status: HttpStatusCode, code: String? = null, field: String? = null): ErrorResponse {
    val err = expect<ErrorResponse>(status)
    if (code != null) assertEquals(code, err.code, err.toString())
    if (field != null) {
        assertEquals(ErrorCodes.VALIDATION, err.code, err.toString())
        assertTrue(field in err.fields, "expected field '$field' in $err")
    }
    return err
}

/** GET [url] page by page (following `nextCursor`) and returns every item in order. */
internal suspend fun ApiTestScope.allPages(
    user: TestEnv.TestUser,
    url: String,
    limit: Int,
    params: Map<String, String> = emptyMap(),
): List<ListingSummary> {
    val out = mutableListOf<ListingSummary>()
    var cursor: String? = null
    var pages = 0
    do {
        val page = client.get(url) {
            auth(user)
            params.forEach { (k, v) -> parameter(k, v) }
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }.expect<Page<ListingSummary>>()
        assertTrue(page.items.size <= limit)
        out += page.items
        cursor = page.nextCursor
        if (++pages > 200) fail("pagination does not terminate")
    } while (cursor != null)
    return out
}

internal suspend fun ApiTestScope.search(user: TestEnv.TestUser, vararg params: Pair<String, String>): List<ListingSummary> =
    client.get(ApiRoutes.LISTINGS) {
        auth(user)
        params.forEach { (k, v) -> parameter(k, v) }
    }.expect<Page<ListingSummary>>().items

internal fun TestEnv.setCreatedAt(listingId: UUID, at: Instant) = db.txBlocking { c ->
    c.update("UPDATE listings SET created_at = ?::timestamptz WHERE id = ?", at.toString(), listingId)
}

internal fun TestEnv.setStatus(listingId: UUID, status: ListingStatus) = db.txBlocking { c ->
    c.update("UPDATE listings SET status = ? WHERE id = ?", status, listingId)
}

internal fun TestEnv.block(blocker: TestEnv.TestUser, blocked: TestEnv.TestUser) = db.txBlocking { c ->
    c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", blocker.id, blocked.id)
}

internal fun List<ListingSummary>.ids(): List<UUID> = map { UUID.fromString(it.id) }
