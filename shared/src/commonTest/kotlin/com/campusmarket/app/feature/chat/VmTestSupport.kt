package com.campusmarket.app.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.BASE
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.NOW
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.testUser
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.ui.state.UserMessage
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ChatEvent
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull

/*
 * View-model test kit for the chat and orders features: an ApiClient on a MockEngine whose dispatcher is the test
 * scheduler (so `advanceUntilIdle()` / `advanceTimeBy()` drive HTTP deterministically), a SessionManager, recorded
 * external links, and a controllable [FakeLiveChat]. Tests set `Dispatchers.setMain(UnconfinedTestDispatcher(...))`.
 */

@OptIn(ExperimentalCoroutinesApi::class)
class VmHarness(
    scope: TestScope,
    user: UserPrivate = testUser,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()
    private val engine = MockEngine(
        MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { request ->
                requests += request
                handler(request)
            }
        },
    )
    val http = HttpClient(engine) { installCampusMarketDefaults(debugLogging = false) }
    val tokens = InMemoryTokenStore(StoredSession("access-1", "refresh-1", NOW + HOUR, user))
    val server = ServerConfig(AppEnvironment(isDebug = false, defaultBaseUrl = BASE, platform = PlatformKind.DESKTOP))
    val api = ApiClient(http, server, tokens, nowMillis = { NOW })
    val session = SessionManager(api, tokens, scope.backgroundScope)
    val userMessages = UserMessages()

    val opened = mutableListOf<String>()
    var launcherSucceeds = true
    val links = ExternalLinks(PlatformUrlLauncher { url -> opened += url; launcherSucceeds }, server)

    fun requests(method: HttpMethod, path: String) = requests.filter { it.method == method && it.url.encodedPath == path }

    /** Next snackbar text, or null if none is queued. */
    suspend fun nextMessage(): UserMessage? = withTimeoutOrNull(1) { userMessages.messages.first() }
}

suspend fun HttpRequestData.bodyText(): String = body.toByteArray().decodeToString()

class FakeLiveChat(initial: ChatSocket.Status = ChatSocket.Status.Connected) : LiveChat {
    private val flow = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
    override val events: Flow<ChatEvent> = flow
    override val status = MutableStateFlow(initial)
    var reconnectRequests = 0
    override fun reconnectNow() {
        reconnectRequests++
    }

    fun emit(event: ChatEvent) {
        check(flow.tryEmit(event))
    }
}

fun ViewModel.close() = viewModelScope.cancel()

// ---------------------------------------------------------------------------------------------------- fixtures

fun publicUser(id: String, name: String, blockedByMe: Boolean = false) =
    UserPublic(id = id, displayName = name, campusDomain = "uni.edu", joinedAt = NOW - 10 * HOUR, blockedByMe = blockedByMe)

fun listingSummary(id: String = "l1", sellerId: String = "u2", sellerName: String = "Maya Chen", status: ListingStatus = ListingStatus.ACTIVE) =
    ListingSummary(
        id = id,
        title = "Desk lamp",
        priceCents = 2500,
        category = ListingCategory.DORM,
        condition = ListingCondition.GOOD,
        status = status,
        sellerId = sellerId,
        sellerName = sellerName,
        createdAt = NOW - 5 * HOUR,
    )
