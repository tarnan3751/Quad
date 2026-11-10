package com.campusmarket.server.chat

import com.campusmarket.core.ChatEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One open websocket's inbox. Events are queued here and written to the socket by that socket's own writer coroutine. */
interface ChatSubscription {
    val userId: UUID
    val events: ReceiveChannel<ChatEvent>

    /** True once the subscription was closed because its queue overflowed (a slow client) or it was evicted. */
    val dropped: Boolean

    /** Queues [event] for this socket only. Never suspends; returns false (and drops the subscription) when the queue is full. */
    fun offer(event: ChatEvent): Boolean

    /** Idempotent. Unregisters from the hub and ends [events]. */
    fun close()
}

/** Fans chat events out to every open socket of the target users. Implementations must never block the caller. */
interface ChatHub {
    fun subscribe(userId: UUID): ChatSubscription

    /** Delivers [event] to every open subscription of each of [userIds]. Non-blocking; slow subscribers are dropped. */
    fun publish(userIds: Collection<UUID>, event: ChatEvent)
}

/** Single-node hub. Each subscription has a bounded queue; overflow drops that subscription rather than slowing senders. */
class InProcessChatHub(
    private val queueCapacity: Int = 128,
    private val maxConnectionsPerUser: Int = 10,
) : ChatHub {
    private val byUser = ConcurrentHashMap<UUID, List<Sub>>()

    private inner class Sub(override val userId: UUID) : ChatSubscription {
        private val channel = Channel<ChatEvent>(queueCapacity)

        @Volatile
        override var dropped: Boolean = false
            private set
        override val events: ReceiveChannel<ChatEvent> get() = channel

        override fun offer(event: ChatEvent): Boolean {
            if (channel.trySend(event).isSuccess) return true
            drop()
            return false
        }

        fun drop() {
            dropped = true
            close()
        }

        override fun close() {
            byUser.computeIfPresent(userId) { _, subs -> (subs - this).ifEmpty { null } }
            channel.close()
        }
    }

    override fun subscribe(userId: UUID): ChatSubscription {
        val sub = Sub(userId)
        var evicted: List<Sub> = emptyList()
        byUser.compute(userId) { _, subs ->
            val all = (subs ?: emptyList()) + sub
            evicted = all.take((all.size - maxConnectionsPerUser).coerceAtLeast(0))
            all.drop(evicted.size)
        }
        evicted.forEach { it.drop() }
        return sub
    }

    override fun publish(userIds: Collection<UUID>, event: ChatEvent) {
        for (id in userIds.distinct()) {
            byUser[id]?.forEach { it.offer(event) }
        }
    }

    /** Number of open subscriptions for [userId] (diagnostics / tests). */
    fun connectionCount(userId: UUID): Int = byUser[userId]?.size ?: 0
}
