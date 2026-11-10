package com.campusmarket.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/** The desktop harness has no status bar. */
@Composable
actual fun StatusBarIcons(darkIcons: Boolean) = Unit

actual fun edgeToEdgeDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
