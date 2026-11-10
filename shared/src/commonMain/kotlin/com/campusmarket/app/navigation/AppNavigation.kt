package com.campusmarket.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.SessionState
import com.campusmarket.app.feature.auth.WelcomeRoute
import com.campusmarket.app.feature.auth.authGraph
import com.campusmarket.app.feature.browse.BrowseRoute
import com.campusmarket.app.feature.browse.browseGraph
import com.campusmarket.app.feature.chat.UnreadConversations
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
import com.campusmarket.app.platform.AppEnvironment
import com.campusmarket.app.platform.DeepLink
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.PlatformKind
import com.campusmarket.app.ui.components.AppNavigationBar
import com.campusmarket.app.ui.components.AppNavigationBarItem
import com.campusmarket.app.ui.components.AppSnackbar
import com.campusmarket.app.ui.components.AppSnackbarVisuals
import com.campusmarket.app.ui.components.QuadIcons
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.UserPrivate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import org.koin.compose.koinInject
import kotlin.math.roundToInt
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
    Sell("Sell", SellRoute, SellRoute::class, Icons.Filled.AddCircle, QuadIcons.AddCircleOutline),
    Inbox("Inbox", InboxRoute, InboxRoute::class, QuadIcons.ChatBubble, QuadIcons.ChatBubbleOutline),
    Profile("Profile", ProfileRoute, ProfileRoute::class, Icons.Filled.Person, Icons.Outlined.Person),
}

/**
 * Taps on the tab the user is already on (inside the signed-in shell). Collect it with [OnTabReselected] to scroll
 * a tab's list back to the top.
 */
val LocalTabReselected = staticCompositionLocalOf<Flow<TopLevelTab>> { emptyFlow() }

/**
 * Runs [action] when the user taps [tab] in the bottom bar while already on it, e.g.
 * `OnTabReselected(TopLevelTab.Browse) { gridState.animateScrollToItem(0) }`.
 */
@Composable
fun OnTabReselected(tab: TopLevelTab, action: suspend () -> Unit) {
    val reselections = LocalTabReselected.current
    val current by rememberUpdatedState(action)
    LaunchedEffect(reselections, tab) { reselections.filter { it == tab }.collect { current() } }
}

/**
 * How far up from the bottom of the window a screen's pinned bottom content (an action bar, the chat composer)
 * reaches, so the shell shows snackbars above it instead of on top of it. Screens opt in with
 * [Modifier.keepSnackbarsAbove].
 */
private class SnackbarClearance {
    var bottomPx by mutableIntStateOf(0)
        private set
    private var owner: Any? = null

    fun set(by: Any, px: Int) {
        owner = by
        bottomPx = px
    }

    fun release(by: Any) {
        if (owner === by) {
            owner = null
            bottomPx = 0
        }
    }
}

private val LocalSnackbarClearance = staticCompositionLocalOf<SnackbarClearance?> { null }

/** On a screen's pinned bottom bar (action bar, chat composer): the shell's snackbars appear above it, not over it. */
@Composable
fun Modifier.keepSnackbarsAbove(): Modifier {
    val clearance = LocalSnackbarClearance.current ?: return this
    val token = remember { Any() }
    DisposableEffect(clearance, token) { onDispose { clearance.release(token) } }
    return onGloballyPositioned { coordinates ->
        val rootHeight = coordinates.findRootCoordinates().size.height
        clearance.set(token, (rootHeight - coordinates.positionInRoot().y).roundToInt().coerceAtLeast(0))
    }
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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.aboveSystemBars()) { AppSnackbar(it) } },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        NavHost(
            nav,
            startDestination = WelcomeRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = EnterScreen,
            exitTransition = ExitScreen,
            popEnterTransition = PopEnterScreen,
            popExitTransition = PopExitScreen,
        ) {
            authGraph(nav)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SignedInShell(snackbar: SnackbarHostState) {
    val nav = rememberNavController()
    val ios = koinInject<AppEnvironment>().platform == PlatformKind.IOS
    DeepLinkHandler(nav)

    val backStackEntry by nav.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = TopLevelTab.entries.firstOrNull { destination.isOn(it.routeClass) }
    val onTabRoot = destination?.isTabRoot() == true
    val unread = rememberInboxUnread()
    val reselections = remember { MutableSharedFlow<TopLevelTab>(extraBufferCapacity = 1) }
    val clearance = remember { SnackbarClearance() }

    Scaffold(
        // On tab roots the bottom bar already lifts the snackbar above the navigation bar; elsewhere a screen's pinned
        // bar (see keepSnackbarsAbove) or the system bars do.
        snackbarHost = {
            val above = when {
                onTabRoot -> Modifier
                clearance.bottomPx > 0 -> Modifier.padding(bottom = with(LocalDensity.current) { clearance.bottomPx.toDp() })
                else -> Modifier.aboveSystemBars()
            }
            SnackbarHost(snackbar, above) { AppSnackbar(it) }
        },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (onTabRoot) {
                AppNavigationBar {
                    TopLevelTab.entries.forEach { tab ->
                        val selected = tab == currentTab
                        val badge = if (tab == TopLevelTab.Inbox) unread else 0
                        AppNavigationBarItem(
                            selected = selected,
                            onClick = { if (selected) reselections.tryEmit(tab) else nav.navigateToTab(tab) },
                            icon = if (selected) tab.selectedIcon else tab.icon,
                            label = tab.label,
                            badgeCount = badge,
                            badgeDescription = if (badge == 1) "1 unread conversation" else "$badge unread conversations",
                        )
                    }
                }
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalTabReselected provides reselections, LocalSnackbarClearance provides clearance) {
            NavHost(
                nav,
                startDestination = BrowseRoute,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                enterTransition = EnterScreen,
                exitTransition = ExitScreen,
                popEnterTransition = PopEnterScreen,
                popExitTransition = PopExitScreen,
            ) {
                browseGraph(nav)
                listingGraph(nav)
                chatGraph(nav)
                ordersGraph(nav)
                profileGraph(nav)
                moderationGraph(nav)
            }
            // iOS has no "back" between tabs: the edge swipe would otherwise pop Sell / Inbox / Profile to Browse.
            // Registered after the NavHost so it goes first; a screen's own handler (e.g. Sell's unsaved form) still
            // comes before it.
            if (ios) BackHandler(enabled = onTabRoot && currentTab != TopLevelTab.Browse) { }
        }
    }
}

/** Conversations with unread messages, for the Inbox tab badge. [UnreadConversations] keeps it live while collected. */
@Composable
private fun rememberInboxUnread(): Int {
    val unread: UnreadConversations = koinInject()
    val count by unread.count.collectAsStateWithLifecycle()
    LifecycleResumeEffect(unread) {
        unread.refresh()
        onPauseOrDispose { }
    }
    return count
}

private fun NavDestination?.isOn(routeClass: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(routeClass) } == true

private fun NavDestination.isTabRoot(): Boolean = TopLevelTab.entries.any { hasRoute(it.routeClass) }

/** The shell's Scaffold has no content insets, so keep snackbars clear of the navigation bar and the keyboard. */
@Composable
private fun Modifier.aboveSystemBars(): Modifier = windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))

// ---------------------------------------------------------------------------------------------------- transitions

/*
 * Material's "shared axis X", kept short: a pushed screen slides in a little from the end and fades in once the old
 * one has started to fade out; back reverses it. Switching between tab roots is a quick cross-fade.
 */
private const val SLIDE_MS = 300
private const val SLIDE_FRACTION = 0.08f

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.isTabRoot() && targetState.destination.isTabRoot()

private val EnterScreen: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isTabSwitch()) {
        fadeIn(tween(180, delayMillis = 60))
    } else {
        slideInHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing)) { (it * SLIDE_FRACTION).roundToInt() } +
            fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing))
    }
}

private val ExitScreen: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isTabSwitch()) {
        fadeOut(tween(90))
    } else {
        slideOutHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing)) { -(it * SLIDE_FRACTION).roundToInt() } +
            fadeOut(tween(90, easing = FastOutLinearInEasing))
    }
}

private val PopEnterScreen: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing)) { -(it * SLIDE_FRACTION).roundToInt() } +
        fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing))
}

private val PopExitScreen: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing)) { (it * SLIDE_FRACTION).roundToInt() } +
        fadeOut(tween(90, easing = FastOutLinearInEasing))
}

/** Routes pending `campusmarket://` links into the signed-in NavHost (they wait while signed out). */
@Composable
private fun DeepLinkHandler(nav: NavController) {
    val pending by DeepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        when (link) {
            is DeepLink.OrderDetail -> {
                // Back from Stripe: the order is usually already on top, so reuse that screen. A different order gets
                // its own screen (reusing the top one would keep the previous order's scroll position and back stack).
                val top = nav.currentBackStackEntry
                val alreadyOpen = top?.destination?.hasRoute(OrderDetailRoute::class) == true &&
                    top.toRoute<OrderDetailRoute>().orderId == link.orderId
                nav.navigate(OrderDetailRoute(link.orderId)) { launchSingleTop = alreadyOpen }
            }
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
                AppSnackbarVisuals(
                    message.text,
                    actionLabel = message.actionLabel,
                    // Errors may need a second read, so they stay longer and can be closed sooner.
                    withDismissAction = message.isError && message.actionLabel == null,
                    // Material's default for a snackbar with an action is Indefinite: "Undo" would then sit on top of
                    // every following screen (covering bottom buttons) until tapped.
                    duration = if (message.actionLabel != null || message.isError) SnackbarDuration.Long else SnackbarDuration.Short,
                    isError = message.isError,
                ),
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }
}
