package com.campusmarket.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The scrolling column of a form with text fields. It shrinks to the space above the on-screen keyboard and, while
 * the keyboard is up, scrolls so that the field being typed in **and** the form's submit button (the child marked
 * with [formSubmit]) are both on screen; when the two don't quite fit, the field goes to the top so as much as
 * possible of what follows shows. Use it instead of `Column(Modifier.imePadding().verticalScroll(…))`.
 *
 * Put padding and `consumeWindowInsets` for the bars around it in [modifier]; padding inside the scrolling area goes
 * on a child.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FormColumn(
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val form = remember(scroll) { FormScroll(scroll) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val margin = with(density) { SubmitMargin.roundToPx() }
    LaunchedEffect(form, density) {
        snapshotFlow { Triple(ime.getBottom(density) > 0, scroll.viewportSize, form.focused) }.collectLatest { (keyboardUp, _, _) ->
            if (!keyboardUp) return@collectLatest
            // The keyboard is still sliding in (the viewport changes every frame), or focus is moving to the next field.
            delay(SETTLE_MS)
            form.reveal(margin)
        }
    }
    CompositionLocalProvider(LocalFormScroll provides form) {
        Column(
            modifier
                .imePadding()
                .verticalScroll(scroll)
                .onGloballyPositioned { form.content = it }
                .onFocusedBoundsChanged { focused ->
                    val content = form.content?.takeIf { it.isAttached }
                    form.focused = if (focused != null && focused.isAttached && content != null) content.localBoundingBoxOf(focused, clipBounds = false) else null
                },
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/** Marks the submit button (or the block that ends with it) of the enclosing [FormColumn]. A no-op anywhere else. */
@Composable
fun Modifier.formSubmit(): Modifier {
    val form = LocalFormScroll.current ?: return this
    return onGloballyPositioned { form.submit = it }
}

private class FormScroll(val scroll: ScrollState) {
    /** The scrolled column: its top is at scroll offset 0. */
    var content: LayoutCoordinates? = null
    var submit: LayoutCoordinates? = null

    /** The focused field, in [content] coordinates. */
    var focused: Rect? by mutableStateOf(null)

    suspend fun reveal(margin: Int) {
        val content = content?.takeIf { it.isAttached } ?: return
        val field = focused ?: return
        val button = submit?.takeIf { it.isAttached }?.let { content.localBoundingBoxOf(it, clipBounds = false) } ?: return
        val viewport = scroll.viewportSize
        if (button.bottom <= field.top || field.height > viewport) return
        val highest = (field.top - margin).toInt().coerceAtLeast(0) // scrolling further would cut the field off at the top
        val lowest = (button.bottom + margin - viewport).toInt() // scrolling less leaves the button under the keyboard
        val target = when {
            // They don't both fit. On a long form the button is a screen or more away: leave the field where it is.
            lowest > highest -> if (button.top - field.bottom < viewport && scroll.value < highest) highest else return
            scroll.value < lowest -> lowest
            scroll.value > highest -> highest
            else -> return
        }
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
    }
}

private val LocalFormScroll = compositionLocalOf<FormScroll?> { null }
private val SubmitMargin = 12.dp
private const val SETTLE_MS = 120L
