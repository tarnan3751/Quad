package com.campusmarket.app.feature.chat

import kotlinx.serialization.Serializable

// Owner: feature B (chat + orders).

/** Bottom-bar tab: conversations, most recent first, with unread counts. */
@Serializable
data object InboxRoute

@Serializable
data class ConversationRoute(val conversationId: String)

/**
 * "Message seller" from a listing: calls `ApiClient.startConversation(listingId)` (idempotent) and shows the
 * conversation (typically by replacing itself with [ConversationRoute]).
 */
@Serializable
data class MessageSellerRoute(val listingId: String)
