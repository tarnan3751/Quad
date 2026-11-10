package com.campusmarket.app.feature.profile

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// Owner: feature C. Register every profile destination here; the shell calls profileGraph(nav).

fun NavGraphBuilder.profileGraph(nav: NavController) {
    composable<ProfileRoute> { ProfileScreen(nav) }
    composable<EditProfileRoute> { EditProfileScreen(nav) }
    composable<UserProfileRoute> { entry -> UserProfileScreen(entry.toRoute<UserProfileRoute>(), nav) }
    composable<SettingsRoute> { SettingsScreen(nav) }
    composable<BlockedUsersRoute> { BlockedUsersScreen(nav) }
}

/** Koin definitions for this feature. */
val profileModule = module {
    viewModelOf(::ProfileViewModel)
    viewModelOf(::EditProfileViewModel)
    viewModel { params -> UserProfileViewModel(route = params.get(), api = get(), session = get(), messages = get()) }
    viewModelOf(::SettingsViewModel)
    viewModelOf(::BlockedUsersViewModel)
}
