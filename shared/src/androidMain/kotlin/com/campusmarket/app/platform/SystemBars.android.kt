package com.campusmarket.app.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.SessionState
import org.koin.core.context.GlobalContext

@Composable
actual fun StatusBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, darkIcons) {
        // Inside a dialog (the photo viewer) it's the dialog's own window that sits over the bars.
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            // "Light status bars" = a light background behind them, i.e. dark icons.
            controller.isAppearanceLightStatusBars = darkIcons
            // Back to what the theme wants, not to what was set before: the activity may have started with this
            // screen's icons already (see startsOnBrandHero).
            onDispose { controller.isAppearanceLightStatusBars = !view.resources.configuration.isNight }
        }
    }
}

private val Configuration.isNight: Boolean
    get() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * True when the first screen will be the Welcome hero (nobody is signed in), whose amber sky needs dark status-bar
 * icons in both themes. The activity sets them before its first frame: left to the screen, they change a moment
 * after the splash has gone, and after dark that is a flash of white icons on amber.
 */
fun startsOnBrandHero(): Boolean =
    runCatching { GlobalContext.get().get<SessionManager>().state.value is SessionState.SignedOut }.getOrDefault(false)

actual fun edgeToEdgeDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
