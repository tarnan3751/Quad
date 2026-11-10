package com.campusmarket.app.feature.listing

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.campusmarket.app.platform.StatusBarIcons
import com.campusmarket.app.platform.fullScreenDialogProperties
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Photo
import kotlinx.coroutines.launch

// Photos are viewed on black in both themes, so the controls on top are white (not theme roles).
private val OnPhoto = Color.White

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Full-screen photos: swipe between them, pinch or double-tap to zoom, drag to pan while zoomed. */
@Composable
internal fun PhotoViewer(photos: List<Photo>, initialPage: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = fullScreenDialogProperties()) {
        StatusBarIcons(darkIcons = false)
        val pagerState = rememberPagerState(initialPage = initialPage) { photos.size }
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !zoomed,
                key = { photos[it].id },
            ) { page ->
                ZoomablePhoto(
                    url = photos[page].url,
                    contentDescription = "Photo ${page + 1} of ${photos.size}",
                    isCurrent = page == pagerState.currentPage,
                    onZoomedChange = { if (page == pagerState.currentPage) zoomed = it },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    // Clear of the status bar, and of the notch / Dynamic Island in landscape.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close photos", tint = OnPhoto) }
                Spacer(Modifier.weight(1f))
                if (photos.size > 1) {
                    Text(
                        "${pagerState.currentPage + 1} / ${photos.size}",
                        color = OnPhoto,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(end = Spacing.lg),
                    )
                }
            }
            if (photos.size > 1) {
                PageDots(pagerState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Spacing.xl))
            }
        }
    }
}

/** Dots for a photo pager, on a translucent pill so they read on any photo. */
@Composable
internal fun PageDots(state: PagerState, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f), CircleShape)
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(state.pageCount) { index ->
            val selected = index == state.currentPage
            Box(Modifier.size(if (selected) 8.dp else 6.dp).background(OnPhoto.copy(alpha = if (selected) 1f else 0.55f), CircleShape))
        }
    }
}

@Composable
private fun ZoomablePhoto(url: String, contentDescription: String, isCurrent: Boolean, onZoomedChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    // Swiping away resets the zoom, so coming back shows the whole photo again.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale > 1.01f) { onZoomedChange(scale > 1.01f) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val from = scale
                        val fromOffset = offset
                        val to = if (from > 1.01f) 1f else DOUBLE_TAP_ZOOM
                        val center = Offset(size.width / 2f, size.height / 2f)
                        // Zoom in towards the tapped point; zoom out back to the centre.
                        val toOffset = if (to == 1f) Offset.Zero else clamp((center - tap) * (to - 1f), to)
                        scope.launch {
                            animate(0f, 1f) { t, _ ->
                                scale = from + (to - from) * t
                                offset = fromOffset + (toOffset - fromOffset) * t
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // One finger at 1x is left to the pager (swipe to the next photo); two fingers, or any drag while
                // zoomed, zoom and pan here.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val anchored = if (centroid == Offset.Unspecified) offset
                            else (centroid - center) - (centroid - center - offset) * (newScale / scale)
                            scale = newScale
                            offset = if (newScale <= 1.01f) Offset.Zero else clamp(anchored + pan, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) {
        ListingImage(
            url = url,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            backdrop = Color.Transparent,
            iconTint = OnPhoto.copy(alpha = 0.7f),
            iconSize = 56.dp,
            errorText = "This photo couldn't be loaded",
        )
    }
}
