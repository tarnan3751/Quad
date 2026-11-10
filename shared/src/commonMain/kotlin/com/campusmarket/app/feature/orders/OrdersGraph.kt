package com.campusmarket.app.feature.orders

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// Owner: feature B. Register every orders/payments destination here; the shell calls ordersGraph(nav).

fun NavGraphBuilder.ordersGraph(nav: NavController) {
    composable<CheckoutRoute> { entry -> CheckoutScreen(entry.toRoute<CheckoutRoute>(), nav) }
    composable<OrdersRoute> { entry -> OrdersScreen(entry.toRoute<OrdersRoute>(), nav) }
    composable<OrderDetailRoute> { entry -> OrderDetailScreen(entry.toRoute<OrderDetailRoute>(), nav) }
    composable<RateOrderRoute> { entry -> RateOrderScreen(entry.toRoute<RateOrderRoute>(), nav) }
    composable<ReportProblemRoute> { entry -> ReportProblemScreen(entry.toRoute<ReportProblemRoute>(), nav) }
    composable<PayoutsRoute> { PayoutsScreen(nav) }
}

/** Koin definitions for this feature. Inject `ExternalLinks` to open Checkout / onboarding URLs. */
val ordersModule = module {
    single { OrderUpdates() }
    viewModel { params -> CheckoutViewModel(route = params.get(), api = get(), links = get(), updates = get(), userMessages = get()) }
    viewModel { params -> OrdersViewModel(route = params.get(), api = get(), updates = get(), userMessages = get()) }
    viewModel { params ->
        OrderDetailViewModel(route = params.get(), api = get(), links = get(), updates = get(), userMessages = get())
    }
    viewModel { params -> RateOrderViewModel(route = params.get(), api = get(), updates = get(), userMessages = get()) }
    viewModel { params -> ReportProblemViewModel(route = params.get(), api = get(), updates = get(), userMessages = get()) }
    viewModelOf(::PayoutsViewModel)
}
