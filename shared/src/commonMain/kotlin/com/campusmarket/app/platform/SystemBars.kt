package com.campusmarket.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * While this is in the composition, the status-bar icons are dark ([darkIcons] = true) or light, whatever the app
 * theme; leaving the composition restores the default (icons follow the system light/dark mode). For screens that
 * draw their own colour under the status bar, like the Welcome hero's amber sky. Inside a full-screen dialog (see
 * [edgeToEdgeDialogProperties]) it sets the dialog window's icons on Android.
 *
 * Android: `WindowInsetsControllerCompat.isAppearanceLightStatusBars`. iOS: `UIApplication.statusBarStyle`
 * (needs `UIViewControllerBasedStatusBarAppearance = NO` in Info.plist, because the SwiftUI host doesn't ask the
 * Compose view controller). Desktop: nothing.
 */
@Composable
expect fun StatusBarIcons(darkIcons: Boolean)

/**
 * A full-screen dialog (the photo viewer) that draws behind the status and navigation bars, so its black backdrop
 * reaches the screen edges instead of leaving the dimmed app showing in the bars. Pad controls with the system-bar
 * insets. Android: `decorFitsSystemWindows = false`. iOS: `usePlatformInsets = false` (a dialog otherwise stops at
 * the safe area).
 */
expect fun edgeToEdgeDialogProperties(): DialogProperties
