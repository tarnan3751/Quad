package com.campusmarket.app.feature.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.Surface
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.chat.ConversationRoute
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.navigation.LocalCurrentUser
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
import com.campusmarket.app.ui.components.OnResume
import com.campusmarket.app.ui.components.OrderStatusChip
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.RatingInput
import com.campusmarket.app.ui.components.RatingSummary
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.rememberTimeFormat
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
    val times = rememberTimeFormat()
    val payoutsEnabled = LocalCurrentUser.current.payoutsEnabled
    OnResume(vm, vm::onResume)

    Scaffold(topBar = { AppTopBar("Orders", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                OrderRole.entries.forEach { role ->
                    AppTab(selected = tab == role, onClick = { vm.selectTab(role) }, text = role.label)
                }
            }
            key(tab) {
                val paginator = vm.paginator(tab)
                PagedLazyColumn(
                    paginator,
                    key = { it.id },
                    empty = {
                        when {
                            tab == OrderRole.BUYER -> EmptyState(
                                "No purchases yet",
                                message = "When you buy something, it shows up here with what to do next, from payment to pickup.",
                                icon = Icons.Filled.ShoppingCart,
                                actionLabel = "Browse listings",
                                onAction = { nav.navigateToTab(TopLevelTab.Browse) },
                            )
                            payoutsEnabled -> EmptyState(
                                "No sales yet",
                                message = "When a buyer pays for one of your listings, the order shows up here. Post something to get started.",
                                icon = Icons.Filled.ShoppingCart,
                                actionLabel = "Sell something",
                                onAction = { nav.navigateToTab(TopLevelTab.Sell) },
                            )
                            else -> EmptyState(
                                "No sales yet",
                                message = "Buyers can only pay you once payouts are set up. It takes a few minutes with Stripe.",
                                icon = Icons.Filled.ShoppingCart,
                                actionLabel = "Set up payouts",
                                onAction = { nav.navigate(PayoutsRoute) },
                            )
                        }
                    },
                ) { order ->
                    OrderRow(order, times, onClick = { nav.navigate(OrderDetailRoute(order.id)) })
                    HorizontalDivider(Modifier.padding(start = 88.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- detail

/**
 * Order detail: what's happening and whose turn it is (with any automatic deadline), the actions, the timeline, the
 * money and the other party. Reloads on resume because payment happens in the browser.
 */
@Composable
fun OrderDetailScreen(route: OrderDetailRoute, nav: NavController) {
    // Keyed by id: a deep link to another order while this screen is on top reuses the destination (launchSingleTop).
    val vm = koinViewModel<OrderDetailViewModel>(key = "order:${route.orderId}") { parametersOf(route) }
    val state by vm.order.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    OnResume(vm, vm::onResume)
    CollectEvents(vm.events.flow) { event ->
        when (event) {
            is OrderDetailEvent.OpenConversation -> nav.navigate(ConversationRoute(event.conversationId))
            is OrderDetailEvent.OpenRating -> nav.navigate(RateOrderRoute(event.orderId))
            is OrderDetailEvent.OpenReportProblem -> nav.navigate(ReportProblemRoute(event.orderId))
        }
    }
    val loaded = state.valueOrNull

    Scaffold(
        topBar = {
            AppTopBar(
                when {
                    loaded == null -> "Order"
                    loaded.isBuyer -> "Your purchase"
                    else -> "Your sale"
                },
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { vm.refresh(userInitiated = true) }, enabled = !ui.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh order")
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
                    onRecheckPayment = vm::recheckPayment,
                    onRate = { stars -> nav.navigate(RateOrderRoute(order.id, stars)) },
                    onOpenListing = { nav.navigate(ListingDetailRoute(order.listing.id)) },
                    onOpenUser = { nav.navigate(UserProfileRoute(order.otherParty.id)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    val confirming = ui.confirming
    if (loaded != null && confirming != null) {
        loaded.confirmCopy(confirming)?.let { copy ->
            ConfirmDialog(
                title = copy.title,
                message = copy.message,
                confirmLabel = copy.confirmLabel,
                onConfirm = vm::confirm,
                onDismiss = vm::dismissConfirm,
                dismissLabel = if (confirming == OrderAction.Cancel) "Keep order" else "Not now",
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
    onRecheckPayment: () -> Unit,
    onRate: (Int) -> Unit,
    onOpenListing: () -> Unit,
    onOpenUser: () -> Unit,
    modifier: Modifier,
) {
    val times = rememberTimeFormat()
    val actions = order.availableActions()
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OrderHeader(order, onOpenListing)

        val checking = ui.paymentCheck.takeIf { order.status == OrderStatus.PENDING_PAYMENT && order.isBuyer }
        NextStepCard(order.nextStep(), firstName(order.otherParty), times, checking) {
            InlineError(ui.error, Modifier.fillMaxWidth())
            CardActions(order, actions, ui, checking, onAction, onRecheckPayment)
        }

        if (OrderAction.Rate in actions) {
            RatingPromptCard(firstName(order.otherParty), onRate)
        } else if (order.status == OrderStatus.COMPLETED && order.iHaveRated) {
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

        DisputeCard(order, times)

        SectionCard {
            Column {
                CardTitle("Progress", Modifier.padding(bottom = Spacing.md))
                StatusTimeline(order.timeline(times), times)
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CardTitle(if (order.isBuyer) "Payment" else "Your earnings")
                AmountBreakdown(order)
                Text(order.paymentNote(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        PartyCard(
            order,
            canMessage = OrderAction.Message in actions && ui.running == null,
            onOpenUser = onOpenUser,
            onMessage = { onAction(OrderAction.Message) },
        )

        if (OrderAction.Cancel in actions) CancelSection(order, enabled = ui.running == null, onCancel = { onAction(OrderAction.Cancel) })

        Text(
            "Order ${order.id.take(8).uppercase()} · placed ${times.dateTimeInline(order.createdAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OrderHeader(order: Order, onOpenListing: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Open listing", onClick = onOpenListing)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(order.listing.thumbnailUrl, contentDescription = null, modifier = Modifier.size(76.dp).clip(MaterialTheme.shapes.medium))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(order.listing.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PriceText(order.amountCents, currency = order.currency)
                OrderStatusChip(order.status)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The other person: tap for their profile, or message them. */
@Composable
private fun PartyCard(order: Order, canMessage: Boolean, onOpenUser: () -> Unit, onMessage: () -> Unit) {
    val other = order.otherParty
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClickLabel = "View profile", onClick = onOpenUser)
                .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.md, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(other.avatarUrl, other.displayName, size = 44.dp)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (order.isBuyer) "Seller" else "Buyer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(other.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RatingSummary(other.ratingAverage, other.ratingCount)
            }
            if (canMessage) {
                IconButton(onClick = onMessage) {
                    Icon(Icons.Filled.Email, contentDescription = order.actionLabel(OrderAction.Message), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Inside the next-step card: the main action, then talking to the other party, then the quieter options. */
@Composable
private fun CardActions(
    order: Order,
    actions: List<OrderAction>,
    ui: OrderDetailUi,
    checking: PaymentCheck?,
    onAction: (OrderAction) -> Unit,
    onRecheckPayment: () -> Unit,
) {
    val busy = ui.running != null
    val active = order.status == OrderStatus.PENDING_PAYMENT || order.status == OrderStatus.PAID || order.status == OrderStatus.DISPUTED
    if (checking == PaymentCheck.Checking) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (OrderAction.Pay in actions) {
            PrimaryButton(
                "${order.actionLabel(OrderAction.Pay)} · ${Format.price(order.amountCents, order.currency)}",
                onClick = { onAction(OrderAction.Pay) },
                enabled = !busy,
                icon = Icons.Filled.Lock,
                modifier = Modifier.fillMaxWidth(),
            )
            if (checking == PaymentCheck.NotReceived) {
                SecondaryButton("I've paid, check again", onClick = onRecheckPayment, icon = Icons.Filled.Refresh, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
        }
        if (OrderAction.ConfirmReceipt in actions) {
            val label = order.actionLabel(OrderAction.ConfirmReceipt)
            val loading = ui.running == OrderAction.ConfirmReceipt
            // While a problem report is open, confirming is one way out among several, not the obvious next step.
            if (order.status == OrderStatus.DISPUTED) {
                SecondaryButton(label, { onAction(OrderAction.ConfirmReceipt) }, Modifier.fillMaxWidth(), !busy, loading, Icons.Filled.CheckCircle)
            } else {
                PrimaryButton(label, { onAction(OrderAction.ConfirmReceipt) }, Modifier.fillMaxWidth(), !busy, loading, Icons.Filled.CheckCircle)
            }
        }
        if (OrderAction.Message in actions && active) {
            SecondaryButton(
                order.actionLabel(OrderAction.Message),
                onClick = { onAction(OrderAction.Message) },
                loading = ui.running == OrderAction.Message,
                enabled = !busy,
                icon = Icons.Filled.Email,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (OrderAction.ReportProblem in actions) {
            TextButton(onClick = { onAction(OrderAction.ReportProblem) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(order.actionLabel(OrderAction.ReportProblem))
            }
        }
        if (OrderAction.WithdrawDispute in actions) {
            TextButton(onClick = { onAction(OrderAction.WithdrawDispute) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(order.actionLabel(OrderAction.WithdrawDispute))
            }
        }
    }
}

/** The destructive way out, at the end of the page with its consequence spelled out (and confirmed in a dialog). */
@Composable
private fun CancelSection(order: Order, enabled: Boolean, onCancel: () -> Unit) {
    val note = when {
        order.status == OrderStatus.PENDING_PAYMENT && order.isBuyer -> "Changed your mind? You haven't been charged."
        order.status == OrderStatus.PENDING_PAYMENT -> "Not selling after all? The buyer won't be able to pay."
        else -> "Can't hand it over? ${order.buyer.displayName} gets a full refund and you won't be paid."
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(Spacing.sm))
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        TextButton(
            onClick = onCancel,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(order.actionLabel(OrderAction.Cancel)) }
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
                    Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()),
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
                        RatingInput(state.stars, onChange = vm::onStars, enabled = !state.submitting, starSize = 40.dp)
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
