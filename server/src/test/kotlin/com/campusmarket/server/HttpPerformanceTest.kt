package com.campusmarket.server

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ChatCommand
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Page
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPerformanceTest {
    private fun HttpResponse.gunzipped(bytes: ByteArray): String {
        assertEquals("gzip", headers[HttpHeaders.ContentEncoding])
        return GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()
    }

    @Test
    fun jsonIsGzippedForClientsThatAcceptIt() = apiTest { env ->
        val seller = env.user()
        repeat(25) { env.listing(seller, title = "Lamp number $it") }

        val zipped = client.get("${ApiRoutes.LISTINGS}?limit=20") { auth(seller); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, zipped.status)
        val bytes = zipped.bodyAsBytes()
        val page = ApiJson.decodeFromString<Page<ListingSummary>>(zipped.gunzipped(bytes))
        assertEquals(20, page.items.size)

        val plain = client.get("${ApiRoutes.LISTINGS}?limit=20") { auth(seller) }
        assertNull(plain.headers[HttpHeaders.ContentEncoding])
        assertEquals(page, ApiJson.decodeFromString<Page<ListingSummary>>(plain.bodyAsText()))
        assertTrue(bytes.size * 3 < plain.bodyAsBytes().size, "gzip should shrink a page of listings several times")

        // tiny bodies aren't worth it
        val health = client.get(ApiRoutes.HEALTH) { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertNull(health.headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun photosAndWebsocketsAreNeverCompressed() = apiTest { env ->
        val key = UUID.randomUUID().toString().replace("-", "") + ".jpg"
        val bytes = ByteArray(64 * 1024) { (it % 7).toByte() } // very compressible, so only the content type decides
        Files.write(Paths.get(env.config.uploadsDir).resolve(key), bytes)
        val res = client.get("${ApiRoutes.UPLOADS}/$key") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertNull(res.headers[HttpHeaders.ContentEncoding])
        assertEquals(bytes.size.toString(), res.headers[HttpHeaders.ContentLength])
        assertTrue(bytes.contentEquals(res.bodyAsBytes()))

        val user = env.user()
        client.webSocket("${ApiRoutes.WS}?token=${user.token}", request = { header(HttpHeaders.AcceptEncoding, "gzip") }) {
            send(Frame.Text(ApiJson.encodeToString(ChatCommand.serializer(), ChatCommand.Ping)))
            val frame = withTimeout(5_000) { incoming.receive() }
            assertIs<Frame.Text>(frame)
            assertEquals(ChatEvent.Pong, ApiJson.decodeFromString(ChatEvent.serializer(), frame.readText()))
        }
    }

    @Test
    fun categoriesRevalidateWithAnETag() = apiTest { env ->
        val user = env.user()
        env.listing(user)
        val first = client.get(ApiRoutes.CATEGORIES) { auth(user) }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("private, no-cache", first.headers[HttpHeaders.CacheControl])
        assertEquals("application/json; charset=UTF-8", first.headers[HttpHeaders.ContentType])
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        assertTrue(first.bodyAsText().contains("\"TEXTBOOKS\""))

        val same = client.get(ApiRoutes.CATEGORIES) { auth(user); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, same.status)
        assertEquals("", same.bodyAsText())
        assertEquals(etag, same.headers[HttpHeaders.ETag])

        env.listing(user, title = "Another book")
        val changed = client.get(ApiRoutes.CATEGORIES) { auth(user); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.OK, changed.status)
        assertTrue(changed.headers[HttpHeaders.ETag] != etag)
    }

    @Test
    fun legalPagesRevalidateWithAnETag() = apiTest {
        val first = client.get(ApiRoutes.LEGAL_TERMS)
        val etag = assertNotNull(first.headers[HttpHeaders.ETag])
        assertEquals("public, max-age=300", first.headers[HttpHeaders.CacheControl])
        val again = client.get(ApiRoutes.LEGAL_TERMS) { header(HttpHeaders.IfNoneMatch, "\"other\", $etag") }
        assertEquals(HttpStatusCode.NotModified, again.status)
        assertNotNull(again.headers["Content-Security-Policy"])
    }

    @Test
    fun summariesAndProfilesCarryTheSmallVariants() = apiTest { env ->
        val seller = env.user("sam")
        val viewer = env.user()
        val listingId = env.listing(seller)
        env.db.tx { c ->
            c.update(
                """UPDATE photos SET storage_key = 'orig.jpg', thumb_key = 'orig-t.jpg', display_key = 'orig-d.jpg',
                          width = 1200, height = 900, variants_done = true
                   WHERE id = (SELECT photo_id FROM listing_photos WHERE listing_id = ?)""",
                listingId,
            )
            c.update("UPDATE users SET avatar_photo_id = (SELECT photo_id FROM listing_photos WHERE listing_id = ?) WHERE id = ?", listingId, seller.id)
        }
        val base = env.config.publicBaseUrl + ApiRoutes.UPLOADS

        val summary = client.get(ApiRoutes.LISTINGS) { auth(viewer) }.body<Page<ListingSummary>>().items.single()
        assertEquals("$base/orig.jpg", summary.thumbnailUrl)
        assertEquals("$base/orig-t.jpg", summary.thumbnailSmallUrl)
        assertEquals(1200 to 900, summary.thumbnailWidth to summary.thumbnailHeight)

        val profile = client.get(ApiRoutes.user(seller.id.toString())) { auth(viewer) }.body<UserPublic>()
        assertEquals("$base/orig.jpg", profile.avatarUrl)
        assertEquals("$base/orig-t.jpg", profile.avatarThumbnailUrl)
        assertEquals("$base/orig-t.jpg", client.get(ApiRoutes.ME) { auth(seller) }.body<UserPrivate>().avatarThumbnailUrl)

        // not generated yet: only the original
        env.db.tx { c -> c.update("UPDATE photos SET thumb_key = NULL, display_key = NULL, variants_done = false") }
        val pending = client.get(ApiRoutes.LISTINGS) { auth(viewer) }.body<Page<ListingSummary>>().items.single()
        assertEquals("$base/orig.jpg", pending.thumbnailUrl)
        assertNull(pending.thumbnailSmallUrl)
        assertNull(client.get(ApiRoutes.user(seller.id.toString())) { auth(viewer) }.body<UserPublic>().avatarThumbnailUrl)
    }

    @Test
    fun keysetCursorsOfTheOlderFormStillWork() {
        val at = java.time.Instant.parse("2026-03-04T05:06:07.123456Z")
        val id = UUID.randomUUID()
        val legacy = Base64.getUrlEncoder().withoutPadding().encodeToString("${at.epochSecond}:${at.nano}:$id".toByteArray())
        assertEquals(KeysetCursor(at, id), KeysetCursor.decode(legacy))
        assertEquals(KeysetCursor(at, id), KeysetCursor.decode(KeysetCursor(at, id).encode()))
    }
}
