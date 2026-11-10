package com.campusmarket.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.StoredSession
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.data.testUser
import com.campusmarket.app.data.toStoredSession
import com.campusmarket.app.di.coreModule
import com.campusmarket.app.di.featureModules
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.platform.PlatformUrlLauncher
import com.campusmarket.app.platform.createPlatformHttpClient
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.LoginRequest
import com.campusmarket.core.UserPrivate
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences

/*
 * The real App() in JVM tests: a scripted [FakeServer] or the [LiveServer], [startTestApp] (Koin with the real
 * modules), and finders / clickers that wait for their node. Navigation must run on the UI thread, so clicks go
 * through `runOnUiThread`. Stop the app with `stopKoin()`.
 */

/** A scripted server for app tests: answers with [handler] and records every request. */
class FakeServer(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())
    val http = HttpClient(MockEngine { request -> requests += request; handler(request) }) { installCampusMarketDefaults(debugLogging = false) }

    fun requestsTo(path: String, method: HttpMethod? = null): List<HttpRequestData> =
        synchronized(requests) { requests.filter { it.url.encodedPath == path && (method == null || it.method == method) } }
}

/**
 * A running dev server with the seeded demo data: [base] is `CAMPUS_MARKET_LIVE` (read-only use: sign in and look);
 * tests that change data take their server from `CAMPUS_MARKET_LIVE_API` instead. Logins are cached per JVM: the
 * server rate-limits them.
 */
object LiveServer {
    val base: String? = env("CAMPUS_MARKET_LIVE")?.trimEnd('/')
    private val sessions = ConcurrentHashMap<String, AuthResponse>()

    fun session(email: String, base: String = this.base!!): AuthResponse =
        sessions.getOrPut("$base $email") { runBlocking { api(base = base).login(LoginRequest(email, "password123")) } }

    fun tokens(email: String, base: String = this.base!!): TokenStore = InMemoryTokenStore(session(email, base).toStoredSession())

    /** A client signed in as [email] (or signed out). */
    fun api(email: String? = null, base: String = this.base!!) = ApiClient(
        createPlatformHttpClient { installCampusMarketDefaults(debugLogging = false) },
        ServerConfig(AppEnvironment(isDebug = true, defaultBaseUrl = base, platform = PlatformKind.DESKTOP)),
        email?.let { tokens(it, base) } ?: InMemoryTokenStore(),
    )
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

/** A session that stays valid for the test (the app refreshes tokens that are about to expire). */
fun signedIn(user: UserPrivate = testUser): TokenStore =
    InMemoryTokenStore(StoredSession("a1", "r1", System.currentTimeMillis() + HOUR, user))

/** Starts Koin with the real modules, talking to [server] (or, without one, to [baseUrl] over HTTP). */
fun startTestApp(
    server: FakeServer?,
    tokens: TokenStore = InMemoryTokenStore(),
    baseUrl: String = if (server != null) "http://test.local" else LiveServer.base!!,
    launcher: PlatformUrlLauncher = PlatformUrlLauncher { true },
    overrides: Module = module { },
) {
    val environment = AppEnvironment(isDebug = true, defaultBaseUrl = baseUrl, platform = PlatformKind.DESKTOP)
    startKoin {
        modules(
            listOf(
                module { single<PlatformUrlLauncher> { launcher } },
                coreModule(environment),
                module {
                    single<Settings> { PreferencesSettings(Preferences.userRoot().node("campusmarket-tests").also { it.clear() }) }
                    single<TokenStore> { tokens }
                    if (server != null) single<HttpClient> { server.http }
                },
            ) + featureModules + overrides,
        )
    }
}

// ---------------------------------------------------------------------------------------------------- driving

const val UI_TIMEOUT = 10_000L

/**
 * A node read out as a summary that starts with [prefix], e.g. an inbox row ("Maya Chen, Desk lamp. …") or a chat
 * bubble: those clear their children's texts, so `hasText` no longer finds them.
 */
fun hasContentDescriptionStartingWith(prefix: String): SemanticsMatcher =
    SemanticsMatcher("ContentDescription starts with '$prefix'") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

/** Shown text, or the summary a row or chat bubble is read as. */
fun shows(text: String): SemanticsMatcher = hasText(text, substring = true) or hasContentDescription(text, substring = true)

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitNode(matcher: SemanticsMatcher, timeoutMillis: Long = UI_TIMEOUT) =
    waitUntil(timeoutMillis = timeoutMillis) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitText(text: String, timeoutMillis: Long = UI_TIMEOUT) = awaitNode(shows(text), timeoutMillis)

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitGone(text: String) =
    waitUntil(timeoutMillis = UI_TIMEOUT) { onAllNodes(shows(text)).fetchSemanticsNodes().isEmpty() }

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.clickNode(matcher: SemanticsMatcher, last: Boolean = false) {
    awaitNode(matcher)
    val node = if (last) onAllNodes(matcher).onLast() else onAllNodes(matcher).onFirst()
    runCatching { node.performScrollTo() } // only inside a scrollable parent
    runOnUiThread { node.performClick() }
    waitForIdle()
}

/** Clicks the first (or [last]) clickable node showing [text]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.click(text: String, substring: Boolean = false, last: Boolean = false) =
    clickNode((hasText(text, substring = substring) or hasContentDescriptionStartingWith(text)) and hasClickAction(), last)

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.clickDescription(description: String, substring: Boolean = false) =
    clickNode(hasContentDescription(description, substring = substring) and hasClickAction())

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.back() = clickDescription("Back")

/** Types into the last text field labelled [label] (a dialog's field comes after the screen's). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.type(label: String, text: String) {
    onAllNodes(hasSetTextAction() and hasText(label)).onLast().performTextInput(text)
    waitForIdle()
}
