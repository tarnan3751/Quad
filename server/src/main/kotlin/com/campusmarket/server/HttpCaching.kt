package com.campusmarket.server

import com.campusmarket.core.ApiJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.serializer
import java.security.MessageDigest
import java.util.Base64

/** Weak validator of a response body (weak: the same entity may be sent gzip-encoded or not). */
fun weakETag(body: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
    return "W/\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(16)) + "\""
}

/** Whether the request's `If-None-Match` names [etag] (weak comparison, as RFC 9110 prescribes for it). */
fun ApplicationCall.matchesIfNoneMatch(etag: String): Boolean {
    val header = request.header(HttpHeaders.IfNoneMatch) ?: return false
    val opaque = etag.removePrefix("W/")
    return header.split(',').map { it.trim() }.any { it == "*" || it.removePrefix("W/") == opaque }
}

/**
 * Responds with [text] plus its ETag and [cacheControl]; a request whose If-None-Match names that ETag gets
 * 304 Not Modified without a body.
 */
suspend fun ApplicationCall.respondCacheable(text: String, type: ContentType, cacheControl: String, etag: String = weakETag(text)) {
    response.header(HttpHeaders.CacheControl, cacheControl)
    response.header(HttpHeaders.ETag, etag)
    if (matchesIfNoneMatch(etag)) respond(HttpStatusCode.NotModified) else respondText(text, type.withCharset(Charsets.UTF_8))
}

/**
 * [respondCacheable] for a JSON [body]. The default lets clients keep a copy but revalidate it on every use (iOS'
 * URLSession does that by itself; OkHttp once it has a cache), so an unchanged result costs a 304 instead of a body.
 */
suspend inline fun <reified T> ApplicationCall.respondCacheableJson(body: T, cacheControl: String = "private, no-cache") =
    respondCacheable(ApiJson.encodeToString(serializer<T>(), body), ContentType.Application.Json, cacheControl)
