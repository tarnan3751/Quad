package com.campusmarket.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private var sheetsWithFields by mutableIntStateOf(0)

/**
 * Call from a bottom sheet that has text fields. On iOS a sheet (and its scrim) ends at the top of the keyboard, and
 * the keyboard is translucent, so the screen behind the sheet would show through the keys; while such a sheet is
 * open, [SheetKeyboardBackdrop] paints the sheet's colour there instead.
 */
@Composable
fun SheetKeyboardBackdropEffect() {
    DisposableEffect(Unit) {
        sheetsWithFields++
        onDispose { sheetsWithFields-- }
    }
}

/** Host for [SheetKeyboardBackdropEffect]: the last child of the app's root box. */
@Composable
fun BoxScope.SheetKeyboardBackdrop() {
    if (sheetsWithFields == 0) return
    val density = LocalDensity.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    if (keyboard > 0.dp) {
        // A little taller than the keyboard: the sheet stops a few points above its rounded top edge.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboard + 16.dp).background(MaterialTheme.colorScheme.surfaceContainerLow))
    }
}
