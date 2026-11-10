package com.campusmarket.app

import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.DeepLink
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.data.ApiError
import com.campusmarket.core.Page
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepLinkTest {
    @Test
    fun parsesKnownLinks() {
        assertEquals(DeepLink.OrderDetail("abc-123"), DeepLink.parse("campusmarket://orders/abc-123"))
        assertEquals(DeepLink.OrderDetail("abc"), DeepLink.parse("campusmarket://orders/abc/?status=paid"))
        assertEquals(DeepLink.Payouts, DeepLink.parse("campusmarket://payouts"))
        assertEquals(DeepLink.Payouts, DeepLink.parse("campusmarket://payouts?refresh=1"))
    }

    @Test
    fun ignoresUnknownLinks() {
        assertNull(DeepLink.parse("https://example.com/orders/1"))
        assertNull(DeepLink.parse("campusmarket://orders"))
        assertNull(DeepLink.parse("campusmarket://listings/1"))
        assertNull(DeepLink.parse(null))
    }
}

class ServerConfigTest {
    private fun config(base: String) = ServerConfig(AppEnvironment(isDebug = true, defaultBaseUrl = base, platform = PlatformKind.ANDROID))

    @Test
    fun rewritesLoopbackMediaUrlsToBaseUrl() {
        val c = config("http://10.0.2.2:8080/")
        assertEquals("http://10.0.2.2:8080/uploads/a.jpg", c.resolve("http://localhost:8080/uploads/a.jpg"))
        assertEquals("http://10.0.2.2:8080/uploads/a.jpg", c.resolve("http://127.0.0.1:8080/uploads/a.jpg"))
        assertEquals("http://10.0.2.2:8080/uploads/a.jpg", c.resolve("/uploads/a.jpg"))
        assertEquals("https://cdn.example.com/a.jpg", c.resolve("https://cdn.example.com/a.jpg"))
        assertNull(c.resolve(null))
    }

    @Test
    fun buildsWebsocketUrl() {
        assertEquals("ws://localhost:8080/api/v1/ws", config("http://localhost:8080").wsUrl("/api/v1/ws"))
        assertEquals("wss://api.example.com/api/v1/ws", config("https://api.example.com").wsUrl("/api/v1/ws"))
    }

    @Test
    fun overrideOnlyInDebug() {
        val release = ServerConfig(AppEnvironment(isDebug = false, defaultBaseUrl = "http://a", platform = PlatformKind.IOS))
        release.setOverride("http://b")
        assertEquals("http://a", release.baseUrl.value)

        val debug = config("http://a")
        debug.setOverride("192.168.1.5:8080")
        assertEquals("http://192.168.1.5:8080", debug.baseUrl.value)
        debug.setOverride(null)
        assertEquals("http://a", debug.baseUrl.value)
    }
}

class FormatTest {
    @Test
    fun parsesPrices() {
        assertEquals(1200, Format.parsePriceCents("12"))
        assertEquals(1250, Format.parsePriceCents("12.5"))
        assertEquals(1205, Format.parsePriceCents("$12.05"))
        assertEquals(120000, Format.parsePriceCents("1,200.00"))
        assertNull(Format.parsePriceCents("12.345"))
        assertNull(Format.parsePriceCents("abc"))
        assertNull(Format.parsePriceCents(""))
        assertEquals("12.50", Format.priceInput(1250))
        assertEquals("12", Format.priceInput(1200))
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PaginatorTest {
    @Test
    fun loadsPagesUntilEndAndDedupes() = runTest {
        val pages = mapOf(
            null to Page(listOf(1, 2, 3), "c1"),
            "c1" to Page(listOf(3, 4), null),
        )
        val p = Paginator<Int>(this, pageSize = 3, key = { it }) { cursor, _ -> pages.getValue(cursor) }

        p.refresh()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), p.state.value.items)
        assertFalse(p.state.value.endReached)

        p.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4), p.state.value.items)
        assertTrue(p.state.value.endReached)

        p.loadMore() // no-op at the end
        advanceUntilIdle()
        assertEquals(4, p.state.value.items.size)
    }

    @Test
    fun firstPageErrorThenRetry() = runTest {
        var fail = true
        val p = Paginator<Int>(this) { _, _ ->
            if (fail) throw ApiError.network() else Page(listOf(1), null)
        }
        p.refresh()
        advanceUntilIdle()
        assertEquals(ApiError.NETWORK, p.state.value.error?.code)
        assertFalse(p.state.value.loaded)

        fail = false
        p.retry()
        advanceUntilIdle()
        assertEquals(listOf(1), p.state.value.items)
        assertNull(p.state.value.error)
    }

    @Test
    fun refreshErrorKeepsItems() = runTest {
        var fail = false
        val p = Paginator<Int>(this) { _, _ -> if (fail) throw ApiError.network() else Page(listOf(1, 2), null) }
        p.refresh()
        advanceUntilIdle()
        fail = true
        p.refresh()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), p.state.value.items)
        assertFalse(p.state.value.isRefreshing)
    }
}
