package com.campusmarket.app.platform

import com.campusmarket.app.di.OutboxSecrets
import com.campusmarket.app.di.initKoin
import com.campusmarket.app.data.SecretStorage
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import org.koin.dsl.module
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.experimental.ExperimentalNativeApi

actual fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) { config() }

class IosUrlLauncher : PlatformUrlLauncher {
    override fun open(url: String): Boolean {
        val nsUrl = NSURL.URLWithString(url) ?: return false
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
        return true
    }
}

/** Simulator → host machine. On a device, set the server in debug builds (Welcome → Server). */
const val IOS_DEFAULT_BASE_URL = "http://localhost:8080"

@OptIn(ExperimentalNativeApi::class)
fun initCampusMarketIos() {
    initKoin(
        environment = AppEnvironment(
            isDebug = Platform.isDebugBinary,
            defaultBaseUrl = IOS_DEFAULT_BASE_URL,
            platform = PlatformKind.IOS,
        ),
        platformModule = module {
            single<PlatformUrlLauncher> { IosUrlLauncher() }
            single<SecretStorage> { KeychainSecretStorage(service = "com.campusmarket.app.session", account = "session.v1") }
            single<SecretStorage>(OutboxSecrets) { KeychainSecretStorage(service = "com.campusmarket.app.session", account = "chatOutbox.v1") }
            single<NetworkMonitor> { IosNetworkMonitor() }
        },
    )
}
