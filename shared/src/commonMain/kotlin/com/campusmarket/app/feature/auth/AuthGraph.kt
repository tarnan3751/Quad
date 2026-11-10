package com.campusmarket.app.feature.auth

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun NavGraphBuilder.authGraph(nav: NavController) {
    composable<WelcomeRoute> { WelcomeScreen(nav) }
    composable<RegisterRoute> { RegisterScreen(nav) }
    composable<VerifyEmailRoute> { entry -> VerifyEmailScreen(entry.toRoute<VerifyEmailRoute>(), nav) }
    composable<LoginRoute> { entry -> LoginScreen(entry.toRoute<LoginRoute>(), nav) }
}

val authModule = module {
    single { PendingVerification() }
    viewModel { RegisterViewModel(session = get(), pending = get()) }
    viewModel { params -> VerifyEmailViewModel(route = params.get(), session = get(), pending = get()) }
    viewModel { params -> LoginViewModel(route = params.get(), session = get(), pending = get()) }
    viewModel { DeleteSuspendedAccountViewModel(api = get(), session = get()) }
}
