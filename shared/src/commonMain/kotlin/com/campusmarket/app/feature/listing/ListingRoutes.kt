package com.campusmarket.app.feature.listing

import kotlinx.serialization.Serializable

// Owner: feature A (browse + listing).

/** Bottom-bar tab: create a listing (photos, title, price, category, condition, pickup). */
@Serializable
data object SellRoute

/** Photo pager, seller card, favorite, message seller, buy, report; edit/delete when `isMine`. */
@Serializable
data class ListingDetailRoute(val listingId: String)

/** Edit an ACTIVE listing of mine. */
@Serializable
data class EditListingRoute(val listingId: String)

@Serializable
data object FavoritesRoute

/** My listings, all statuses except DELETED (filter by status). */
@Serializable
data object MyListingsRoute
