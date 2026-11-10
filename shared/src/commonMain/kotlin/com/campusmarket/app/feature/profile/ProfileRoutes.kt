package com.campusmarket.app.feature.profile

import kotlinx.serialization.Serializable

// Owner: feature C (profile + moderation).

/**
 * Bottom-bar tab: me (avatar, name, rating) + entry points to my listings, favorites, orders, payouts, settings,
 * and the moderation queue for moderators.
 */
@Serializable
data object ProfileRoute

@Serializable
data object EditProfileRoute

/** Someone's public profile: ratings, ACTIVE listings, message / block / report. [showRatings] opens the Ratings tab. */
@Serializable
data class UserProfileRoute(val userId: String, val showRatings: Boolean = false)

/** Sign out, blocked users, delete account, (debug) server URL. */
@Serializable
data object SettingsRoute

@Serializable
data object BlockedUsersRoute
