package com.campusmarket.app.navigation

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.NOW
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.feature.awaitMatch
import com.campusmarket.app.feature.chat.listingSummary
import com.campusmarket.app.feature.chat.publicUser
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Conversation
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Page
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Inbox tab badge: sums unread counts, throttles background refreshes, survives failures, resets on sign-out. */
class InboxBadgeTest {
    private fun conversation(id: String, unread: Int) =
        Conversation(id, listingSummary(id = "l-$id"), publicUser("u-$id", "User $id"), "hi", NOW, unread, NOW - 1_000)

    @Test
    fun countsUnreadAcrossConversationsAndThrottlesRefreshes() = runTest {
        var unread = listOf(2, 0, 3)
        val h = Harness { req ->
            if (req.url.encodedPath == ApiRoutes.CONVERSATIONS) {
                respondJson(Page.serializer(Conversation.serializer()), Page(unread.mapIndexed { i, n -> conversation("c$i", n) }))
            } else {
                respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val badge = InboxBadge(h.api, scope)
            badge.refresh(force = true)
            badge.count.awaitMatch { it == 5 }
            assertEquals("50", h.requestsTo(ApiRoutes.CONVERSATIONS).single().url.parameters["limit"], "one page, the largest")

            unread = listOf(0, 0, 1)
            badge.refresh() // too soon after the last count: skipped
            withContext(Dispatchers.Default) { delay(200) }
            assertEquals(1, h.requestsTo(ApiRoutes.CONVERSATIONS).size)
            assertEquals(5, badge.count.value)

            badge.refresh(force = true)
            badge.count.awaitMatch { it == 1 }

            badge.set(7)
            assertEquals(7, badge.count.value)
            badge.clear()
            assertEquals(0, badge.count.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun keepsTheLastCountWhenOffline() = runTest {
        var offline = false
        val h = Harness { req ->
            when {
                offline -> throw kotlinx.io.IOException("offline")
                req.url.encodedPath == ApiRoutes.CONVERSATIONS ->
                    respondJson(Page.serializer(Conversation.serializer()), Page(listOf(conversation("c1", 4))))
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val badge = InboxBadge(h.api, scope)
            badge.refresh(force = true)
            badge.count.awaitMatch { it == 4 }
            offline = true
            badge.refresh(force = true)
            withContext(Dispatchers.Default) { delay(300) }
            assertEquals(4, badge.count.value)
        } finally {
            scope.cancel()
        }
    }
}
