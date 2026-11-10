package com.campusmarket.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.DialogProperties
import platform.Foundation.NSNumber
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.UIKit.UIStatusBarStyle
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent

/**
 * Uses the app-wide status bar style, which iOS honours because Info.plist sets
 * `UIViewControllerBasedStatusBarAppearance` to NO (the SwiftUI `WindowGroup` hosting the Compose view controller
 * never asks it for `preferredStatusBarStyle`). The default style still follows the system light/dark mode.
 *
 * `-[UIApplication setStatusBarStyle:]` is deprecated and hidden from Kotlin, but it is still what UIKit uses when
 * view-controller-based appearance is off (Flutter's `SystemChrome` relies on the same), so it's called through KVC.
 */
@Composable
actual fun StatusBarIcons(darkIcons: Boolean) {
    DisposableEffect(darkIcons) {
        val app = UIApplication.sharedApplication
        val previous = app.statusBarStyle
        app.setStatusBarStyleCompat(if (darkIcons) UIStatusBarStyleDarkContent else UIStatusBarStyleLightContent)
        onDispose { app.setStatusBarStyleCompat(previous) }
    }
}

private fun UIApplication.setStatusBarStyleCompat(style: UIStatusBarStyle) {
    setValue(NSNumber(long = style), forKey = "statusBarStyle")
}

actual fun edgeToEdgeDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false)
