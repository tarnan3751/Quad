package com.campusmarket.app

import androidx.compose.ui.window.ComposeUIViewController
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.initCampusMarketIos
import platform.UIKit.UIViewController

/** Swift: `MainViewControllerKt.MainViewController()`. */
fun MainViewController(): UIViewController {
    initCampusMarketIos()
    return ComposeUIViewController { App() }
}

/** Swift: `MainViewControllerKt.handleDeepLink(url: url.absoluteString)` from `.onOpenURL`. */
fun handleDeepLink(url: String): Boolean = DeepLinks.handle(url)
