package com.campusmarket.app.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Settings → System → Date & time → "Use 24-hour format" (which defaults to the locale's convention). Re-read on
 * every resume: the switch lives in another app, so coming back from it is when it can have changed.
 */
@Composable
internal actual fun deviceUses24HourClock(): Boolean {
    val configuration = LocalConfiguration.current // re-read when the configuration (locale) changes
    val context = LocalContext.current
    var use24h by remember(configuration, context) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    LifecycleResumeEffect(context) {
        use24h = DateFormat.is24HourFormat(context)
        onPauseOrDispose { }
    }
    return use24h
}
