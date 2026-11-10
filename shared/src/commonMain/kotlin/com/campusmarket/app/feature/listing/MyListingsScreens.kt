package com.campusmarket.app.feature.listing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ShoppingCart
import com.campusmarket.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.navigation.TopLevelTab
import com.campusmarket.app.navigation.navigateToTab
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ListingCard
import com.campusmarket.app.ui.components.ListingRow
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.PagedLazyGrid
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingStatus
import org.koin.compose.viewmodel.koinViewModel

/** `api.myFavorites` in a grid; un-favoriting removes the tile (Undo in the snackbar). */
@Composable
fun FavoritesScreen(nav: NavController) {
    val vm = koinViewModel<FavoritesViewModel>()
    Scaffold(topBar = { AppTopBar("Favorites", onBack = { nav.popBackStack() }) }) { padding ->
        PagedLazyGrid(
            paginator = vm.favorites,
            key = { it.id },
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(Spacing.screen),
            empty = {
                EmptyState(
                    "No favorites yet",
                    message = "Tap the heart on a listing to keep it here.",
                    icon = Icons.Filled.FavoriteBorder,
                    actionLabel = "Browse listings",
                    onAction = { nav.navigateToTab(TopLevelTab.Browse) },
                )
            },
        ) { listing ->
            ListingCard(
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
                            icon = Icons.Filled.ShoppingCart,
                            actionLabel = "Sell something",
                            onAction = { nav.navigateToTab(TopLevelTab.Sell) },
                        )
                    } else {
                        EmptyState(emptyTitle(filter ?: ListingStatus.ACTIVE), icon = Icons.Filled.ShoppingCart)
                    }
                },
            ) { listing ->
                ListingRow(
                    listing = listing,
                    onClick = { nav.navigate(ListingDetailRoute(listing.id)) },
                    supporting = listedLabel(listing.createdAt),
                )
                HorizontalDivider(Modifier.padding(start = 88.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
