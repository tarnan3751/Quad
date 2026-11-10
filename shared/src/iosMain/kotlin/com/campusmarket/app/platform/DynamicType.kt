package com.campusmarket.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityMedium
import platform.UIKit.UIContentSizeCategoryDidChangeNotification
import platform.UIKit.UIContentSizeCategoryExtraExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraSmall
import platform.UIKit.UIContentSizeCategoryLarge
import platform.UIKit.UIContentSizeCategoryMedium
import platform.UIKit.UIContentSizeCategorySmall

/**
 * Follows the system text size (Settings → Display & Brightness → Text Size, Control Center) while the app runs.
 * Compose reads it only when its view controller is created, so a change made with the app open, or in the
 * background, otherwise shows only after a relaunch. Uses Compose's own category → font scale steps, so the size at
 * launch is unchanged.
 */
@Composable
internal fun FollowDynamicType(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var category by remember { mutableStateOf(UIApplication.sharedApplication.preferredContentSizeCategory) }
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIContentSizeCategoryDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> category = UIApplication.sharedApplication.preferredContentSizeCategory }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    val scale = fontScaleFor(category) ?: density.fontScale
    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) { content() }
}

/** Compose Multiplatform's mapping (linear, unlike UIKit's per-style UIFontMetrics curves). Null for unknown. */
private fun fontScaleFor(category: String?): Float? = when (category) {
    UIContentSizeCategoryExtraSmall -> 0.8f
    UIContentSizeCategorySmall -> 0.85f
    UIContentSizeCategoryMedium -> 0.9f
    UIContentSizeCategoryLarge -> 1f
    UIContentSizeCategoryExtraLarge -> 1.1f
    UIContentSizeCategoryExtraExtraLarge -> 1.2f
    UIContentSizeCategoryExtraExtraExtraLarge -> 1.3f
    UIContentSizeCategoryAccessibilityMedium -> 1.4f
    UIContentSizeCategoryAccessibilityLarge -> 1.5f
    UIContentSizeCategoryAccessibilityExtraLarge -> 1.6f
    UIContentSizeCategoryAccessibilityExtraExtraLarge -> 1.7f
    UIContentSizeCategoryAccessibilityExtraExtraExtraLarge -> 1.8f
    else -> null
}
