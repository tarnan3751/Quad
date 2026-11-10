package com.campusmarket.app.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.SessionState
import com.campusmarket.app.feature.auth.WelcomeRoute
import com.campusmarket.app.feature.auth.authGraph
import com.campusmarket.app.feature.browse.BrowseRoute
import com.campusmarket.app.feature.browse.browseGraph
import com.campusmarket.app.feature.chat.InboxRoute
import com.campusmarket.app.feature.chat.chatGraph
import com.campusmarket.app.feature.listing.SellRoute
import com.campusmarket.app.feature.listing.listingGraph
import com.campusmarket.app.feature.moderation.moderationGraph
import com.campusmarket.app.feature.orders.OrderDetailRoute
import com.campusmarket.app.feature.orders.PayoutsRoute
import com.campusmarket.app.feature.orders.ordersGraph
import com.campusmarket.app.feature.profile.ProfileRoute
import com.campusmarket.app.feature.profile.profileGraph
import com.campusmarket.app.platform.DeepLink
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.ui.components.AppNavigationBar
import com.campusmarket.app.ui.components.AppNavigationBarItem
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.UserPrivate
import org.koin.compose.koinInject
import kotlin.reflect.KClass

/** The signed-in user, available anywhere inside the signed-in shell. Always fresh (follows `SessionManager.state`). */
val LocalCurrentUser = staticCompositionLocalOf<UserPrivate> { error("LocalCurrentUser is only provided inside the signed-in shell") }

/** Bottom-bar tabs, in order. */
enum class TopLevelTab(
    val label: String,
    val route: Any,
    val routeClass: KClass<*>,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Browse("Browse", BrowseRoute, BrowseRoute::class, Icons.Filled.Search, Icons.Outlined.Search),
    Sell("Sell", SellRoute, SellRoute::class, Icons.Filled.AddCircle, Icons.Outlined.AddCircle),
    Inbox("Inbox", InboxRoute, InboxRoute::class, Icons.Filled.Email, Icons.Outlined.Email),
    Profile("Profile", ProfileRoute, ProfileRoute::class, Icons.Filled.Person, Icons.Outlined.Person),
}

/** Switch tabs the standard way: single top, save/restore each tab's back stack. */
fun NavController.navigateToTab(tab: TopLevelTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Auth gate: signed out → auth flow; signed in → tabs. Each side has its own NavHost, so signing out (or the session
 * expiring) drops the whole signed-in back stack, and a different account starts fresh.
 */
@Composable
fun AppNavigation() {
    val session: SessionManager = koinInject()
    val state by session.state.collectAsStateWithLifecycle()
    // One snackbar queue above both shells: a message sent as the session ends (e.g. "Your account has been deleted")
    // would otherwise be taken by the signed-in shell's collector and dropped when that shell leaves the composition.
    val snackbar = remember { SnackbarHostState() }
    UserMessagesHost(snackbar)
    when (val s = state) {
        SessionState.SignedOut -> AuthShell(snackbar)
        is SessionState.SignedIn -> androidx.compose.runtime.key(s.user.id) {
            CompositionLocalProvider(LocalCurrentUser provides s.user) { SignedInShell(snackbar) }
        }
    }
}

@Composable
private fun AuthShell(snackbar: SnackbarHostState) {
    val nav = rememberNavController()
    Scaffold(snackbarHost = { SnackbarHost(snackbar, Modifier.aboveSystemBars()) }, contentWindowInsets = WindowInsets(0)) { padding ->
        NavHost(nav, startDestination = WelcomeRoute, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            authGraph(nav)
        }
    }
}

@Composable
private fun SignedInShell(snackbar: SnackbarHostState) {
    val nav = rememberNavController()
    DeepLinkHandler(nav)

    val backStackEntry by nav.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = TopLevelTab.entries.firstOrNull { destination.isOn(it.routeClass) }
    val onTabRoot = TopLevelTab.entries.any { destination?.hasRoute(it.routeClass) == true }

    Scaffold(
        // On tab roots the bottom bar already lifts the snackbar above the navigation bar; elsewhere nothing does.
        snackbarHost = { SnackbarHost(snackbar, if (onTabRoot) Modifier else Modifier.aboveSystemBars()) },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (onTabRoot) {
                AppNavigationBar {
                    TopLevelTab.entries.forEach { tab ->
                        val selected = tab == currentTab
                        AppNavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected) nav.navigateToTab(tab) },
                            icon = if (selected) tab.selectedIcon else tab.icon,
                            label = tab.label,
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = BrowseRoute, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            browseGraph(nav)
            listingGraph(nav)
            chatGraph(nav)
            ordersGraph(nav)
            profileGraph(nav)
            moderationGraph(nav)
        }
    }
}

private fun NavDestination?.isOn(routeClass: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(routeClass) } == true

/** The shell's Scaffold has no content insets, so keep snackbars clear of the navigation bar and the keyboard. */
@Composable
private fun Modifier.aboveSystemBars(): Modifier = windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))

/** Routes pending `campusmarket://` links into the signed-in NavHost (they wait while signed out). */
@Composable
private fun DeepLinkHandler(nav: NavController) {
    val pending by DeepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        when (link) {
            is DeepLink.OrderDetail -> nav.navigate(OrderDetailRoute(link.orderId)) { launchSingleTop = true }
            DeepLink.Payouts -> nav.navigate(PayoutsRoute) { launchSingleTop = true }
        }
        DeepLinks.consume(link)
    }
}

/** Shows [UserMessages] as snackbars. */
@Composable
private fun UserMessagesHost(snackbar: SnackbarHostState) {
    val messages: UserMessages = koinInject()
    LaunchedEffect(messages) {
        messages.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.actionLabel == null,
                // Material's default for a snackbar with an action is Indefinite: "Undo" would then sit on top of
                // every following screen (covering bottom buttons) until tapped.
                duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }
}
