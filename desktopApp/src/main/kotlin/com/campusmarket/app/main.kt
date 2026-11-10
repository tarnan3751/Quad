package com.campusmarket.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.initCampusMarketDesktop

/**
 * Desktop dev harness (not a product target). Desktop has no URL-scheme registration; to exercise deep links pass
 * one as the first argument, e.g. `./gradlew :desktopApp:run --args="campusmarket://orders/<id>"`.
 * Server: `CAMPUS_MARKET_API` env var, default http://localhost:8080.
 */
fun main(args: Array<String>) {
    initCampusMarketDesktop()
    args.firstOrNull()?.let { DeepLinks.handle(it) }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Quad",
            state = rememberWindowState(width = 430.dp, height = 900.dp),
        ) {
            App()
        }
    }
}
