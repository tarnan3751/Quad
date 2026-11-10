package com.campusmarket.app.feature.browse

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.campusmarket.app.data.SessionManager
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Owner: feature A. Register every browse destination here; the shell calls browseGraph(nav).

fun NavGraphBuilder.browseGraph(nav: NavController) {
    composable<BrowseRoute> { BrowseScreen(nav) }
}

/** Koin definitions for this feature. `ListingSync` comes from `listingModule`. */
val browseModule = module {
    viewModel {
        BrowseViewModel(
            api = get(),
            sync = get(),
            messages = get(),
            recents = RecentSearches.stored(get(), get<SessionManager>().currentUser?.id),
        )
    }
}
