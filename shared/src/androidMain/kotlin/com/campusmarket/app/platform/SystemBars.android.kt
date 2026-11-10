package com.campusmarket.app.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun StatusBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, darkIcons) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previous = controller.isAppearanceLightStatusBars
            // "Light status bars" = a light background behind them, i.e. dark icons.
            controller.isAppearanceLightStatusBars = darkIcons
            onDispose { controller.isAppearanceLightStatusBars = previous }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
