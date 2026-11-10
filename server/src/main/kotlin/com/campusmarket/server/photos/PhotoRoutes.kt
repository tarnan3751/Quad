package com.campusmarket.server.photos

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.Photo
import com.campusmarket.server.AUTH_JWT
import com.campusmarket.server.ApiException
import com.campusmarket.server.AppDeps
import com.campusmarket.server.badRequest
import com.campusmarket.server.invalidField
import com.campusmarket.server.notFound
import com.campusmarket.server.photoUrl
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import com.campusmarket.server.userId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Entry point for the photos feature; called once from Application.module. See docs/CONTRACT.md. */
fun Route.photoRoutes(deps: AppDeps) = photoRoutes(deps, LocalPhotoStorage(Paths.get(deps.config.uploadsDir)))

/** Same as [photoRoutes] with an explicit storage backend. */
fun Route.photoRoutes(deps: AppDeps, storage: PhotoStorage) {
    val uploads = PhotoUploads(deps, storage)

    authenticate(AUTH_JWT) {
        post(ApiRoutes.PHOTOS) {
            call.respond(HttpStatusCode.Created, uploads.handle(call))
        }
    }

    // Public: photo URLs are embedded in listings and profiles and fetched by image loaders without auth.
    get("${ApiRoutes.UPLOADS}/{key}") {
        val photo = storage.open(call.parameters["key"].orEmpty()) ?: notFound("Photo")
        // keys are random and never reused, so the bytes behind a URL never change
        val etag = "\"${call.parameters["key"]}\""
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header("X-Content-Type-Options", "nosniff")
        val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
        if (ifNoneMatch != null && ifNoneMatch.split(',').map { it.trim().removePrefix("W/") }.any { it == etag || it == "*" }) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.respond(StoredPhotoContent(photo))
        }
    }
}

private class StoredPhotoContent(private val photo: StoredPhoto) : OutgoingContent.ReadChannelContent() {
    override val contentType: ContentType = ContentType.parse(photo.format.contentType)
    override val contentLength: Long = photo.size
    override fun readFrom(): ByteReadChannel = photo.readChannel()
}

/** POST /photos: streams the `file` part to disk under the size cap, sniffs, strips metadata, stores, records. */
internal class PhotoUploads(
    private val deps: AppDeps,
    private val storage: PhotoStorage,
    private val maxUploadsPerHour: Int = MAX_UPLOADS_PER_HOUR,
) {
    companion object {
        /** Per-user abuse guard; generous for normal use (a listing has at most 8 photos). */
        const val MAX_UPLOADS_PER_HOUR = 100

        /** Bound on the request as a whole: the photo plus multipart framing and a few small fields. */
        private const val MAX_REQUEST_BYTES = Limits.PHOTO_MAX_BYTES + 64L * 1024

        /**
         * Ktor's per-part limit. It applies to file parts too and, when hit, fails the whole call with a 500
         * (the parser runs ahead of us in its own coroutine). Requiring a Content-Length of at most
         * [MAX_REQUEST_BYTES] guarantees no part can reach it, so our own streaming check always answers
         * first, with a clean 413.
         */
        private const val PART_LIMIT = MAX_REQUEST_BYTES

        /** Non-file parts are buffered in memory by Ktor, so only a couple are tolerated before `file`. */
        private const val MAX_PARTS_BEFORE_FILE = 2

        /** 120 MP: above any phone camera's output that fits in [Limits.PHOTO_MAX_BYTES]. */
        const val MAX_PIXELS = 120_000_000L

        private val log = LoggerFactory.getLogger(PhotoUploads::class.java)
    }

    private class Spooled(val path: Path, val format: ImageFormat, val header: ByteArray)

    suspend fun handle(call: ApplicationCall): Photo {
        val ownerId = call.userId
        if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
            invalidField("file", "Upload the photo as multipart/form-data in a part named 'file'")
        }
        val declaredLength = call.request.contentLength() ?: throw ApiException(
            HttpStatusCode.LengthRequired, ErrorCodes.VALIDATION, "Photo uploads must declare a Content-Length",
        )
        if (declaredLength > MAX_REQUEST_BYTES) tooLarge()
        enforceQuota(ownerId)

        val spooled = receiveFilePart(call.receiveMultipart(formFieldLimit = PART_LIMIT))
            ?: invalidField("file", "Choose a photo to upload")
        val leftovers = mutableListOf(spooled.path)
        try {
            val (finalPath, size) = when (spooled.format) {
                ImageFormat.JPEG -> {
                    val clean = withContext(Dispatchers.IO) { Files.createTempFile(storage.scratchDir, "clean-", ".part") }
                    leftovers.add(clean)
                    val result = try {
                        withContext(Dispatchers.IO) { JpegSanitizer.sanitize(spooled.path, clean) }
                    } catch (_: MalformedImageException) {
                        invalidField("file", "This image appears to be damaged")
                    }
                    clean to result.size
                }
                ImageFormat.PNG -> spooled.path to ImageHeaders.pngSize(spooled.header, spooled.header.size)
                ImageFormat.WEBP -> spooled.path to ImageHeaders.webpSize(spooled.header, spooled.header.size)
            }
            // A few KB of PNG can declare billions of pixels; don't hand decompression bombs to the apps.
            if (size != null && size.width.toLong() * size.height > MAX_PIXELS) {
                invalidField("file", "This image's dimensions are too large")
            }
            val bytes = withContext(Dispatchers.IO) { Files.size(finalPath) }
            val key = PhotoKeys.newKey(spooled.format)
            storage.save(key, finalPath)
            leftovers.remove(finalPath)

            val id = UUID.randomUUID()
            try {
                deps.db.tx { c ->
                    c.update(
                        """INSERT INTO photos (id, owner_id, storage_key, content_type, size_bytes, width, height)
                           VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        id, ownerId, key, spooled.format.contentType, bytes, size?.width, size?.height,
                    )
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { storage.delete(key) } }
                throw e
            }
            return Photo(id = id.toString(), url = deps.config.photoUrl(key), width = size?.width, height = size?.height)
        } finally {
            deleteQuietly(leftovers)
        }
    }

    private suspend fun enforceQuota(ownerId: UUID) {
        val recent = deps.db.tx { c ->
            c.queryOne("SELECT count(*) FROM photos WHERE owner_id = ? AND created_at > now() - interval '1 hour'", ownerId) { it.getLong(1) } ?: 0L
        }
        if (recent >= maxUploadsPerHour) {
            throw ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED, "Too many photo uploads, try again later")
        }
    }

    /**
     * Reads parts until the first one named `file` and spools it; returns null if there is none.
     * Anything after the photo is left unread. A rejected photo is deliberately not released: releasing
     * drains the rest of the part, i.e. would read an oversized upload to the end.
     */
    private suspend fun receiveFilePart(multipart: MultiPartData): Spooled? {
        var skipped = 0
        while (true) {
            val part = network { multipart.readPart() } ?: return null
            if (part.name == "file" && part is PartData.FileItem) {
                val spooled = spool(part.provider())
                release(part)
                return spooled
            }
            release(part)
            if (part.name == "file") invalidField("file", "The 'file' part must be a file upload (with a filename)")
            if (++skipped > MAX_PARTS_BEFORE_FILE) badRequest("Too many form parts; send only the 'file' part")
        }
    }

    private suspend fun release(part: PartData) = withContext(NonCancellable) { runCatching { part.release() } }

    /** Streams [channel] into a scratch file, enforcing the size cap and rejecting non-images as early as possible. */
    private suspend fun spool(channel: ByteReadChannel): Spooled {
        val tmp = withContext(Dispatchers.IO) { Files.createTempFile(storage.scratchDir, "upload-", ".part") }
        try {
            val header = ByteArray(ImageFormat.HEADER_SIZE)
            var headerLen = 0
            var format: ImageFormat? = null
            var total = 0L
            withContext(Dispatchers.IO) {
                Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = network { channel.readAvailable(buf, 0, buf.size) }
                        if (n < 0) break
                        total += n
                        if (total > Limits.PHOTO_MAX_BYTES) tooLarge()
                        if (headerLen < header.size) {
                            val k = minOf(n, header.size - headerLen)
                            System.arraycopy(buf, 0, header, headerLen, k)
                            headerLen += k
                        }
                        if (format == null && headerLen >= ImageFormat.SNIFF_SIZE) {
                            format = ImageFormat.sniff(header, headerLen) ?: unsupportedType()
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) invalidField("file", "The file is empty")
            val sniffed = format ?: ImageFormat.sniff(header, headerLen) ?: unsupportedType()
            return Spooled(tmp, sniffed, header.copyOf(headerLen))
        } catch (e: Throwable) {
            deleteQuietly(listOf(tmp))
            throw e
        }
    }

    /** Failures while reading the request body are the client's (malformed multipart, aborted upload). */
    private suspend fun <T> network(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug("Rejected malformed upload", e)
        badRequest("Malformed multipart upload")
    }

    private suspend fun deleteQuietly(paths: List<Path>) = withContext(NonCancellable + Dispatchers.IO) {
        paths.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun tooLarge(): Nothing {
        val msg = "Photos must be at most ${Limits.PHOTO_MAX_BYTES / (1024 * 1024)} MB"
        throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.VALIDATION, msg, mapOf("file" to msg))
    }

    private fun unsupportedType(): Nothing = invalidField("file", "Only JPEG, PNG or WebP images are allowed")
}
