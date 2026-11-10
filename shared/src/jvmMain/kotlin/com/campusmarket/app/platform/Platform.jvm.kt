package com.campusmarket.app.platform

import com.campusmarket.app.di.OutboxSecrets
import com.campusmarket.app.di.initKoin
import com.campusmarket.app.data.SecretStorage
import com.campusmarket.app.data.SettingsSecretStorage
import com.russhwolf.settings.Settings
import io.github.vinceglb.filekit.FileKit
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.dsl.module
import java.awt.Desktop
import java.net.URI

actual fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) { config() }

class DesktopUrlLauncher : PlatformUrlLauncher {
    override fun open(url: String): Boolean =
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                val os = System.getProperty("os.name").lowercase()
                val cmd = when {
                    "mac" in os -> arrayOf("open", url)
                    "win" in os -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                    else -> arrayOf("xdg-open", url)
                }
                Runtime.getRuntime().exec(cmd)
            }
            true
        } catch (e: Exception) {
            false
        }
}

/**
 * Desktop dev harness entry. Base URL: `CAMPUS_MARKET_API` env var, else `http://localhost:8080`.
 * Always a debug build (server override + HTTP logging enabled).
 */
fun initCampusMarketDesktop() {
    FileKit.init(appId = "CampusMarket")
    initKoin(
        environment = AppEnvironment(
            isDebug = true,
            defaultBaseUrl = System.getenv("CAMPUS_MARKET_API")?.takeIf { it.isNotBlank() } ?: "http://localhost:8080",
            platform = PlatformKind.DESKTOP,
        ),
        platformModule = module {
            single<PlatformUrlLauncher> { DesktopUrlLauncher() }
            // DEV HARNESS ONLY: tokens in plain java.util.prefs, NOT secure. Real clients are Android and iOS.
            single<SecretStorage> { SettingsSecretStorage(get<Settings>()) }
            single<SecretStorage>(OutboxSecrets) { SettingsSecretStorage(get<Settings>(), key = "chat.outbox.v1") }
        },
    )
}
