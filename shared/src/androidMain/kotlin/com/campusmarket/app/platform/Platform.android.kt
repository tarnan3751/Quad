package com.campusmarket.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.campusmarket.app.data.SecretStorage
import com.campusmarket.app.di.initKoin
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.dsl.module

actual fun createPlatformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) { config() }

class AndroidUrlLauncher(private val context: Context) : PlatformUrlLauncher {
    override fun open(url: String): Boolean =
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
}

/** Android emulator → host machine. Override at runtime in debug builds (Welcome → Server). */
const val ANDROID_DEFAULT_BASE_URL = "http://10.0.2.2:8080"

/** Call once from `Application.onCreate`. Idempotent. */
fun initCampusMarket(context: Context) {
    val app = context.applicationContext
    val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    initKoin(
        environment = AppEnvironment(isDebug = debuggable, defaultBaseUrl = ANDROID_DEFAULT_BASE_URL, platform = PlatformKind.ANDROID),
        platformModule = module {
            single<PlatformUrlLauncher> { AndroidUrlLauncher(app) }
            single<SecretStorage> { KeystoreSecretStorage(app) }
        },
    )
}
