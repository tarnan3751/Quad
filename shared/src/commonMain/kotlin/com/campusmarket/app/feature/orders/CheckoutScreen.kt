package com.campusmarket.app.feature.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.chat.MessageSellerRoute
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.ConditionChip
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.components.UserRow
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingDetail
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Buy flow, step 1: review the item and how payment works, then "Continue to payment" creates the order, opens
 * Stripe Checkout in the browser and replaces this screen with the order (which refreshes when the user returns).
 */
@Composable
fun CheckoutScreen(route: CheckoutRoute, nav: NavController) {
    val vm = koinViewModel<CheckoutViewModel>(key = "checkout:${route.listingId}") { parametersOf(route) }
    val listingState by vm.listing.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    CollectEvents(vm.events.flow) { event ->
        when (event) {
            is CheckoutEvent.OrderPlaced -> nav.navigate(OrderDetailRoute(event.orderId)) { popUpTo<CheckoutRoute> { inclusive = true } }
        }
    }
    val listing = listingState.valueOrNull

    Scaffold(
        topBar = { AppTopBar("Review purchase", onBack = { nav.popBackStack() }) },
        bottomBar = {
            if (listing != null && ui.problem == null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.screen, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        InlineError(ui.error, Modifier.fillMaxWidth())
                        PrimaryButton(
                            "Continue to payment · ${Format.price(listing.priceCents, listing.currency)}",
                            onClick = vm::placeOrder,
                            loading = ui.placing,
                            icon = Icons.Filled.Lock,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    ) { padding ->
        UiStateContent(listingState, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { l ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                ItemSummary(l, onOpen = { nav.navigate(ListingDetailRoute(l.id)) })
                ui.problem?.let { problem ->
                    ProblemCard(
                        problem,
                        l,
                        existingOrderId = ui.existingOrderId,
                        onMessageSeller = { nav.navigate(MessageSellerRoute(l.id)) },
                        onOpenOrder = { id -> nav.navigate(OrderDetailRoute(id)) { popUpTo<CheckoutRoute> { inclusive = true } } },
                    )
                }
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PriceLine("Item price", Format.price(l.priceCents, l.currency))
                        PriceLine("Service fee", "Free")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Total", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            PriceText(l.priceCents, currency = l.currency)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text("What happens next", style = MaterialTheme.typography.titleMedium)
                    NumberedStep(
                        1,
                        "Pay securely with Stripe",
                        "Checkout opens in your browser. Your card details go straight to Stripe and never touch Quad.",
                    )
                    NumberedStep(
                        2,
                        "We hold the money",
                        "${l.seller.displayName} isn't paid yet. Meet up to pick up the item" +
                            (if (l.pickupLocation.isNotBlank()) " (usually ${l.pickupLocation})" else "") + ".",
                    )
                    NumberedStep(
                        3,
                        "Confirm pickup to release it",
                        "Once you have the item, tap “Confirm I received it” on the order. If you don't, the money is released to " +
                            "${l.seller.displayName} automatically $DEFAULT_AUTO_RELEASE_HOURS hours after you pay.",
                    )
                    NumberedStep(
                        4,
                        "Something wrong? Report a problem",
                        "If the seller doesn't show up or the item isn't as described, report a problem on the order before then. " +
                            "The money stays on hold until it's sorted out, and our team can refund you.",
                    )
                }
                SellerSection(l, onOpen = { nav.navigate(UserProfileRoute(l.seller.id)) })
            }
        }
    }
}

@Composable
private fun ItemSummary(listing: ListingDetail, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(
            listing.photos.firstOrNull()?.url,
            contentDescription = listing.title,
            modifier = Modifier.size(88.dp).clip(MaterialTheme.shapes.medium),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(listing.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PriceText(listing.priceCents, currency = listing.currency)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                ConditionChip(listing.condition)
                Text(
                    "View listing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onOpen).padding(Spacing.xs),
                )
            }
        }
    }
    if (listing.pickupLocation.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Pickup: ${listing.pickupLocation}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SellerSection(listing: ListingDetail, onOpen: () -> Unit) {
    Column {
        Text("Seller", style = MaterialTheme.typography.titleMedium)
        UserRow(listing.seller, onClick = onOpen, modifier = Modifier.clip(MaterialTheme.shapes.medium))
    }
}

@Composable
private fun PriceLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProblemCard(
    problem: CheckoutProblem,
    listing: ListingDetail,
    existingOrderId: String?,
    onMessageSeller: () -> Unit,
    onOpenOrder: (String) -> Unit,
) {
    val seller = listing.seller.displayName
    val text = when (problem) {
        CheckoutProblem.OwnListing -> "This is your listing, so you can't buy it."
        CheckoutProblem.Unavailable -> if (existingOrderId != null) {
            "You already have an order for this item. Open it to pay or check its status."
        } else {
            "This item is no longer available. Someone else may have reserved or bought it."
        }
        CheckoutProblem.PayoutsNotEnabled ->
            "$seller hasn't set up payouts yet, so they can't accept payments in the app. Message them to let them know you'd like to buy it."
        CheckoutProblem.Blocked -> "You can't buy from $seller because one of you has blocked the other."
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        InfoCard(text, icon = Icons.Filled.Warning, tone = if (existingOrderId != null) InfoTone.Info else InfoTone.Warning)
        when {
            problem == CheckoutProblem.Unavailable && existingOrderId != null ->
                PrimaryButton("View your order", onClick = { onOpenOrder(existingOrderId) }, modifier = Modifier.fillMaxWidth())
            problem == CheckoutProblem.PayoutsNotEnabled ->
                SecondaryButton("Message $seller", onClick = onMessageSeller, modifier = Modifier.fillMaxWidth())
        }
    }
}
