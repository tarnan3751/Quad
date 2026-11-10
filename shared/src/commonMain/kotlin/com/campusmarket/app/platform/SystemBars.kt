package com.campusmarket.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

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

/**
 * Properties for a dialog that covers the whole screen, status bar and home indicator areas included (the photo
 * viewer); its content pads itself with `statusBarsPadding()` / `navigationBarsPadding()`. On iOS a dialog otherwise
 * stops at the safe area and the dimmed screen shows above and below it.
 */
expect fun fullScreenDialogProperties(): DialogProperties
