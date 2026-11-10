package com.campusmarket.server.photos

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.Photo
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoVariantRoutesTest {

    private suspend fun ApiTestScope.upload(user: TestEnv.TestUser, bytes: ByteArray, filename: String = "photo.jpg"): Photo =
        client.submitFormWithBinaryData(
            ApiRoutes.PHOTOS,
            formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                })
            },
        ) { auth(user) }.also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private fun path(url: String) = url.removePrefix(TestEnv.config.publicBaseUrl)

    private suspend fun ApiTestScope.decoded(url: String): Pair<Int, Int> {
        val res = client.get(path(url))
        assertEquals(HttpStatusCode.OK, res.status, url)
        assertEquals("image/jpeg", res.headers[HttpHeaders.ContentType])
        val img = ImageIO.read(ByteArrayInputStream(res.bodyAsBytes()))
        return img.width to img.height
    }

    @Test
    fun uploadMakesVariantsAndListingDetailCarriesThem() = apiTest { env ->
        val sam = env.user("sam")
        val photo = upload(sam, PhotoTestImages.jpeg(2000, 1500))
        val key = photo.url.substringAfterLast('/')
        assertEquals(env.config.publicBaseUrl + ApiRoutes.UPLOADS + "/" + PhotoVariants.thumbKey(key), photo.thumbnailUrl)
        assertEquals(env.config.publicBaseUrl + ApiRoutes.UPLOADS + "/" + PhotoVariants.displayKey(key), photo.displayUrl)
        assertEquals(640 to 480, decoded(photo.thumbnailUrl!!))
        assertEquals(1280 to 960, decoded(photo.displayUrl!!))
        val row = env.db.tx { c ->
            c.queryOne("SELECT thumb_key, display_key, variants_done FROM photos WHERE id = ?", UUID.fromString(photo.id)) {
                Triple(it.getString(1), it.getString(2), it.getBoolean(3))
            }
        }
        assertEquals(Triple(PhotoVariants.thumbKey(key), PhotoVariants.displayKey(key), true), row)

        val req = CreateListingRequest("Desk lamp", "Bright", ListingCategory.DORM, ListingCondition.GOOD, 1500, "", listOf(photo.id))
        val listing = client.post(ApiRoutes.LISTINGS) { auth(sam); json(req) }.body<ListingDetail>()
        assertEquals(photo, listing.photos.single())
        assertEquals(photo, client.get(ApiRoutes.listing(listing.id)) { auth(sam) }.body<ListingDetail>().photos.single())
    }

    @Test
    fun webpUploadHasNoVariants() = apiTest { env ->
        val sam = env.user()
        val photo = upload(sam, PhotoTestImages.webpLossy(300, 200), "a.webp")
        assertNull(photo.thumbnailUrl)
        assertNull(photo.displayUrl)
        assertEquals(true, env.db.tx { c -> c.queryOne("SELECT variants_done FROM photos WHERE id = ?", UUID.fromString(photo.id)) { it.getBoolean(1) } })
    }

    @Test
    fun conditionalRequestsRangesAndHead() = apiTest { env ->
        val sam = env.user()
        val photo = upload(sam, PhotoTestImages.jpeg(300, 200))
        val url = path(photo.url)
        val full = client.get(url)
        val bytes = full.bodyAsBytes()
        val size = bytes.size
        assertEquals(size.toString(), full.headers[HttpHeaders.ContentLength])
        assertEquals("bytes", full.headers[HttpHeaders.AcceptRanges])
        val etag = assertNotNull(full.headers[HttpHeaders.ETag])
        val lastModified = assertNotNull(full.headers[HttpHeaders.LastModified])

        assertEquals(HttpStatusCode.NotModified, client.get(url) { header(HttpHeaders.IfNoneMatch, etag) }.status)
        assertEquals(HttpStatusCode.NotModified, client.get(url) { header(HttpHeaders.IfModifiedSince, lastModified) }.status)
        assertEquals(HttpStatusCode.OK, client.get(url) { header(HttpHeaders.IfModifiedSince, "Sat, 01 Jan 2000 00:00:00 GMT") }.status)
        // If-None-Match takes precedence over If-Modified-Since
        assertEquals(HttpStatusCode.OK, client.get(url) { header(HttpHeaders.IfNoneMatch, "\"other\""); header(HttpHeaders.IfModifiedSince, lastModified) }.status)

        val head = client.get(url) { header(HttpHeaders.Range, "bytes=0-9") }
        assertEquals(HttpStatusCode.PartialContent, head.status)
        assertEquals("bytes 0-9/$size", head.headers[HttpHeaders.ContentRange])
        assertContentEquals(bytes.copyOfRange(0, 10), head.bodyAsBytes())

        val tail = client.get(url) { header(HttpHeaders.Range, "bytes=-5") }
        assertEquals(HttpStatusCode.PartialContent, tail.status)
        assertEquals("bytes ${size - 5}-${size - 1}/$size", tail.headers[HttpHeaders.ContentRange])
        assertContentEquals(bytes.copyOfRange(size - 5, size), tail.bodyAsBytes())

        val open = client.get(url) { header(HttpHeaders.Range, "bytes=100-") }
        assertEquals(HttpStatusCode.PartialContent, open.status)
        assertContentEquals(bytes.copyOfRange(100, size), open.bodyAsBytes())

        val unsatisfiable = client.get(url) { header(HttpHeaders.Range, "bytes=$size-") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, unsatisfiable.status)
        assertEquals("bytes */$size", unsatisfiable.headers[HttpHeaders.ContentRange])

        // several ranges, a stale If-Range or a malformed header: the whole file
        assertEquals(HttpStatusCode.OK, client.get(url) { header(HttpHeaders.Range, "bytes=0-1,5-6") }.status)
        assertEquals(HttpStatusCode.OK, client.get(url) { header(HttpHeaders.Range, "bytes=0-1"); header(HttpHeaders.IfRange, "\"old\"") }.status)
        assertEquals(HttpStatusCode.PartialContent, client.get(url) { header(HttpHeaders.Range, "bytes=0-1"); header(HttpHeaders.IfRange, etag) }.status)
        assertEquals(HttpStatusCode.OK, client.get(url) { header(HttpHeaders.Range, "items=0-1") }.status)

        val headRes = client.head(url)
        assertEquals(HttpStatusCode.OK, headRes.status)
        assertEquals(size.toString(), headRes.headers[HttpHeaders.ContentLength])
        assertEquals("image/jpeg", headRes.headers[HttpHeaders.ContentType])
        assertEquals(0, headRes.bodyAsBytes().size)
        assertEquals(HttpStatusCode.NotFound, client.head("${ApiRoutes.UPLOADS}/missing.jpg").status)
    }

    @Test
    fun backfillMakesVariantsOfEarlierPhotos() = apiTest { env ->
        val sam = env.user()
        val uploads = Paths.get(env.config.uploadsDir)
        val key = "backfill${UUID.randomUUID().toString().replace("-", "").take(16)}.jpg"
        Files.write(uploads.resolve(key), PhotoTestImages.jpeg(2400, 1800))
        val withFile = UUID.randomUUID()
        val shared = UUID.randomUUID()
        val missing = env.photo(sam) // fixture: no file behind it
        env.db.tx { c ->
            for (id in listOf(withFile, shared)) {
                c.update(
                    "INSERT INTO photos (id, owner_id, storage_key, content_type, size_bytes, width, height) VALUES (?, ?, ?, 'image/jpeg', 1, 2400, 1800)",
                    id, sam.id, key,
                )
            }
        }
        val done = VariantBackfill(env.db, PhotoVariants(LocalPhotoStorage(uploads))).run()
        assertEquals(3, done)
        val rows = env.db.tx { c ->
            c.query("SELECT id, thumb_key, display_key, variants_done FROM photos") {
                it.getObject(1, UUID::class.java) to Triple(it.getString(2), it.getString(3), it.getBoolean(4))
            }.toMap()
        }
        val expected = Triple(PhotoVariants.thumbKey(key), PhotoVariants.displayKey(key), true)
        assertEquals(expected, rows[withFile])
        assertEquals(expected, rows[shared])
        assertEquals(Triple(null, null, true), rows[missing])
        assertTrue(Files.isRegularFile(uploads.resolve(expected.first)))
        assertTrue(Files.isRegularFile(uploads.resolve(expected.second)))
        // nothing left to do
        assertEquals(0, VariantBackfill(env.db, PhotoVariants(LocalPhotoStorage(uploads))).run())
    }
}
