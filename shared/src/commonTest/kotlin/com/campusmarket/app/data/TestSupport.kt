package com.campusmarket.app.data

import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.ui.state.UserMessage
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ApiJson
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.UserPrivate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Client test kit: [Harness] (the real ApiClient on a scripted MockEngine), JSON responses, request bodies, fixed
 * time, and waits for view-model state. JVM app tests (jvmTest `TestApp.kt`) build on the same fixtures.
 */

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

fun session(access: String = "access-1", refresh: String = "refresh-1", expiresAt: Long = NOW + HOUR, user: UserPrivate = testUser) =
    StoredSession(access, refresh, expiresAt, user)

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

/** The text body the real client sent (JSON bodies are byte arrays; multipart uploads are not). */
val HttpRequestData.bodyText: String get() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

fun <T> HttpRequestData.decode(serializer: KSerializer<T>): T = ApiJson.decodeFromString(serializer, bodyText)

fun HttpRequestData.jsonBody(): JsonObject = Json.parseToJsonElement(bodyText).jsonObject

/**
 * An [ApiClient] wired to a [MockEngine] with the real client configuration, plus the other things view models take
 * (user messages, external links that record what was opened). With a [scope], HTTP runs on the test scheduler, so
 * `advanceUntilIdle()` / `advanceTimeBy()` drive it; without one it answers at once, on the calling thread, so a call
 * made from an eagerly dispatched coroutine (`withMainDispatcher`) has finished when the function that made it returns.
 * No test talks to the fake server from another thread: under load that made requests go missing from [requests] and
 * states get skipped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Harness(
    initial: StoredSession? = session(),
    var now: Long = NOW,
    private val scope: TestScope? = null,
    /**
     * Answer on Ktor's IO threads instead (only without a [scope]): multipart uploads need a real dispatcher to
     * stream their body. Wait for results with [awaitValue] / [awaitCondition], and only for states that can't
     * already hold.
     */
    threaded: Boolean = false,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val tokens = InMemoryTokenStore(initial)
    private val recording = Mutex()

    /** Every request so far, in order. A snapshot: the engine may be recording on another thread. */
    @kotlin.concurrent.Volatile
    var requests: List<HttpRequestData> = emptyList()
        private set
    val engine = MockEngine(
        MockEngineConfig().apply {
            if (scope != null) dispatcher = StandardTestDispatcher(scope.testScheduler) else if (!threaded) dispatcher = UnconfinedTestDispatcher()
            addHandler { request ->
                recording.withLock { requests = requests + request }
                handler(request)
            }
        },
    )
    val http = HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) }
    val server = ServerConfig(AppEnvironment(isDebug = false, defaultBaseUrl = BASE, platform = PlatformKind.DESKTOP))
    val api = ApiClient(http, server, tokens, nowMillis = { now })

    /** Needs the test [scope] (it runs in its background scope). */
    val session by lazy { SessionManager(api, tokens, checkNotNull(scope).backgroundScope) }
    val messages = UserMessages()

    val opened = mutableListOf<String>()
    var launcherSucceeds = true
    val links = ExternalLinks(PlatformUrlLauncher { url -> opened += url; launcherSucceeds }, server)

    fun requestsTo(path: String) = requests.filter { it.url.encodedPath == path }

    fun requests(method: HttpMethod, path: String) = requests.filter { it.method == method && it.url.encodedPath == path }

    /** Next snackbar text, or null if none is queued. */
    suspend fun nextMessage(): UserMessage? = withTimeoutOrNull(1) { messages.messages.first() }
}

/** `viewModelScope` needs a Main dispatcher on the JVM test target. */
@OptIn(ExperimentalCoroutinesApi::class)
inline fun withMainDispatcher(block: () -> Unit) {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
        block()
    } finally {
        Dispatchers.resetMain()
    }
}

/** Waits in real time (the MockEngine answers on another thread), up to [timeoutMs], for the first value matching [predicate]. */
suspend fun <T> Flow<T>.awaitValue(timeoutMs: Long = 30_000, predicate: (T) -> Boolean = { true }): T =
    withContext(Dispatchers.Default) { withTimeout(timeoutMs) { first(predicate) } }

/** Polls [condition] (real time) until it holds. For effects off the main dispatcher, e.g. calls made in the app scope. */
suspend fun awaitCondition(timeoutMs: Long = 30_000, condition: () -> Boolean) =
    withContext(Dispatchers.Default) {
        // runCatching: the condition may read a list the engine thread is appending to.
        withTimeout(timeoutMs) { while (!runCatching(condition).getOrDefault(false)) delay(10) }
    }
