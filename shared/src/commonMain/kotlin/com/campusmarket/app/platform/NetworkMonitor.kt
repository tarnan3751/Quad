package com.campusmarket.app.platform

import kotlinx.coroutines.flow.Flow

/**
 * Says when the device gets a usable network connection (again), so the chat socket reconnects at once instead of
 * waiting out its backoff (up to 30 s). Provided by the Android and iOS platform modules; absent on desktop.
 */
fun interface NetworkMonitor {
    /** Emits whenever a network becomes available, including once on collection if one already is. */
    fun available(): Flow<Unit>
}
