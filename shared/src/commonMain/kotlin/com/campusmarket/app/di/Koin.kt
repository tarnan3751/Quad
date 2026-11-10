package com.campusmarket.app.di

import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.SignOutListener
import com.campusmarket.app.data.SecretStorage
import com.campusmarket.app.data.SecureTokenStore
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.feature.auth.authModule
import com.campusmarket.app.feature.browse.browseModule
import com.campusmarket.app.feature.chat.ChatOutbox
import com.campusmarket.app.feature.chat.chatModule
import com.campusmarket.app.feature.listing.listingModule
import com.campusmarket.app.feature.moderation.moderationModule
import com.campusmarket.app.feature.orders.ordersModule
import com.campusmarket.app.feature.profile.profileModule
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.platform.NetworkMonitor
import com.campusmarket.app.platform.createPlatformHttpClient
import com.campusmarket.app.ui.state.UserMessages
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/** Qualifier for the process-lifetime [CoroutineScope] (`get(AppScope)`). */
val AppScope = named("appScope")

/**
 * Qualifier for the platform [SecretStorage] that holds the chat outbox (unsent messages and drafts): the same
 * protection as the session, in its own slot.
 */
val OutboxSecrets = named("chatOutboxSecrets")

/** Core singletons shared by every feature. Owned by the client foundation; features add their own modules. */
fun coreModule(environment: AppEnvironment): Module = module {
    single { environment }
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<Settings> { Settings() }
    single { ServerConfig(environment, get()) }
    // SecretStorage comes from the platform module (Keychain / Keystore; plain settings on the desktop harness).
    single<TokenStore> {
        val secrets = get<SecretStorage>()
        SecureTokenStore(
            secrets,
            legacy = if (secrets.isSecure) get<Settings>() else null,
            // Otherwise silent: e.g. an unsigned iOS simulator build has no Keychain access, so sessions don't persist.
            onError = { what, e -> if (environment.isDebug) println("Quad token store: $what: ${e.message}") },
        )
    }
    single<HttpClient> { createPlatformHttpClient { installCampusMarketDefaults(debugLogging = environment.isDebug) } }
    single { ApiClient(get(), get(), get()) }
    single { SessionManager(get(), get(), get(AppScope), signOutListeners = { getKoin().getAll<SignOutListener>() }) }
    single { ChatSocket(get(), get(), get(), get(), get(AppScope), getOrNull<NetworkMonitor>()?.available() ?: emptyFlow()) }
    single { UserMessages() }
    single { ExternalLinks(get(), get()) }
}

/** One module per feature package; each owner edits only their own module file. */
val featureModules: List<Module> = listOf(
    authModule,
    browseModule,
    listingModule,
    chatModule,
    ordersModule,
    profileModule,
    moderationModule,
)

/** Starts Koin once per process. Called by the platform entry points (`initCampusMarket*`). */
fun initKoin(environment: AppEnvironment, platformModule: Module) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    val koin = startKoin {
        modules(listOf(platformModule, coreModule(environment)) + featureModules)
    }.koin
    // The first frame needs the session (a Keystore / Keychain read) and the HTTP client behind it. Build them off the
    // main thread while the platform creates the window; the UI then finds them ready (Koin singletons are created
    // once, under a lock). A failure here is ignored: the UI's own lookup creates them and reports it.
    koin.get<CoroutineScope>(AppScope).launch {
        runCatching {
            koin.get<SessionManager>()
            koin.get<ChatSocket>()
            koin.get<ChatOutbox>() // decrypts the stored outbox
        }
    }
}
