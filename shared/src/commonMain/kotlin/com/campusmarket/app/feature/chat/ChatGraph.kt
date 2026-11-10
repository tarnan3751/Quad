package com.campusmarket.app.feature.chat

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.campusmarket.app.di.AppScope
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Owner: feature B. Register every chat destination here; the shell calls chatGraph(nav).

fun NavGraphBuilder.chatGraph(nav: NavController) {
    composable<InboxRoute> { InboxScreen(nav) }
    composable<ConversationRoute> { entry -> ConversationScreen(entry.toRoute<ConversationRoute>(), nav) }
    composable<MessageSellerRoute> { entry -> MessageSellerScreen(entry.toRoute<MessageSellerRoute>(), nav) }
}

/** Koin definitions for this feature. `ChatSocket` and `ApiClient` are already singletons in the core module. */
val chatModule = module {
    single<LiveChat> { SocketLiveChat(get()) }
    single { UnreadConversations(api = get(), live = get(), session = get(), scope = get(AppScope)) }
    viewModel { InboxViewModel(api = get(), live = get(), session = get(), userMessages = get(), unread = get()) }
    viewModel { params ->
        ConversationViewModel(route = params.get(), api = get(), live = get(), session = get(), userMessages = get(), unread = get())
    }
    viewModel { params -> MessageSellerViewModel(route = params.get(), api = get()) }
}
