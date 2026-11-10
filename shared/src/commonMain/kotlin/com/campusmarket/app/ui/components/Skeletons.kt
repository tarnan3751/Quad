package com.campusmarket.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.theme.Spacing

/*
 * Skeletons: grey stand-ins in the shape of the content that's loading, with a soft light sweeping across. Use them
 * instead of a bare spinner whenever the layout is known (a grid of listings, a list of rows, a profile header).
 * Everything inside one [ShimmerGroup] (each skeleton below opens its own) sweeps in step.
 */

private val LocalShimmer = compositionLocalOf<State<Float>?> { null }

/** Shares one shimmer animation between all [SkeletonBox]es inside, so they sweep together. */
@Composable
fun ShimmerGroup(content: @Composable () -> Unit) {
    if (LocalShimmer.current != null) return content()
    val progress = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    CompositionLocalProvider(LocalShimmer provides progress, content = content)
}

/** A shimmering placeholder block. Size it with [modifier]; [shape] defaults to the thumbnail radius. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    ShimmerGroup {
        val progress = LocalShimmer.current!!
        val base = MaterialTheme.colorScheme.surfaceContainerHighest
        val highlight = lerp(base, MaterialTheme.colorScheme.surfaceContainerLowest, 0.55f)
        Box(
            modifier
                .clip(shape)
                .drawBehind {
                    drawRect(base)
                    // A band three widths wide travelling from off the left edge to off the right edge.
                    val travel = size.width * 3f
                    val x = -size.width + travel * progress.value
                    drawRect(
                        Brush.linearGradient(
                            0f to base, 0.5f to highlight, 1f to base,
                            start = Offset(x - size.width, 0f),
                            end = Offset(x, size.height),
                        ),
                    )
                },
        )
    }
}

/** A line of text-height skeleton ([widthFraction] of the available width). */
@Composable
fun SkeletonLine(modifier: Modifier = Modifier, widthFraction: Float = 1f, height: Dp = 12.dp) {
    SkeletonBox(modifier.fillMaxWidth(widthFraction).height(height), shape = CircleShape)
}

/** Placeholder in the shape of a `ListingCard` (square photo, price, two title lines). */
@Composable
fun ListingCardSkeleton(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column {
            SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1f), shape = RectangleShape)
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SkeletonLine(widthFraction = 0.35f, height = 16.dp)
                SkeletonLine(widthFraction = 0.9f)
                SkeletonLine(widthFraction = 0.5f, height = 10.dp)
            }
        }
    }
}

/** First-load placeholder for a listing grid: the same columns and spacing as `PagedLazyGrid`. */
@Composable
fun ListingGridSkeleton(
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Adaptive(minSize = 160.dp),
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    count: Int = 8,
) {
    ShimmerGroup {
        LazyVerticalGrid(
            columns = columns,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            userScrollEnabled = false,
            modifier = modifier.fillMaxSize().loadingSemantics(),
        ) {
            items(count) { ListingCardSkeleton() }
        }
    }
}

/** Leading shape of a [ListRowSkeleton]. */
enum class SkeletonLeading { None, Avatar, Thumbnail }

/** Placeholder for one list row: avatar or thumbnail, a title line and a supporting line. */
@Composable
fun ListRowSkeleton(modifier: Modifier = Modifier, leading: SkeletonLeading = SkeletonLeading.Thumbnail) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (leading) {
            SkeletonLeading.Avatar -> SkeletonBox(Modifier.size(44.dp), shape = CircleShape)
            SkeletonLeading.Thumbnail -> SkeletonBox(Modifier.size(56.dp))
            SkeletonLeading.None -> Unit
        }
        if (leading != SkeletonLeading.None) Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkeletonLine(widthFraction = 0.55f, height = 14.dp)
            SkeletonLine(widthFraction = 0.85f)
        }
    }
}

/** First-load placeholder for a list: [rows] of [ListRowSkeleton]. */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 8,
    leading: SkeletonLeading = SkeletonLeading.Thumbnail,
    contentPadding: PaddingValues = PaddingValues(vertical = Spacing.sm),
) {
    ShimmerGroup {
        LazyColumn(modifier.fillMaxSize().loadingSemantics(), contentPadding = contentPadding, userScrollEnabled = false) {
            items(rows) { ListRowSkeleton(leading = leading) }
        }
    }
}

/** [rows] of [ListRowSkeleton] in a plain column, for a list inside a sheet or a scrolling screen. */
@Composable
fun ListRowSkeletons(rows: Int, modifier: Modifier = Modifier, leading: SkeletonLeading = SkeletonLeading.Thumbnail) {
    ShimmerGroup {
        Column(modifier.fillMaxWidth().loadingSemantics()) { repeat(rows) { ListRowSkeleton(leading = leading) } }
    }
}

/** Screen readers hear "Loading" once instead of a pile of empty boxes. */
private fun Modifier.loadingSemantics(): Modifier = clearAndSetSemantics { contentDescription = "Loading" }
