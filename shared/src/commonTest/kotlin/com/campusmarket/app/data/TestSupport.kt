package com.campusmarket.app.data

import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.core.ApiJson
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.UserPrivate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.KSerializer

const val BASE = "http://test.local"
const val NOW = 1_700_000_000_000L
const val HOUR = 3_600_000L

val testUser = UserPrivate(
    id = "u1",
    email = "ada@uni.edu",
    displayName = "Ada",
    campusDomain = "uni.edu",
    joinedAt = NOW - 1000,
)

fun session(access: String = "access-1", refresh: String = "refresh-1", expiresAt: Long = NOW + HOUR) =
    StoredSession(access, refresh, expiresAt, testUser)

fun authResponse(access: String, refresh: String, expiresAt: Long = NOW + HOUR, user: UserPrivate = testUser) =
    AuthResponse(access, refresh, expiresAt, user)

fun <T> MockRequestHandleScope.respondJson(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(
    content = ApiJson.encodeToString(serializer, value),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

fun MockRequestHandleScope.respondError(status: HttpStatusCode, code: String, message: String = code, fields: Map<String, String> = emptyMap()) =
    respondJson(ErrorResponse.serializer(), ErrorResponse(code, message, fields), status)

val HttpRequestData.bearer: String? get() = headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

/** An [ApiClient] wired to a [MockEngine] with the real client configuration. */
class Harness(
    initial: StoredSession? = session(),
    var now: Long = NOW,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val tokens = InMemoryTokenStore(initial)
    val requests = mutableListOf<HttpRequestData>()
    val engine = MockEngine { request ->
        requests += request
        handler(request)
    }
    val http = HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) }
    val server = ServerConfig(AppEnvironment(isDebug = false, defaultBaseUrl = BASE, platform = PlatformKind.DESKTOP))
    val api = ApiClient(http, server, tokens, nowMillis = { now })

    fun requestsTo(path: String) = requests.filter { it.url.encodedPath == path }
}
