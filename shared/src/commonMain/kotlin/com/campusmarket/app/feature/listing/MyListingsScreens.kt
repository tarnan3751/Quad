package com.campusmarket.app.feature.listing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.navigation.TopLevelTab
import com.campusmarket.app.navigation.navigateToTab
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.FilterChip
import com.campusmarket.app.ui.components.ListingStatusChip
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.listImageUrl
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import org.koin.compose.viewmodel.koinViewModel

/** `api.myFavorites` in a grid; un-favoriting removes the tile (Undo in the snackbar). */
@Composable
fun FavoritesScreen(nav: NavController) {
    val vm = koinViewModel<FavoritesViewModel>()
    Scaffold(topBar = { AppTopBar("Favorites", onBack = { nav.popBackStack() }) }) { padding ->
        PagedListingGrid(
            paginator = vm.favorites,
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = Spacing.lg),
            endOfList = null,
            empty = {
                EmptyState(
                    "No favorites yet",
                    modifier = Modifier.fillMaxWidth(),
                    message = "Tap the heart on anything you like and it'll wait for you here.",
                    icon = Icons.Filled.FavoriteBorder,
                    actionLabel = "Browse listings",
                    onAction = { nav.navigateToTab(TopLevelTab.Browse) },
                )
            },
        ) { listing ->
            ListingTile(
                listing = listing,
                onClick = { nav.navigate(ListingDetailRoute(listing.id)) },
                onToggleFavorite = { vm.unfavorite(listing) },
            )
        }
    }
}

/** `api.myListings(status)` with a status filter; tap → detail (edit / delete from there). */
@Composable
fun MyListingsScreen(nav: NavController) {
    val vm = koinViewModel<MyListingsViewModel>()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val paged by vm.listings.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }
    Scaffold(
        topBar = {
            AppTopBar(
                "My listings",
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { nav.navigateToTab(TopLevelTab.Sell) }) {
                        Icon(Icons.Filled.Add, contentDescription = "New listing")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
            ) {
                items(MyListingsFilters, key = { it?.name ?: "ALL" }) { status ->
                    FilterChip(
                        selected = status == filter,
                        onClick = { vm.setFilter(status) },
                        label = { Text(status?.label ?: "All") },
                    )
                }
            }
            if (!paged.loaded && paged.error == null) {
                RowSkeletons()
            } else {
                // Fresh scroll position per filter.
                val listState = rememberSaveable(filter, saver = LazyListState.Saver) { LazyListState() }
                PagedLazyColumn(
                    paginator = vm.listings,
                    key = { it.id },
                    state = listState,
                    empty = {
                        if (filter == null) {
                            EmptyState(
                                "You haven't listed anything yet",
                                message = "Turn the stuff you don't need into cash from students nearby.",
                                icon = ListingIcons.Box,
                                actionLabel = "Sell something",
                                onAction = { nav.navigateToTab(TopLevelTab.Sell) },
                            )
                        } else {
                            EmptyState(emptyTitle(filter ?: ListingStatus.ACTIVE), icon = ListingIcons.Box)
                        }
                    },
                ) { listing ->
                    MyListingRow(listing, onClick = { nav.navigate(ListingDetailRoute(listing.id)) })
                    HorizontalDivider(Modifier.padding(start = 96.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun MyListingRow(listing: ListingSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.screen, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListingImage(
            listing.listImageUrl,
            contentDescription = null,
            modifier = Modifier.size(68.dp).clip(MaterialTheme.shapes.small),
            iconSize = 24.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listing.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            PriceText(listing.priceCents, currency = listing.currency, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                ListingStatusChip(listing.status)
                Text(
                    listedLabel(listing.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowSkeletons() {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = false) {
        items(6) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pulse(Modifier.size(68.dp).clip(MaterialTheme.shapes.small), block)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Pulse(Modifier.width(200.dp).height(14.dp).clip(RoundedCornerShape(4.dp)), block)
                    Pulse(Modifier.width(64.dp).height(14.dp).clip(RoundedCornerShape(4.dp)), block)
                    Pulse(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(4.dp)), block)
                }
            }
        }
    }
}

private fun emptyTitle(status: ListingStatus): String = when (status) {
    ListingStatus.ACTIVE -> "No active listings"
    ListingStatus.RESERVED -> "Nothing reserved right now"
    ListingStatus.SOLD -> "Nothing sold yet"
    ListingStatus.REMOVED -> "No removed listings"
    ListingStatus.DELETED -> "No deleted listings"
}
