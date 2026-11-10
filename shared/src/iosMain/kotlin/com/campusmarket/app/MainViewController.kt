package com.campusmarket.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.ComposeUIViewController
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.initCampusMarketIos
import platform.UIKit.UIViewController

/** Swift: `MainViewControllerKt.MainViewController()`. */
fun MainViewController(): UIViewController {
    initCampusMarketIos()
    return ComposeUIViewController { DismissKeyboardOnTapOutside { App() } }
}

/** Swift: `MainViewControllerKt.handleDeepLink(url: url.absoluteString)` from `.onOpenURL`. */
fun handleDeepLink(url: String): Boolean = DeepLinks.handle(url)

/**
 * The iOS convention: a tap outside the text fields puts the keyboard away. Number and decimal pads (price, code)
 * have no return key, so without this their keyboard could only be closed by leaving the screen. Taps that a
 * control handles (buttons, chips, fields) and drags don't count. Dialogs and sheets are separate layers and keep
 * their own behaviour.
 */
@Composable
private fun DismissKeyboardOnTapOutside(content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    Box(Modifier.fillMaxSize().pointerInput(focus) { detectTapGestures { focus.clearFocus() } }) {
        content()
    }
}
