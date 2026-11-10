package com.campusmarket.app.feature.moderation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// Owner: feature C. Register every moderation destination here; the shell calls moderationGraph(nav).

fun NavGraphBuilder.moderationGraph(nav: NavController) {
    composable<ModerationQueueRoute> { ModerationQueueScreen(nav) }
    composable<ReportDetailRoute> { entry -> ReportDetailScreen(entry.toRoute<ReportDetailRoute>(), nav) }
    composable<ModerationLogRoute> { ModerationLogScreen(nav) }
    composable<StaffRoute> { StaffScreen(nav) }
    composable<DisputesRoute> { DisputesScreen(nav) }
    composable<DisputeDetailRoute> { entry -> DisputeDetailScreen(entry.toRoute<DisputeDetailRoute>(), nav) }
}

/** Koin definitions for this feature. */
val moderationModule = module {
    single { ReportedTargets() }
    viewModelOf(::ModerationQueueViewModel)
    viewModel { params -> ReportDetailViewModel(route = params.get(), api = get(), messages = get()) }
    viewModelOf(::ModerationLogViewModel)
    viewModelOf(::StaffViewModel)
    viewModelOf(::DisputesViewModel)
    viewModel { params -> DisputeDetailViewModel(route = params.get(), api = get(), messages = get()) }
}
