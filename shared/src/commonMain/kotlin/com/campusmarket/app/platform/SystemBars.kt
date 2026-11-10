package com.campusmarket.app.platform

import androidx.compose.runtime.Composable

/**
 * While this is in the composition, the status-bar icons are dark ([darkIcons] = true) or light, whatever the app
 * theme; leaving the composition restores the default (icons follow the system light/dark mode). For screens that
 * draw their own colour under the status bar, like the Welcome hero's amber sky.
 *
 * Android: `WindowInsetsControllerCompat.isAppearanceLightStatusBars`. iOS: `UIApplication.statusBarStyle`
 * (needs `UIViewControllerBasedStatusBarAppearance = NO` in Info.plist, because the SwiftUI host doesn't ask the
 * Compose view controller). Desktop: nothing.
 */
@Composable
expect fun StatusBarIcons(darkIcons: Boolean)
