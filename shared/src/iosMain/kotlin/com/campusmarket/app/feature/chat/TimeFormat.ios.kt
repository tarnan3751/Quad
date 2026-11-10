package com.campusmarket.app.feature.chat

import androidx.compose.runtime.Composable
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

/** The "j" skeleton resolves to the user's hour cycle, including Settings → General → Date & Time → 24-Hour Time. */
@Composable
internal actual fun deviceUses24HourClock(): Boolean {
    val pattern = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale) ?: return false
    return !pattern.contains('a')
}
