package com.campusmarket.server.photos

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.Limits
import com.campusmarket.core.Photo
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import com.campusmarket.server.auth
import com.campusmarket.server.json
import com.campusmarket.server.photos.PhotoTestImages.indexOf
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhotoRoutesTest {

    private suspend fun ApiTestScope.upload(
        user: TestEnv.TestUser?,
        bytes: ByteArray,
        filename: String = "photo.jpg",
        declaredType: String = "image/jpeg",
        partName: String = "file",
    ): HttpResponse = client.submitFormWithBinaryData(
        ApiRoutes.PHOTOS,
        formData {
            append(partName, bytes, Headers.build {
                append(HttpHeaders.ContentType, declaredType)
                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
            })
        },
    ) { if (user != null) auth(user) }

    private fun keyOf(photo: Photo) = photo.url.substringAfterLast('/')

    private fun uploadsDir() = Paths.get(TestEnv.config.uploadsDir)

    private fun incomingIsEmpty() = Files.notExists(uploadsDir().resolve(".incoming")) ||
        uploadsDir().resolve(".incoming").listDirectoryEntries().isEmpty()

    private suspend fun assertFileError(res: HttpResponse, status: HttpStatusCode = HttpStatusCode.BadRequest) {
        assertEquals(status, res.status)
        val err = res.body<ErrorResponse>()
        assertEquals(ErrorCodes.VALIDATION, err.code)
        assertTrue("file" in err.fields, "expected a 'file' field error, got $err")
    }

    @Test
    fun jpegUploadStripsPrivateMetadataButKeepsOrientation() = apiTest { env ->
        val sam = env.user("sam")
        val original = PhotoTestImages.jpeg(40, 20)
        val withMeta = PhotoTestImages.withMetadata(
            original,
            listOf(
                PhotoTestImages.exifSegment(orientation = 6, secret = "SECRET-GPS-48.8584N"),
                PhotoTestImages.segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>SECRET-XMP</x:xmpmeta>".toByteArray()),
                PhotoTestImages.segment(0xFE, "SECRET-COMMENT".toByteArray()),
            ),
            trailer = "SECRET-TRAILER-motion-photo".toByteArray(),
        )

        val res = upload(sam, withMeta)
        assertEquals(HttpStatusCode.Created, res.status)
        val photo = res.body<Photo>()
        // displayed size: orientation 6 rotates 90°, so 40x20 is shown as 20x40
        assertEquals(20, photo.width)
        assertEquals(40, photo.height)
        assertTrue(photo.url.startsWith("${env.config.publicBaseUrl}${ApiRoutes.UPLOADS}/"))
        assertTrue(Regex("[0-9a-f]{32}\\.jpg").matches(keyOf(photo)), keyOf(photo))

        val served = client.get(photo.url.removePrefix(env.config.publicBaseUrl))
        assertEquals(HttpStatusCode.OK, served.status)
        assertEquals("image/jpeg", served.headers[HttpHeaders.ContentType])
        val cache = served.headers[HttpHeaders.CacheControl].orEmpty()
        assertTrue("public" in cache && "max-age=31536000" in cache && "immutable" in cache, cache)
        assertEquals("nosniff", served.headers["X-Content-Type-Options"])
        val bytes = served.bodyAsBytes()
        assertEquals(-1, bytes.indexOf("SECRET".toByteArray()), "private metadata must be stripped")
        assertTrue(bytes.indexOf("Exif".toByteArray()) in 0..10, "orientation EXIF segment must lead the file")
        assertEquals(0xD9, bytes.last().toInt() and 0xFF, "file must end at EOI")
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(40, decoded.width)
        assertEquals(20, decoded.height)

        val row = env.db.tx { c ->
            c.queryOne("SELECT owner_id, storage_key, content_type, size_bytes, width, height FROM photos WHERE id = ?", UUID.fromString(photo.id)) {
                listOf<Any?>(it.getObject(1, UUID::class.java), it.getString(2), it.getString(3), it.getLong(4), it.getInt(5), it.getInt(6))
            }
        }
        assertEquals(listOf<Any?>(sam.id, keyOf(photo), "image/jpeg", bytes.size.toLong(), 20, 40), row)
        assertTrue(incomingIsEmpty(), "scratch files must be cleaned up")
    }

    @Test
    fun pngIsStoredVerbatimWithDimensions() = apiTest { env ->
        val sam = env.user()
        val png = PhotoTestImages.png(7, 5)
        val res = upload(sam, png, filename = "shot.png", declaredType = "image/png")
        assertEquals(HttpStatusCode.Created, res.status)
        val photo = res.body<Photo>()
        assertEquals(7, photo.width)
        assertEquals(5, photo.height)
        assertTrue(photo.url.endsWith(".png"))
        val served = client.get(photo.url.removePrefix(env.config.publicBaseUrl))
        assertEquals("image/png", served.headers[HttpHeaders.ContentType])
        assertContentEquals(png, served.bodyAsBytes())
    }

    @Test
    fun formatIsSniffedFromBytesNotFromDeclaredType() = apiTest { env ->
        val sam = env.user()
        val res = upload(sam, PhotoTestImages.webpLossless(300, 200), filename = "upload.bin", declaredType = "application/octet-stream")
        assertEquals(HttpStatusCode.Created, res.status)
        val photo = res.body<Photo>()
        assertTrue(photo.url.endsWith(".webp"))
        assertEquals(300, photo.width)
        assertEquals(200, photo.height)
        val served = client.get(photo.url.removePrefix(env.config.publicBaseUrl))
        assertEquals("image/webp", served.headers[HttpHeaders.ContentType])

        // A JPEG declared as PNG is still a JPEG.
        val jpegAsPng = upload(sam, PhotoTestImages.jpeg(3, 3), filename = "x.png", declaredType = "image/png").body<Photo>()
        assertTrue(jpegAsPng.url.endsWith(".jpg"))
    }

    @Test
    fun nonImagesAreRejectedWhateverTheyClaimToBe() = apiTest { env ->
        val sam = env.user()
        assertFileError(upload(sam, "<html><script>alert(1)</script></html>".toByteArray()))
        assertFileError(upload(sam, "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000".toByteArray(), "a.gif", "image/gif"))
        assertFileError(upload(sam, "%PDF-1.7 hello".toByteArray(), "a.jpg", "image/jpeg"))
        assertFileError(upload(sam, ByteArray(0)))
        assertFileError(upload(sam, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) // too short to be anything
        // JPEG magic but no image structure
        assertFileError(upload(sam, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 1, 2, 3)))
        // decompression bomb: tiny file, 256 megapixels
        assertFileError(upload(sam, PhotoTestImages.webpExtended(16000, 16000), "bomb.webp", "image/webp"))
        assertEquals(0, env.db.tx { c -> c.queryOne("SELECT count(*) FROM photos") { it.getInt(1) } })
        assertTrue(incomingIsEmpty())
    }

    @Test
    fun sizeCapIsEnforcedWhileStreaming() = apiTest { env ->
        val sam = env.user()
        val jpeg = PhotoTestImages.jpeg(16, 16)

        // Exactly at the cap is fine (the junk after EOI is dropped by the sanitizer).
        val atCap = jpeg + ByteArray(Limits.PHOTO_MAX_BYTES - jpeg.size)
        val ok = upload(sam, atCap)
        assertEquals(HttpStatusCode.Created, ok.status)
        val served = client.get(ok.body<Photo>().url.removePrefix(env.config.publicBaseUrl)).bodyAsBytes()
        assertEquals(jpeg.size, served.size)

        // One byte over is rejected with 413, and nothing is left behind.
        assertFileError(upload(sam, jpeg + ByteArray(Limits.PHOTO_MAX_BYTES - jpeg.size + 1)), HttpStatusCode.PayloadTooLarge)
        // Far over: rejected up front from Content-Length.
        assertFileError(upload(sam, ByteArray(3 * Limits.PHOTO_MAX_BYTES)), HttpStatusCode.PayloadTooLarge)
        assertEquals(1, env.db.tx { c -> c.queryOne("SELECT count(*) FROM photos") { it.getInt(1) } })
        assertTrue(incomingIsEmpty())
    }

    @Test
    fun chunkedUploadsWithoutContentLengthAreRefused() = apiTest { env ->
        val sam = env.user()
        val boundary = "cm-test-boundary"
        val body = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
            override val contentLength: Long? = null // forces chunked transfer
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                channel.writeFully(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
                val chunk = ByteArray(64 * 1024)
                repeat(3 * Limits.PHOTO_MAX_BYTES / chunk.size) { channel.writeFully(chunk) }
                channel.writeFully("\r\n--$boundary--\r\n".toByteArray())
            }
        }
        val res = client.post(ApiRoutes.PHOTOS) { auth(sam); setBody(body) }
        assertEquals(HttpStatusCode.LengthRequired, res.status)
        assertEquals(ErrorCodes.VALIDATION, res.body<ErrorResponse>().code)
        assertTrue(incomingIsEmpty())
    }

    @Test
    fun requestShapeErrors() = apiTest { env ->
        val sam = env.user()
        // no auth
        assertEquals(HttpStatusCode.Unauthorized, upload(null, PhotoTestImages.png(2, 2)).status)
        // wrong part name
        assertFileError(upload(sam, PhotoTestImages.png(2, 2), partName = "image"))
        // not multipart
        assertFileError(client.post(ApiRoutes.PHOTOS) { auth(sam); json(mapOf("file" to "abc")) })
        // `file` sent as a plain form field instead of a file
        val res = client.submitFormWithBinaryData(ApiRoutes.PHOTOS, formData { append("file", "not a file") }) { auth(sam) }
        assertFileError(res)
    }

    @Test
    fun uploadsAreRateLimitedPerUser() = apiTest { env ->
        val sam = env.user()
        env.db.tx { c ->
            c.update(
                """INSERT INTO photos (id, owner_id, storage_key, content_type, size_bytes)
                   SELECT gen_random_uuid(), ?, 'k' || g || '.jpg', 'image/jpeg', 1 FROM generate_series(1, ?) g""",
                sam.id, PhotoUploads.MAX_UPLOADS_PER_HOUR,
            )
        }
        val res = upload(sam, PhotoTestImages.png(2, 2), "a.png", "image/png")
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertEquals(ErrorCodes.RATE_LIMITED, res.body<ErrorResponse>().code)
        // older uploads don't count
        env.db.tx { c -> c.update("UPDATE photos SET created_at = now() - interval '2 hours' WHERE owner_id = ?", sam.id) }
        assertEquals(HttpStatusCode.Created, upload(sam, PhotoTestImages.png(2, 2), "a.png", "image/png").status)
    }

    @Test
    fun servingIsPublicCacheableAndConfinedToTheUploadsDir() = apiTest { env ->
        val sam = env.user()
        val photo = upload(sam, PhotoTestImages.png(4, 4), "a.png", "image/png").body<Photo>()
        val path = photo.url.removePrefix(env.config.publicBaseUrl)

        // public: no Authorization header
        val res = client.get(path)
        assertEquals(HttpStatusCode.OK, res.status)
        val etag = assertNotNull(res.headers[HttpHeaders.ETag])
        val notModified = client.get(path) { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, notModified.status)

        // a file next to (outside) the uploads dir and one in the scratch dir must not be reachable
        val parent = uploadsDir().toAbsolutePath().parent
        val secretName = "cm-secret-${UUID.randomUUID().toString().take(8)}.jpg"
        val outside = parent.resolve(secretName)
        Files.write(outside, "top secret".toByteArray())
        val scratch = uploadsDir().resolve(".incoming").resolve("pending.jpg")
        Files.createDirectories(scratch.parent)
        Files.write(scratch, "partial".toByteArray())
        try {
            for (attempt in listOf(
                "${ApiRoutes.UPLOADS}/..%2F$secretName",
                "${ApiRoutes.UPLOADS}/%2E%2E%2F$secretName",
                "${ApiRoutes.UPLOADS}/..%5C$secretName",
                "${ApiRoutes.UPLOADS}/.incoming%2Fpending.jpg",
                "${ApiRoutes.UPLOADS}/.incoming",
                "${ApiRoutes.UPLOADS}/missing.jpg",
                "${ApiRoutes.UPLOADS}/${UUID.randomUUID()}.jpg",
                "${ApiRoutes.UPLOADS}/",
            )) {
                val r = client.get(attempt)
                assertEquals(HttpStatusCode.NotFound, r.status, attempt)
                assertTrue("secret" !in r.bodyAsBytes().decodeToString(), attempt)
            }
        } finally {
            Files.deleteIfExists(outside)
            Files.deleteIfExists(scratch)
        }
    }

    @Test
    fun fixturePhotosWithoutFilesAre404() = apiTest { env ->
        val sam = env.user()
        val id = env.photo(sam)
        val key = env.db.tx { c -> c.query("SELECT storage_key FROM photos WHERE id = ?", id) { it.getString(1) }.single() }
        assertEquals(HttpStatusCode.NotFound, client.get("${ApiRoutes.UPLOADS}/$key").status)
    }
}
