package com.campusmarket.app.di

import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.SecretStorage
import com.campusmarket.app.data.SecureTokenStore
import com.campusmarket.app.data.TokenStore
import com.campusmarket.app.data.installCampusMarketDefaults
import com.campusmarket.app.feature.auth.authModule
import com.campusmarket.app.feature.browse.browseModule
import com.campusmarket.app.feature.chat.chatModule
import com.campusmarket.app.feature.listing.listingModule
import com.campusmarket.app.feature.moderation.moderationModule
import com.campusmarket.app.feature.orders.ordersModule
import com.campusmarket.app.feature.profile.profileModule
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.platform.createPlatformHttpClient
import com.campusmarket.app.ui.state.UserMessages
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/** Qualifier for the process-lifetime [CoroutineScope] (`get(AppScope)`). */
val AppScope = named("appScope")

/** Core singletons shared by every feature. Owned by the client foundation; features add their own modules. */
fun coreModule(environment: AppEnvironment): Module = module {
    single { environment }
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<Settings> { Settings() }
    single { ServerConfig(environment, get()) }
    // SecretStorage comes from the platform module (Keychain / Keystore; plain settings on the desktop harness).
    single<TokenStore> {
        val secrets = get<SecretStorage>()
        SecureTokenStore(secrets, legacy = if (secrets.isSecure) get<Settings>() else null)
    }
    single<HttpClient> { createPlatformHttpClient { installCampusMarketDefaults(debugLogging = environment.isDebug) } }
    single { ApiClient(get(), get(), get()) }
    single { SessionManager(get(), get(), get(AppScope)) }
    single { ChatSocket(get(), get(), get(), get(), get(AppScope)) }
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
    startKoin {
        modules(listOf(platformModule, coreModule(environment)) + featureModules)
    }
}
