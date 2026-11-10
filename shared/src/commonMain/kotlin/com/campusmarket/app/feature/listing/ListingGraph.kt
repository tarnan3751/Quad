package com.campusmarket.app.feature.listing

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.campusmarket.app.di.AppScope
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// Owner: feature A. Register every listing destination here; the shell calls listingGraph(nav).

fun NavGraphBuilder.listingGraph(nav: NavController) {
    composable<SellRoute> { SellScreen(nav) }
    composable<ListingDetailRoute> { entry -> ListingDetailScreen(entry.toRoute<ListingDetailRoute>(), nav) }
    composable<EditListingRoute> { entry -> EditListingScreen(entry.toRoute<EditListingRoute>(), nav) }
    composable<FavoritesRoute> { FavoritesScreen(nav) }
    composable<MyListingsRoute> { MyListingsScreen(nav) }
}

/** Koin definitions for listing screens, plus the feature-wide [ListingSync] (also used by browse). */
val listingModule = module {
    single { ListingSync(api = get(), messages = get(), scope = get(AppScope)) }
    viewModel { params -> ListingDetailViewModel(route = params.get(), api = get(), messages = get(), sync = get()) }
    viewModelOf(::SellViewModel)
    viewModel { params -> EditListingViewModel(route = params.get(), api = get(), messages = get(), sync = get()) }
    viewModelOf(::FavoritesViewModel)
    viewModelOf(::MyListingsViewModel)
}
