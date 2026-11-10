package com.campusmarket.server.listings

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.UpdateListingRequest
import com.campusmarket.server.AppDeps
import com.campusmarket.server.Budget
import com.campusmarket.server.TestEnv
import com.campusmarket.server.UserRateLimits
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

class ListingAuditTest {

    @Test
    fun creatingListingsIsRateLimitedPerUser() =
        apiTest(AppDeps(TestEnv.config.copy(userRateLimits = UserRateLimits(listings = Budget(2, 1.hours))), TestEnv.db)) { env ->
            val sam = env.user("sam")
            val photo = env.photo(sam) // one photo can be reused by every listing
            repeat(2) { client.post(ApiRoutes.LISTINGS) { auth(sam); json(createRequest(listOf(photo))) }.expect<ListingDetail>(HttpStatusCode.Created) }
            client.post(ApiRoutes.LISTINGS) { auth(sam); json(createRequest(listOf(photo))) }
                .expectError(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED)
            val ann = env.user("ann")
            client.post(ApiRoutes.LISTINGS) { auth(ann); json(createRequest(listOf(env.photo(ann)))) }.expect<ListingDetail>(HttpStatusCode.Created)
        }

    @Test
    fun invisibleFormattingCharactersAreStrippedFromListingText() = apiTest { env ->
        val sam = env.user("sam")
        val req = createRequest(
            photoIds = listOf(env.photo(sam)),
            // a right-to-left override would make "gpj.exe" render as "exe.jpg"; zero-width spaces hide words from search
            title = "Desk​ lamp ‮gpj.exe",
            description = "Wor​ks ⁦great⁩\nreally",
            pickupLocation = "North﻿ dorm",
        )
        val d = client.post(ApiRoutes.LISTINGS) { auth(sam); json(req) }.expect<ListingDetail>(HttpStatusCode.Created)
        assertEquals("Desk lamp gpj.exe", d.title)
        assertEquals("Works great\nreally", d.description)
        assertEquals("North dorm", d.pickupLocation)

        // decomposed accents are stored composed (NFC), so they match searches typed either way
        val edited = client.patch(ApiRoutes.listing(d.id)) { auth(sam); json(UpdateListingRequest(title = "Café table")) }
            .expect<ListingDetail>()
        assertEquals("Café table", edited.title)
        assertEquals(listOf(d.id), search(sam, "q" to "Café").map { it.id })

        // a title made only of invisible characters is empty
        client.post(ApiRoutes.LISTINGS) { auth(sam); json(createRequest(listOf(env.photo(sam)), title = "​​​​")) }
            .expectError(HttpStatusCode.BadRequest, field = "title")
    }
}
