package com.campusmarket.app.feature.browse

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// Owner: feature A. Register every browse destination here; the shell calls browseGraph(nav).

fun NavGraphBuilder.browseGraph(nav: NavController) {
    composable<BrowseRoute> { BrowseScreen(nav) }
}

/** Koin definitions for this feature. `ListingSync` comes from `listingModule`. */
val browseModule = module {
    viewModelOf(::BrowseViewModel)
}
