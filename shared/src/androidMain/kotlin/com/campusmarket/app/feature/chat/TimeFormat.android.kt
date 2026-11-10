package com.campusmarket.app.feature.chat

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Settings → System → Date & time → "Use 24-hour format" (which defaults to the locale's convention). */
@Composable
internal actual fun deviceUses24HourClock(): Boolean {
    LocalConfiguration.current // re-read when the configuration (locale) changes
    return DateFormat.is24HourFormat(LocalContext.current)
}
