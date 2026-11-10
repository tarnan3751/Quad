package com.campusmarket.server.photos

import com.campusmarket.server.notFound
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * GET / HEAD `/uploads/{key}`. Keys are random (or content-derived) and never reused, so the bytes behind a URL never
 * change: immutable caching, a key-based strong ETag, conditional requests (304) and single byte ranges (206 / 416).
 */
internal suspend fun ApplicationCall.respondPhoto(storage: PhotoStorage, head: Boolean) {
    val key = parameters["key"].orEmpty()
    val photo = storage.open(key) ?: notFound("Photo")
    val etag = "\"$key\""
    val lastModified = photo.lastModified.truncatedTo(ChronoUnit.SECONDS)
    response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.LastModified, HTTP_DATE.format(lastModified))
    response.header(HttpHeaders.AcceptRanges, "bytes")
    response.header("X-Content-Type-Options", "nosniff")

    // If-None-Match wins over If-Modified-Since (RFC 9110 13.1.3)
    val ifNoneMatch = request.header(HttpHeaders.IfNoneMatch)
    val notModified = if (ifNoneMatch != null) {
        ifNoneMatch.split(',').map { it.trim().removePrefix("W/") }.any { it == etag || it == "*" }
    } else {
        request.header(HttpHeaders.IfModifiedSince)?.let(::parseHttpDate)?.let { !lastModified.isAfter(it) } ?: false
    }
    if (notModified) return respond(HttpStatusCode.NotModified)

    val type = ContentType.parse(photo.format.contentType)
    val range = request.header(HttpHeaders.Range)
        ?.takeIf { !head && ifRangeMatches(etag, lastModified) }
        ?.let { parseByteRange(it, photo.size) }
    when {
        range == null -> respond(if (head) HeadContent(type, photo.size) else PhotoBytes(photo, type, 0 until photo.size))
        range.isEmpty() -> {
            response.header(HttpHeaders.ContentRange, "bytes */${photo.size}")
            respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        else -> {
            response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${photo.size}")
            respond(HttpStatusCode.PartialContent, PhotoBytes(photo, type, range))
        }
    }
}

private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

private fun parseHttpDate(raw: String): Instant? = runCatching { ZonedDateTime.parse(raw.trim(), HTTP_DATE).toInstant() }.getOrNull()

/** No `If-Range`, or one naming this version (the ETag, or the exact Last-Modified date): the range applies. */
private fun ApplicationCall.ifRangeMatches(etag: String, lastModified: Instant): Boolean {
    val ifRange = request.header(HttpHeaders.IfRange)?.trim() ?: return true
    return if (ifRange.startsWith("\"") || ifRange.startsWith("W/")) ifRange == etag else parseHttpDate(ifRange) == lastModified
}

/**
 * The single byte range a `Range` header asks for: null to ignore the header (malformed, not bytes, several ranges:
 * the whole file is sent), an empty range when it can't be satisfied (416).
 */
internal fun parseByteRange(header: String, size: Long): LongRange? {
    val spec = header.trim()
    if (!spec.startsWith("bytes=", ignoreCase = true)) return null
    val value = spec.substring(6).trim()
    val dash = value.indexOf('-')
    if (',' in value || dash < 0) return null
    val first = value.substring(0, dash).trim()
    val last = value.substring(dash + 1).trim()
    if (first.isEmpty()) { // suffix range: the last N bytes
        val n = last.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return if (n == 0L || size == 0L) LongRange.EMPTY else maxOf(0L, size - n) until size
    }
    val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull()?.takeIf { it >= start } ?: return null
    return if (start >= size) LongRange.EMPTY else start..minOf(end, size - 1)
}

/** [range] of a stored photo, streamed from storage. */
private class PhotoBytes(private val photo: StoredPhoto, override val contentType: ContentType, private val range: LongRange) :
    OutgoingContent.ReadChannelContent() {
    override val contentLength: Long = range.last - range.first + 1
    override fun readFrom(): ByteReadChannel = photo.readChannel(range)
}

private class HeadContent(override val contentType: ContentType, override val contentLength: Long) : OutgoingContent.NoContent()
