package com.campusmarket.app.ui

import androidx.compose.runtime.Composable
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** Desktop harness: the default locale's short time pattern ("h:mm a" in the US, "HH:mm" in most of Europe). */
@Composable
internal actual fun deviceUses24HourClock(): Boolean {
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()) as? SimpleDateFormat)?.toPattern() ?: return false
    return !pattern.contains('a')
}
