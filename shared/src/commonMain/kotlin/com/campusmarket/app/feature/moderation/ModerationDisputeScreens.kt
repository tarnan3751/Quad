package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.profile.ListingLine
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTab
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ErrorState
import com.campusmarket.app.ui.components.OrderStatusChip
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.QuadIcons
import com.campusmarket.app.ui.components.RatingSummary
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.rememberTimeFormat
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.Limits
import com.campusmarket.core.UserPublic
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------------- queue

/** Disputed orders: Open (oldest first, the work queue) / Closed. Linked from the reports queue and the profile tab. */
@Composable
fun DisputesScreen(nav: NavController) {
    ModeratorGate("Disputes", nav) {
        val vm = koinViewModel<DisputesViewModel>()
        val status by vm.status.collectAsStateWithLifecycle()
        LifecycleResumeEffect(vm) {
            vm.onResume()
            onPauseOrDispose {}
        }
        Scaffold(
            topBar = { AppTopBar("Disputes", onBack = { nav.popBackStack() }, actions = { ModerationToolLinks(nav, current = DisputesRoute) }) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = DisputeStatus.entries.indexOf(status), containerColor = MaterialTheme.colorScheme.surface) {
                    DisputeStatus.entries.forEach { s ->
                        AppTab(selected = s == status, onClick = { vm.select(s) }, text = s.label)
                    }
                }
                PagedLazyColumn(
                    vm.disputes,
                    key = { it.dispute.id },
                    empty = {
                        when (status) {
                            DisputeStatus.OPEN -> EmptyState(
                                "No open disputes",
                                message = "Problems buyers report with paid orders, and orders held because a seller was suspended, show up here.",
                                icon = Icons.Default.CheckCircle,
                            )
                            DisputeStatus.CLOSED -> EmptyState("No closed disputes yet", icon = QuadIcons.History)
                        }
                    },
                ) { case ->
                    DisputeRow(case, onClick = { nav.navigate(DisputeDetailRoute(case.orderId)) })
                    HorizontalDivider(Modifier.padding(start = Spacing.screen + 52.dp + Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun DisputeRow(case: DisputeCase, onClick: () -> Unit) {
    val d = case.dispute
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = Spacing.screen, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RemoteImage(case.listing.thumbnailUrl, contentDescription = null, modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.small))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.reason.label, style = MaterialTheme.typography.labelLarge, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PriceText(case.amountCents, currency = case.currency, style = MaterialTheme.typography.titleSmall)
            }
            Text(case.listing.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${case.buyer.displayName} bought from ${case.seller.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                if (d.status == DisputeStatus.OPEN) {
                    Text("Opened ${rememberTimeFormat().ago(d.openedAt)}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                } else {
                    StatusChip(d.outcome?.label ?: d.status.label, ChipTone.Neutral)
                    d.closedAt?.let { Text(rememberTimeFormat().ago(it), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) }
                }
                if (case.sellerSuspended) StatusChip("Seller suspended", ChipTone.Negative)
                if (case.buyerSuspended) StatusChip("Buyer suspended", ChipTone.Negative)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- detail

private enum class DisputeDialog { Refund, Release }

/**
 * One dispute: the money at stake, what the buyer reported, both sides (with suspension status), the order and its
 * timeline, the payment split, and the decision in plain language: refund the buyer or pay the seller, each confirmed
 * with a required internal note.
 */
@Composable
fun DisputeDetailScreen(route: DisputeDetailRoute, nav: NavController) {
    ModeratorGate("Dispute", nav) {
        val vm = koinViewModel<DisputeDetailViewModel>(key = "mod-dispute:${route.orderId}") { parametersOf(route) }
        val state by vm.case.collectAsStateWithLifecycle()
        val action by vm.runner.state.collectAsStateWithLifecycle()
        val refreshing by vm.refreshing.collectAsStateWithLifecycle()
        val me = LocalCurrentUser.current
        var dialog by remember { mutableStateOf<DisputeDialog?>(null) }
        CollectEvents(vm.actionDone.flow) { dialog = null }

        Scaffold(topBar = { AppTopBar("Dispute", onBack = { nav.popBackStack() }) }) { padding ->
            when (val s = state) {
                UiState.Loading -> DetailSkeleton(Modifier.padding(padding))
                is UiState.Error -> ErrorState(s.error, Modifier.fillMaxSize().padding(padding), onRetry = vm::reload)
                is UiState.Content -> PullToRefreshBox(refreshing, onRefresh = vm::pullRefresh, modifier = Modifier.fillMaxSize().padding(padding)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
                        DisputeBody(
                            s.value,
                            nav,
                            canResolve = canResolve(s.value, me.id),
                            isParty = me.id == s.value.buyer.id || me.id == s.value.seller.id,
                            onOpenDialog = { vm.runner.clearError(); dialog = it },
                        )
                    }
                }
            }
        }

        val case = (state as? UiState.Content)?.value ?: return@ModeratorGate
        val payout = Format.price(case.sellerShareCents, case.currency)
        val paid = Format.price(case.amountCents, case.currency)
        when (dialog) {
            null -> Unit
            DisputeDialog.Refund -> ModerationActionDialog(
                title = "Refund ${case.buyer.displayName}?",
                message = "${case.buyer.displayName} gets the full $paid back on their card, ${case.seller.displayName} isn't paid, " +
                    "and the listing goes back on sale. This is final.",
                confirmLabel = "Refund buyer",
                noteLabel = "Reason for the decision (required)",
                noteRequired = true,
                noteMax = Limits.DISPUTE_NOTE_MAX,
                submitting = action.submitting,
                error = action.error,
                onDismiss = { dialog = null },
                onConfirm = { note, _ -> vm.resolve(DisputeResolution.REFUND_BUYER, note, me.id) },
            )
            DisputeDialog.Release -> ModerationActionDialog(
                title = "Pay $payout to ${case.seller.displayName}?",
                message = "${case.seller.displayName} receives $payout (the $paid sale minus the platform fee), the order is " +
                    "completed and ${case.buyer.displayName} can't get a refund in the app. This is final.",
                confirmLabel = "Release to seller",
                noteLabel = "Reason for the decision (required)",
                noteRequired = true,
                noteMax = Limits.DISPUTE_NOTE_MAX,
                submitting = action.submitting,
                error = action.error,
                onDismiss = { dialog = null },
                onConfirm = { note, _ -> vm.resolve(DisputeResolution.RELEASE_TO_SELLER, note, me.id) },
            )
        }
    }
}

@Composable
private fun DisputeBody(case: DisputeCase, nav: NavController, canResolve: Boolean, isParty: Boolean, onOpenDialog: (DisputeDialog) -> Unit) {
    val d = case.dispute
    val scheme = MaterialTheme.colorScheme

    // What's at stake.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(top = Spacing.md),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (d.status == DisputeStatus.OPEN) "On hold" else "Was on hold", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    PriceText(case.amountCents, currency = case.currency, style = CampusTheme.priceLarge)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (d.status == DisputeStatus.CLOSED) StatusChip("Dispute closed", ChipTone.Neutral)
                    OrderStatusChip(case.orderStatus)
                }
            }
            Text(
                if (d.openedBySystem) "Held automatically ${rememberTimeFormat().ago(d.openedAt)} because the seller's account was suspended."
                else "${case.buyer.displayName} reported a problem ${rememberTimeFormat().ago(d.openedAt)}.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }

    // The buyer's side of the story.
    ModHeading(if (d.openedBySystem) "Why it's on hold" else "What the buyer says")
    Column(Modifier.padding(horizontal = Spacing.screen + Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(d.reason.label, style = MaterialTheme.typography.titleMedium)
        if (d.openedBySystem) {
            Text(
                "Nothing is released to a suspended seller without a moderator's decision. The buyer can still confirm they got the item.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            QuoteBlock(d.details, "The buyer didn't add details.")
        }
    }

    // Both parties.
    ModHeading("The two sides")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = Spacing.screen), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PartyCard("Buyer", case.buyer, case.buyerSuspended, Modifier.weight(1f).fillMaxHeight()) { nav.navigate(UserProfileRoute(case.buyer.id)) }
        PartyCard("Seller", case.seller, case.sellerSuspended, Modifier.weight(1f).fillMaxHeight()) { nav.navigate(UserProfileRoute(case.seller.id)) }
    }

    // The order.
    ModHeading("Order")
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        Column(Modifier.padding(bottom = Spacing.md)) {
            ListingLine(case.listing, onClick = { nav.navigate(ListingDetailRoute(case.listing.id)) })
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg), color = scheme.outlineVariant)
            Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AmountRow("Buyer paid", Format.price(case.amountCents, case.currency))
                AmountRow("Platform fee", "− ${Format.price(case.feeCents, case.currency)}")
                AmountRow("Seller would receive", Format.price(case.sellerShareCents, case.currency), strong = true)
            }
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg), color = scheme.outlineVariant)
            Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val times = rememberTimeFormat()
                AmountRow("Ordered", times.dateTime(case.orderCreatedAt))
                case.paidAt?.let { AmountRow("Paid", times.dateTime(it)) }
                AmountRow("Problem reported", times.dateTime(d.openedAt))
                d.closedAt?.let { AmountRow("Closed", times.dateTime(it)) }
            }
        }
    }

    when {
        d.status == DisputeStatus.CLOSED -> {
            ModHeading("Outcome")
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(d.outcome?.label ?: d.status.label, style = MaterialTheme.typography.titleMedium)
                    val times = rememberTimeFormat()
                    val by = listOfNotNull(case.resolvedBy?.displayName?.let { "by $it" }, d.closedAt?.let(times::dateTime))
                    if (by.isNotEmpty()) Text(by.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (case.resolvedBy != null) QuoteBlock(case.resolutionNote, "No note was left.")
                }
            }
        }
        canResolve -> {
            ModHeading("Decide")
            Text(
                "Check the listing and both profiles first. Either way money moves and it's final. The buyer and seller see the outcome, never your note.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xs).padding(bottom = Spacing.sm),
            )
            Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DecisionCard(
                    title = "Refund ${case.buyer.displayName} ${Format.price(case.amountCents, case.currency)}",
                    body = "The order is refunded and the listing goes back on sale. ${case.seller.displayName} isn't paid.",
                    button = "Refund buyer",
                    onClick = { onOpenDialog(DisputeDialog.Refund) },
                )
                DecisionCard(
                    title = "Pay ${case.seller.displayName} ${Format.price(case.sellerShareCents, case.currency)}",
                    body = "The order completes and the listing is marked sold. ${case.buyer.displayName} can't get a refund in the app.",
                    button = "Release to seller",
                    onClick = { onOpenDialog(DisputeDialog.Release) },
                )
            }
        }
        isParty -> Text(
            "You're the buyer or the seller in this order, so another moderator has to decide it.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        )
        else -> Text(
            "The order is ${case.orderStatus.label.lowercase()}, so there's nothing to decide. Pull down to refresh.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        )
    }
}

/** One side of the dispute: role, avatar, name, campus, rating, and whether the account is suspended. */
@Composable
private fun PartyCard(role: String, user: UserPublic, suspended: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(role, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Avatar(user.avatarUrl, user.displayName, size = 44.dp)
            Text(user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(user.campusDomain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RatingSummary(user.ratingAverage, user.ratingCount, starSize = 12.dp)
            if (suspended) StatusChip("Suspended", ChipTone.Negative)
        }
    }
}

/** A resolution choice: what it means, then the button that asks for confirmation. */
@Composable
private fun DecisionCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton(button, onClick = onClick, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium))
    }
}
