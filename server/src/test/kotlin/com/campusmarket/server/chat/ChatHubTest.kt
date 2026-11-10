package com.campusmarket.server.chat

import com.campusmarket.core.ChatEvent
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHubTest {
    private fun ev(n: Int) = ChatEvent.ConversationRead("c$n", "u", n.toLong())

    @Test
    fun publishReachesAllSubscriptionsOfTargetUsersOnly() = runBlocking {
        val hub = InProcessChatHub()
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        val a1 = hub.subscribe(a); val a2 = hub.subscribe(a); val b1 = hub.subscribe(b); val c1 = hub.subscribe(c)
        hub.publish(listOf(a, b, a), ev(1)) // duplicate target id delivers once
        assertEquals(ev(1), a1.events.receive())
        assertEquals(ev(1), a2.events.receive())
        assertEquals(ev(1), b1.events.receive())
        assertTrue(c1.events.tryReceive().isFailure)
        assertTrue(a1.events.tryReceive().isFailure)
    }

    @Test
    fun closeUnregistersAndIsIdempotent() {
        val hub = InProcessChatHub()
        val u = UUID.randomUUID()
        val s = hub.subscribe(u)
        assertEquals(1, hub.connectionCount(u))
        s.close(); s.close()
        assertEquals(0, hub.connectionCount(u))
        hub.publish(listOf(u), ev(1)) // no subscribers: no-op
        assertFalse(s.dropped)
    }

    @Test
    fun slowSubscriberIsDroppedWithoutBlockingPublishersOrOthers() = runBlocking {
        val hub = InProcessChatHub(queueCapacity = 3)
        val u = UUID.randomUUID()
        val slow = hub.subscribe(u)
        val fast = hub.subscribe(u)
        val received = mutableListOf<ChatEvent>()
        repeat(10) {
            hub.publish(listOf(u), ev(it)) // never suspends
            fast.events.tryReceive().getOrNull()?.let(received::add) // fast consumer keeps up
        }
        assertTrue(slow.dropped)
        assertFalse(fast.dropped)
        assertEquals(1, hub.connectionCount(u))
        assertEquals<List<ChatEvent>>((0 until 10).map { ev(it) }, received)
        // the dropped one can still drain what was buffered, then ends
        assertEquals(3, generateSequence<ChatEvent> { slow.events.tryReceive().getOrNull() }.count())
        assertTrue(slow.events.receiveCatching().isClosed)
    }

    @Test
    fun oldestConnectionIsEvictedBeyondThePerUserCap() {
        val hub = InProcessChatHub(maxConnectionsPerUser = 2)
        val u = UUID.randomUUID()
        val first = hub.subscribe(u); val second = hub.subscribe(u); val third = hub.subscribe(u)
        assertTrue(first.dropped)
        assertFalse(second.dropped)
        assertFalse(third.dropped)
        assertEquals(2, hub.connectionCount(u))
    }

    @Test
    fun cursorRoundTripAndRejection() {
        val c = KeysetCursor(java.time.Instant.parse("2026-01-02T03:04:05.123456Z"), UUID.randomUUID())
        assertEquals(c, KeysetCursor.decode(c.encode()))
        assertNull(KeysetCursor.decode(null))
        assertNull(KeysetCursor.decode(""))
        for (bad in listOf("%%%", "abc", java.util.Base64.getUrlEncoder().encodeToString("1:notauuid".toByteArray()))) {
            val failed = runCatching { KeysetCursor.decode(bad) }.exceptionOrNull()
            assertTrue(failed is com.campusmarket.server.ApiException, bad)
        }
    }
}
