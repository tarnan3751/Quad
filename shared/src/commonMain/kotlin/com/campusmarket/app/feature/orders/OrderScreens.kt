package com.campusmarket.app.feature.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.chat.ConversationRoute
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.navigation.TopLevelTab
import com.campusmarket.app.navigation.navigateToTab
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTab
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.OrderStatusChip
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.RatingInput
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.SectionHeader
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.components.UserRow
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Limits
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------------- list

/** Orders with Buying / Selling tabs. `route.role` = "buyer" | "seller" | null (Buying). */
@Composable
fun OrdersScreen(route: OrdersRoute, nav: NavController) {
    val vm = koinViewModel<OrdersViewModel> { parametersOf(route) }
    val tab by vm.tab.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { AppTopBar("Orders", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                OrderRole.entries.forEach { role ->
                    AppTab(selected = tab == role, onClick = { vm.selectTab(role) }, text = role.label)
                }
            }
            key(tab) {
                PagedLazyColumn(
                    vm.paginator(tab),
                    key = { it.id },
                    empty = {
                        if (tab == OrderRole.BUYER) {
                            EmptyState(
                                "No purchases yet",
                                message = "Things you buy show up here, from payment to pickup.",
                                icon = Icons.Filled.ShoppingCart,
                                actionLabel = "Browse listings",
                                onAction = { nav.navigateToTab(TopLevelTab.Browse) },
                            )
                        } else {
                            EmptyState(
                                "No sales yet",
                                message = "When a buyer pays for one of your listings, the order shows up here. Buyers can only pay once you've set up payouts.",
                                icon = Icons.Filled.ShoppingCart,
                                actionLabel = "Payout settings",
                                onAction = { nav.navigate(PayoutsRoute) },
                            )
                        }
                    },
                ) { order ->
                    OrderRow(order, onClick = { nav.navigate(OrderDetailRoute(order.id)) })
                    HorizontalDivider(Modifier.padding(start = 84.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- detail

/**
 * Order detail: where it stands, what to do next (by role and status), the timeline, money breakdown and the other
 * party. Reloads on resume because payment happens in the browser.
 */
@Composable
fun OrderDetailScreen(route: OrderDetailRoute, nav: NavController) {
    // Keyed by id: a deep link to another order while this screen is on top reuses the destination (launchSingleTop).
    val vm = koinViewModel<OrderDetailViewModel>(key = "order:${route.orderId}") { parametersOf(route) }
    val state by vm.order.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }
    CollectEvents(vm.events.flow) { event ->
        when (event) {
            is OrderDetailEvent.OpenConversation -> nav.navigate(ConversationRoute(event.conversationId))
            is OrderDetailEvent.OpenRating -> nav.navigate(RateOrderRoute(event.orderId))
            is OrderDetailEvent.OpenReportProblem -> nav.navigate(ReportProblemRoute(event.orderId))
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                "Order",
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { vm.refresh(userInitiated = true) }, enabled = !ui.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        UiStateContent(state, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { order ->
            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh(userInitiated = true) },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                OrderDetailBody(
                    order,
                    ui,
                    onAction = vm::onAction,
                    onOpenListing = { nav.navigate(ListingDetailRoute(order.listing.id)) },
                    onOpenUser = { nav.navigate(UserProfileRoute(order.otherParty.id)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    val order = state.valueOrNull
    val confirming = ui.confirming
    if (order != null && confirming != null) {
        order.confirmCopy(confirming)?.let { copy ->
            ConfirmDialog(
                title = copy.title,
                message = copy.message,
                confirmLabel = copy.confirmLabel,
                onConfirm = vm::confirm,
                onDismiss = vm::dismissConfirm,
                dismissLabel = "Not now",
                destructive = copy.destructive,
                loading = ui.running == confirming,
            )
        }
    }
}

@Composable
private fun OrderDetailBody(
    order: Order,
    ui: OrderDetailUi,
    onAction: (OrderAction) -> Unit,
    onOpenListing: () -> Unit,
    onOpenUser: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Listing
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onOpenListing).padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(order.listing.thumbnailUrl, contentDescription = null, modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(order.listing.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    Format.price(order.amountCents, order.currency),
                    style = CampusTheme.priceLarge.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                    color = CampusTheme.colors.price,
                )
                OrderStatusChip(order.status)
            }
        }

        InfoCard(order.statusExplanation(), tone = order.infoTone())
        DisputeCard(order)
        InlineError(ui.error, Modifier.fillMaxWidth())
        OrderActions(order, ui, onAction)

        SectionCard {
            Column {
                Text("Progress", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = Spacing.md))
                StatusTimeline(order.timeline())
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Payment", style = MaterialTheme.typography.titleSmall)
                AmountBreakdown(order)
                Text(
                    order.paymentNote(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column {
            SectionHeader(if (order.isBuyer) "Seller" else "Buyer")
            UserRow(order.otherParty, onClick = onOpenUser, modifier = Modifier.clip(MaterialTheme.shapes.medium))
        }

        Text(
            "Order ${order.id.take(8).uppercase()} · placed ${Format.dateTime(order.createdAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OrderActions(order: Order, ui: OrderDetailUi, onAction: (OrderAction) -> Unit) {
    val actions = order.availableActions()
    val busy = ui.running != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        actions.forEach { action ->
            val label = order.actionLabel(action)
            val loading = ui.running == action
            when (action) {
                OrderAction.Pay, OrderAction.ConfirmReceipt -> PrimaryButton(
                    label,
                    onClick = { onAction(action) },
                    loading = loading,
                    enabled = !busy,
                    icon = if (action == OrderAction.ConfirmReceipt) Icons.Filled.CheckCircle else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OrderAction.Rate -> PrimaryButton(label, onClick = { onAction(action) }, enabled = !busy, icon = Icons.Filled.Star, modifier = Modifier.fillMaxWidth())
                OrderAction.Message -> SecondaryButton(
                    label,
                    onClick = { onAction(action) },
                    loading = loading,
                    enabled = !busy,
                    icon = Icons.Filled.Email,
                    modifier = Modifier.fillMaxWidth(),
                )
                OrderAction.ReportProblem -> SecondaryButton(
                    label,
                    onClick = { onAction(action) },
                    enabled = !busy,
                    icon = Icons.Filled.Warning,
                    modifier = Modifier.fillMaxWidth(),
                )
                OrderAction.WithdrawDispute -> TextButton(
                    onClick = { onAction(action) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label) }
                OrderAction.Cancel -> TextButton(
                    onClick = { onAction(action) },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label) }
            }
        }
        if (order.status == OrderStatus.COMPLETED && order.iHaveRated) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CampusTheme.colors.success, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    "You rated ${order.otherParty.displayName}. Thanks!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- rating

private val STAR_WORDS = listOf("", "Poor", "Not great", "OK", "Good", "Great")

/** Stars + optional comment for the other party of a COMPLETED order. */
@Composable
fun RateOrderScreen(route: RateOrderRoute, nav: NavController) {
    val vm = koinViewModel<RateOrderViewModel>(key = "rate:${route.orderId}") { parametersOf(route) }
    val orderState by vm.order.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.done.flow) { nav.popBackStack() }

    Scaffold(topBar = { AppTopBar("Rate", onBack = { nav.popBackStack() }) }) { padding ->
        UiStateContent(orderState, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { order ->
            val other = order.otherParty
            when {
                order.status != OrderStatus.COMPLETED -> EmptyState(
                    "Not ready to rate yet",
                    Modifier.fillMaxSize().padding(padding),
                    message = "You can rate ${other.displayName} once the order is completed.",
                    icon = Icons.Filled.Star,
                )
                order.iHaveRated -> EmptyState(
                    "Already rated",
                    Modifier.fillMaxSize().padding(padding),
                    message = "Thanks! You've rated ${other.displayName} for this order.",
                    icon = Icons.Filled.CheckCircle,
                    actionLabel = "Done",
                    onAction = { nav.popBackStack() },
                )
                else -> Column(
                    Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Avatar(other.avatarUrl, other.displayName, size = 72.dp)
                        Text(
                            "How was trading with ${other.displayName}?",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "${if (order.isBuyer) "You bought" else "You sold"} “${order.listing.title}”. Your rating helps other students trade with confidence.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        RatingInput(state.stars, onChange = vm::onStars, enabled = !state.submitting)
                        Text(
                            state.fieldErrors["stars"] ?: STAR_WORDS[state.stars.coerceIn(0, 5)].ifEmpty { "Tap a star" },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (state.fieldErrors["stars"] != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        AppTextField(
                            value = state.comment,
                            onValueChange = vm::onComment,
                            label = "Comment (optional)",
                            placeholder = "On time, item as described, friendly…",
                            supportingText = "Shown on ${other.displayName}'s profile",
                            error = state.fieldErrors["comment"],
                            singleLine = false,
                            minLines = 3,
                            maxLength = Limits.RATING_COMMENT_MAX,
                            enabled = !state.submitting,
                        )
                        InlineError(state.formError, Modifier.fillMaxWidth())
                        PrimaryButton("Submit rating", onClick = vm::submit, loading = state.submitting, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
