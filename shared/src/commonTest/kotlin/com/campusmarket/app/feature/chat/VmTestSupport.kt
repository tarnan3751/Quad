package com.campusmarket.app.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.NOW
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.UserPublic
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** A controllable stand-in for the chat socket. */
class FakeLiveChat(initial: ChatSocket.Status = ChatSocket.Status.Connected) : LiveChat {
    private val flow = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
    override val events: Flow<ChatEvent> = flow
    override val status = MutableStateFlow(initial)
    var reconnectRequests = 0
    override fun reconnectNow() {
        reconnectRequests++
    }

    fun emit(event: ChatEvent) {
        check(flow.tryEmit(event))
    }
}

fun ViewModel.close() = viewModelScope.cancel()

// ---------------------------------------------------------------------------------------------------- fixtures

fun publicUser(id: String, name: String, blockedByMe: Boolean = false) =
    UserPublic(id = id, displayName = name, campusDomain = "uni.edu", joinedAt = NOW - 10 * HOUR, blockedByMe = blockedByMe)

fun listingSummary(id: String = "l1", sellerId: String = "u2", sellerName: String = "Maya Chen", status: ListingStatus = ListingStatus.ACTIVE) =
    ListingSummary(
        id = id,
        title = "Desk lamp",
        priceCents = 2500,
        category = ListingCategory.DORM,
        condition = ListingCondition.GOOD,
        status = status,
        sellerId = sellerId,
        sellerName = sellerName,
        createdAt = NOW - 5 * HOUR,
    )
