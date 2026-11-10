package com.campusmarket.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.feature.browse.browseGraph
import com.campusmarket.app.feature.chat.chatGraph
import com.campusmarket.app.feature.listing.listingGraph
import com.campusmarket.app.feature.moderation.moderationGraph
import com.campusmarket.app.feature.orders.ordersGraph
import com.campusmarket.app.feature.profile.profileGraph
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.components.LocalUrlResolver
import com.campusmarket.app.ui.theme.CampusMarketTheme
import com.campusmarket.core.UserPrivate
import org.koin.compose.koinInject
import org.koin.core.context.stopKoin
import java.io.File
import javax.imageio.ImageIO

/**
 * Base of the opt-in screenshot galleries: phone-sized (412 × 892 dp at 2x) renders in light or dark, saved as PNGs.
 * Environment: `CAMPUS_MARKET_SCREENSHOTS` (output folder; galleries skip without it), `CAMPUS_MARKET_LIVE` (dev server
 * for the live galleries, which only sign in and navigate), `CAMPUS_MARKET_FONT_SCALE` (e.g. 1.3) and
 * `CAMPUS_MARKET_SHOT_PHOTOS` (a folder of jpgs some fake-server galleries use as photos).
 * [settleMillis] is how long each [shot] waits for images and animations first.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
abstract class ScreenshotGallery(private val settleMillis: Long = 2_000) {
    protected val dir: File? = env("CAMPUS_MARKET_SCREENSHOTS")?.let(::File)
    protected val fontScale: Float = env("CAMPUS_MARKET_FONT_SCALE")?.toFloatOrNull() ?: 1f
    private val photos: File? = env("CAMPUS_MARKET_SHOT_PHOTOS")?.let(::File)

    /** File-name prefix for the following shots, e.g. "dark-". */
    protected var prefix = ""

    /** URL of `<name>` in `CAMPUS_MARKET_SHOT_PHOTOS`, or null (the app then shows its placeholder). */
    protected fun photoUrl(name: String): String? = photos?.let { File(it, name) }?.takeIf { it.exists() }?.toURI()?.toString()

    /** False, with a note in the log, unless the screenshot folder (and for [live] galleries the dev server) is set. */
    protected fun enabled(live: Boolean = false): Boolean {
        val missing = listOfNotNull("CAMPUS_MARKET_SCREENSHOTS".takeIf { dir == null }, "CAMPUS_MARKET_LIVE".takeIf { live && LiveServer.base == null })
        if (missing.isNotEmpty()) println("${this::class.simpleName} skipped: set ${missing.joinToString(" and ")}")
        return missing.isEmpty()
    }

    /** Lets the screen settle, then saves it (the whole scene, including dialogs and sheets). */
    protected fun SkikoComposeUiTest.shot(name: String) {
        repeat((settleMillis / 100).toInt()) {
            Thread.sleep(100)
            runCatching { waitForIdle() }
        }
        save(name)
    }

    /** Saves the screen as it is now. */
    protected fun SkikoComposeUiTest.save(name: String) {
        dir!!.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$prefix$name.png"))
    }

    /** The NavController of [Destinations]. */
    protected lateinit var nav: NavHostController

    /** Every signed-in destination without the tab bar, starting at [start], so a gallery can open any route with [go]. */
    @Composable
    protected fun Destinations(user: UserPrivate, start: Any) {
        val server: ServerConfig = koinInject()
        val resolver = remember(server) { { url: String? -> server.resolve(url) } }
        CampusMarketTheme {
            CompositionLocalProvider(LocalUrlResolver provides resolver, LocalCurrentUser provides user) {
                Surface(Modifier.fillMaxSize()) {
                    val controller = rememberNavController()
                    nav = controller
                    NavHost(controller, startDestination = start) {
                        browseGraph(controller)
                        listingGraph(controller)
                        chatGraph(controller)
                        ordersGraph(controller)
                        profileGraph(controller)
                        moderationGraph(controller)
                    }
                }
            }
        }
    }

    protected fun ComposeUiTest.go(route: Any) {
        runOnUiThread { nav.navigate(route) }
        waitForIdle()
    }

    protected fun ComposeUiTest.pop() {
        runOnUiThread { nav.popBackStack() }
        waitForIdle()
    }

    protected fun ComposeUiTest.scrollDown(times: Int = 1) = repeat(times) {
        onRoot().performTouchInput { swipeUp() }
        waitForIdle()
    }

    /**
     * Renders [content] (the app started with [startTestApp]) and runs [block]; on failure it saves `<prefix>failure`.
     * Stops Koin afterwards.
     */
    protected fun screens(
        dark: Boolean,
        fontScale: Float = this.fontScale,
        landscape: Boolean = false,
        height: Float = 892f,
        content: @Composable () -> Unit = { App() },
        block: SkikoComposeUiTest.() -> Unit,
    ) {
        try {
            val size = if (landscape) Size(892f * 2, 412f * 2) else Size(412f * 2, height * 2)
            runSkikoComposeUiTest(size = size, density = Density(2f, fontScale)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) { content() }
                }
                try {
                    block()
                } catch (e: Throwable) {
                    runCatching { save("failure") }
                    throw e
                }
            }
        } finally {
            runCatching { stopKoin() }
        }
    }
}
