package com.campusmarket.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import coil3.compose.setSingletonImageLoaderFactory
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.navigation.AppNavigation
import com.campusmarket.app.ui.components.LocalUrlResolver
import com.campusmarket.app.ui.components.newImageLoader
import com.campusmarket.app.ui.theme.CampusMarketTheme
import org.koin.compose.koinInject

/**
 * Root composable shared by Android, iOS and desktop. Koin must already be started by the platform entry point
 * (`initCampusMarket(context)` / `initCampusMarketDesktop()` / `MainViewController()`).
 */
@Composable
fun App() {
    setSingletonImageLoaderFactory(::newImageLoader)
    val server: ServerConfig = koinInject()
    val session: SessionManager = koinInject()
    LaunchedEffect(session) { session.restore() }
    val resolver = remember(server) { { url: String? -> server.resolve(url) } }

    CampusMarketTheme {
        CompositionLocalProvider(LocalUrlResolver provides resolver) {
            Surface(Modifier.fillMaxSize()) {
                AppNavigation()
            }
        }
    }
}
