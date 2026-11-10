package com.campusmarket.app.feature.listing

import com.campusmarket.app.data.NOW
import com.campusmarket.app.data.testUser
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Photo
import com.campusmarket.core.UserPublic
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData

/* Fixtures for the browse and listing view-model tests. */

val seller = UserPublic(id = "seller-1", displayName = "Maya Chen", campusDomain = "uni.edu", ratingAverage = 4.5, ratingCount = 2, joinedAt = NOW - 1000)

fun summary(id: String, title: String = "Listing $id", priceCents: Int = 1000, favorited: Boolean = false, status: ListingStatus = ListingStatus.ACTIVE) =
    ListingSummary(
        id = id,
        title = title,
        priceCents = priceCents,
        category = ListingCategory.TEXTBOOKS,
        condition = ListingCondition.GOOD,
        status = status,
        thumbnailUrl = "http://test.local/uploads/$id.jpg",
        sellerId = seller.id,
        sellerName = seller.displayName,
        favorited = favorited,
        createdAt = NOW - 60_000,
    )

fun detail(
    id: String = "l1",
    isMine: Boolean = false,
    status: ListingStatus = ListingStatus.ACTIVE,
    favorited: Boolean = false,
    favoriteCount: Int = 0,
    photos: List<Photo> = listOf(Photo("ph1", "http://test.local/uploads/ph1.jpg"), Photo("ph2", "http://test.local/uploads/ph2.jpg")),
) = ListingDetail(
    id = id,
    title = "Campbell Biology",
    description = "12th edition, a few highlights.",
    priceCents = 6500,
    category = ListingCategory.TEXTBOOKS,
    condition = ListingCondition.GOOD,
    status = status,
    pickupLocation = "Library steps",
    photos = photos,
    seller = if (isMine) seller.copy(id = testUser.id, displayName = testUser.displayName) else seller,
    favorited = favorited,
    favoriteCount = favoriteCount,
    isMine = isMine,
    createdAt = NOW - 3_600_000,
    updatedAt = NOW - 3_600_000,
)

/** The multipart filename of a `POST /photos` request. */
suspend fun HttpRequestData.uploadedFileName(): String =
    Regex("filename=\"([^\"]+)\"").find(body.toByteArray().decodeToString())?.groupValues?.get(1) ?: error("no filename in upload")
