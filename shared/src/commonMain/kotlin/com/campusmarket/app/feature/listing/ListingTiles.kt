package com.campusmarket.app.feature.listing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.campusmarket.app.ui.components.ErrorState
import com.campusmarket.app.ui.components.ListingStatusChip
import com.campusmarket.app.ui.components.LocalUrlResolver
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.rememberTimeFormat
import com.campusmarket.app.ui.state.PagedState
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * Storefront building blocks shared by browse, favorites and the listing screens: the grid tile, its skeleton, the
 * photo with loading / missing / failed states, the favorite heart and a paged grid that keeps its header on screen
 * through loading, error and empty states.
 */

// ---------------------------------------------------------------------------------------------------- text

/** One-line explanation of a condition, shown when picking it and on the listing. */
internal val ListingCondition.hint: String
    get() = when (this) {
        ListingCondition.NEW -> "Never used, may still be in its packaging."
        ListingCondition.LIKE_NEW -> "Used once or twice. No visible wear."
        ListingCondition.GOOD -> "Some signs of use. Works perfectly."
        ListingCondition.FAIR -> "Clear wear or small flaws, but still works."
        ListingCondition.POOR -> "Doesn't fully work. For parts or repair."
    }

// ---------------------------------------------------------------------------------------------------- photo

private enum class PhotoLoad { Missing, Loading, Loaded, Failed }

/**
 * A listing photo: a soft pulse while it loads, a photo outline when there is none, a crossed-out photo (and
 * [errorText], if given) when it can't be loaded. Size it with [modifier]; [backdrop] shows around fitted photos.
 */
@Composable
internal fun ListingImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    backdrop: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    iconSize: Dp = 32.dp,
    errorText: String? = null,
) {
    val resolved = LocalUrlResolver.current(url)
    var load by remember(resolved) { mutableStateOf(if (resolved == null) PhotoLoad.Missing else PhotoLoad.Loading) }
    Box(modifier.background(backdrop), contentAlignment = Alignment.Center) {
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    load = when (state) {
                        is AsyncImagePainter.State.Success -> PhotoLoad.Loaded
                        is AsyncImagePainter.State.Error -> PhotoLoad.Failed
                        else -> PhotoLoad.Loading
                    }
                },
            )
        }
        when (load) {
            PhotoLoad.Loading -> Pulse(Modifier.matchParentSize(), backdrop)
            PhotoLoad.Missing -> Icon(ListingIcons.Photo, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
            PhotoLoad.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(ListingIcons.BrokenPhoto, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
                if (errorText != null) Text(errorText, style = MaterialTheme.typography.labelMedium, color = iconTint, textAlign = TextAlign.Center)
            }
            PhotoLoad.Loaded -> Unit
        }
    }
}

/** A gently breathing placeholder block (image loading, skeletons). */
@Composable
internal fun Pulse(modifier: Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
    )
    Box(modifier.graphicsLayer { this.alpha = alpha }.background(color))
}

// ---------------------------------------------------------------------------------------------------- favorite

/**
 * Heart toggle on a 48 dp touch target. The change shows at once (the call runs optimistically in [ListingSync]);
 * the heart pops when it fills.
 */
@Composable
internal fun FavoriteButton(
    favorited: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onPhoto: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(favorited) }
    LaunchedEffect(favorited) {
        if (favorited && !previous) {
            scale.snapTo(0.55f)
            scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
        }
        previous = favorited
    }
    IconToggleButton(
        checked = favorited,
        onCheckedChange = {
            haptics.performHapticFeedback(if (favorited) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onToggle()
        },
        modifier = modifier.size(48.dp),
    ) {
        val icon: @Composable () -> Unit = {
            Icon(
                if (favorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (favorited) "Remove from favorites" else "Add to favorites",
                tint = if (favorited) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(if (onPhoto) 19.dp else 24.dp).graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
        }
        if (onPhoto) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
                shadowElevation = 1.dp,
                modifier = Modifier.size(34.dp),
            ) { Box(contentAlignment = Alignment.Center) { icon() } }
        } else {
            icon()
        }
    }
}

// ---------------------------------------------------------------------------------------------------- tile

/**
 * Grid tile: square photo (cropped), price, title clamped to two lines with the space for two always reserved, and
 * "condition · age", so every tile in a row is the same height. Non-ACTIVE listings get a status badge and a dimmed
 * photo; [onToggleFavorite] adds the heart.
 */
@Composable
internal fun ListingTile(
    listing: ListingSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box {
            ListingImage(listing.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            if (listing.status != ListingStatus.ACTIVE) {
                if (listing.status != ListingStatus.RESERVED) {
                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)))
                }
                ListingStatusChip(listing.status, Modifier.align(Alignment.TopStart).padding(Spacing.sm))
            }
            if (onToggleFavorite != null) {
                FavoriteButton(listing.favorited, onToggleFavorite, Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
        }
        Column(
            Modifier.padding(start = Spacing.md, end = Spacing.md, top = 10.dp, bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PriceText(listing.priceCents, currency = listing.currency)
            Text(
                listing.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${listing.condition.label} · ${rememberTimeFormat().ago(listing.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Placeholder with the tile's exact shape while the first page loads. */
@Composable
internal fun ListingTileSkeleton(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Pulse(Modifier.fillMaxWidth().aspectRatio(1f), block)
        Column(
            Modifier.padding(start = Spacing.md, end = Spacing.md, top = 12.dp, bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Pulse(Modifier.width(56.dp).height(16.dp).clip(RoundedCornerShape(4.dp)), block)
            Pulse(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)), block)
            Pulse(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(4.dp)), block)
            Pulse(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(4.dp)), block)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- grid

private const val LOAD_MORE_THRESHOLD = 6

/** Test tag of the [PagedListingGrid]'s lazy grid. */
const val LISTING_GRID_TAG = "listingGrid"

/**
 * Paged listing grid with pull-to-refresh and infinite scroll. Unlike the generic `PagedLazyGrid`, [header] (search
 * context, categories, sort) stays visible through the first load (skeleton tiles), a failed first load and the
 * [empty] state, and the footer says when the end is reached.
 */
@Composable
internal fun PagedListingGrid(
    paginator: Paginator<ListingSummary>,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = Spacing.lg),
    endOfList: String? = "You've seen everything for now",
    header: LazyGridScope.() -> Unit = {},
    empty: @Composable () -> Unit,
    tile: @Composable LazyGridItemScope.(ListingSummary) -> Unit,
) {
    val paged by paginator.state.collectAsStateWithLifecycle()
    LaunchedEffect(paginator) {
        val s = paginator.state.value
        if (!s.loaded && !s.isLoading && s.error == null) paginator.refresh()
    }
    LaunchedEffect(state, paginator) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) paginator.loadMore() }
    }
    // A first page that doesn't fill the screen leaves us "near the end" without any scrolling.
    LaunchedEffect(paged.items.size, paged.endReached) {
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (paged.loaded && !paged.endReached && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD) paginator.loadMore()
    }

    PullToRefreshBox(isRefreshing = paged.isRefreshing, onRefresh = paginator::refresh, modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            modifier = Modifier.fillMaxSize().testTag(LISTING_GRID_TAG),
        ) {
            header()
            val error = paged.error
            when {
                !paged.loaded && error != null -> item(key = "__error", span = { GridItemSpan(maxLineSpan) }) {
                    ErrorState(error, Modifier.fillMaxWidth().padding(top = Spacing.xl), onRetry = paginator::retry)
                }
                !paged.loaded -> items(6, key = { "__skeleton$it" }, contentType = { "skeleton" }) { ListingTileSkeleton() }
                paged.isEmpty -> item(key = "__empty", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = Spacing.xl)) { empty() }
                }
                else -> items(paged.items, key = { it.id }, contentType = { "listing" }) { tile(it) }
            }
            if (paged.loaded && !paged.isEmpty) {
                item(key = "__footer", span = { GridItemSpan(maxLineSpan) }) { GridFooter(paged, paginator::retry, endOfList) }
            }
        }
    }
}

@Composable
private fun GridFooter(paged: PagedState<ListingSummary>, onRetry: () -> Unit, endOfList: String?) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Spacing.sm), contentAlignment = Alignment.Center) {
        when {
            paged.isLoadingMore -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            paged.loadMoreError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load more.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            paged.endReached && endOfList != null && paged.items.size > 4 -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.padding(horizontal = Spacing.xl),
            ) {
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(endOfList, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Small round icon badge used by facts and notices. */
@Composable
internal fun IconBadge(icon: ImageVector, container: Color, content: Color, size: Dp = 36.dp) {
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.55f)) }
    }
}
