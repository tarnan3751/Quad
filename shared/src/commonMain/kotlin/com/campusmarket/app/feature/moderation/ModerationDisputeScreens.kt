package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTab
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.DestructiveButton
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ListingRow
import com.campusmarket.app.ui.components.OrderStatusChip
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.SectionHeader
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.components.UserRow
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.UserPublic
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------------- queue

/** Disputed orders: Open (oldest first, the work queue) / Closed. Opened from the moderation queue's app bar. */
@Composable
fun DisputesScreen(nav: NavController) {
    ModeratorGate("Disputes", nav) {
        val vm = koinViewModel<DisputesViewModel>()
        val status by vm.status.collectAsStateWithLifecycle()
        // Coming back from a dispute that was just resolved: reload (the first resume is the initial load).
        var firstResume by remember { mutableStateOf(true) }
        LifecycleResumeEffect(vm) {
            if (firstResume) firstResume = false else vm.disputes.refresh()
            onPauseOrDispose {}
        }
        Scaffold(topBar = { AppTopBar("Disputes", onBack = { nav.popBackStack() }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = DisputeStatus.entries.indexOf(status)) {
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
                            DisputeStatus.CLOSED -> EmptyState("No closed disputes yet")
                        }
                    },
                ) { case ->
                    DisputeRow(case, onClick = { nav.navigate(DisputeDetailRoute(case.orderId)) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DisputeRow(case: DisputeCase, onClick: () -> Unit) {
    val d = case.dispute
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        overlineContent = {
            Text("${d.reason.label} · ${Format.price(case.amountCents, case.currency)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        headlineContent = { Text(case.listing.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
        supportingContent = {
            val tail = if (d.status == DisputeStatus.OPEN) "opened ${Format.relativeTime(d.openedAt)}"
            else listOfNotNull(d.outcome?.label, d.closedAt?.let(Format::relativeTime)).joinToString(" · ")
            Text("${case.buyer.displayName} bought from ${case.seller.displayName} · $tail", maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (case.sellerSuspended) StatusChip("Seller suspended", ChipTone.Negative)
                if (d.status == DisputeStatus.CLOSED) StatusChip(d.status.label, ChipTone.Neutral)
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------- detail

private enum class DisputeDialog { Refund, Release }

/**
 * One dispute: what the buyer reported, the listing, both parties (with suspension status), the money, and the
 * decision: refund the buyer or release the payment to the seller, each confirmed with a required internal note.
 */
@Composable
fun DisputeDetailScreen(route: DisputeDetailRoute, nav: NavController) {
    ModeratorGate("Dispute", nav) {
        val vm = koinViewModel<DisputeDetailViewModel>(key = "mod-dispute:${route.orderId}") { parametersOf(route) }
        val state by vm.case.collectAsStateWithLifecycle()
        val action by vm.runner.state.collectAsStateWithLifecycle()
        val me = LocalCurrentUser.current
        var dialog by remember { mutableStateOf<DisputeDialog?>(null) }
        CollectEvents(vm.actionDone.flow) { dialog = null }

        Scaffold(topBar = { AppTopBar("Dispute", onBack = { nav.popBackStack() }) }) { padding ->
            UiStateContent(state, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { case ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
                    DisputeBody(
                        case,
                        nav,
                        canResolve = canResolve(case, me.id),
                        isParty = me.id == case.buyer.id || me.id == case.seller.id,
                        onOpenDialog = { vm.runner.clearError(); dialog = it },
                    )
                }
                val payout = Format.price(case.sellerShareCents, case.currency)
                val paid = Format.price(case.amountCents, case.currency)
                when (dialog) {
                    null -> Unit
                    DisputeDialog.Refund -> ModerationActionDialog(
                        title = "Refund ${case.buyer.displayName}?",
                        message = "${case.buyer.displayName} gets the full $paid back to their original payment method, " +
                            "${case.seller.displayName} isn't paid, and the listing goes back on sale. This can't be undone.",
                        confirmLabel = "Refund buyer",
                        noteLabel = "Note (required, internal: shown in the moderation log)",
                        noteRequired = true,
                        submitting = action.submitting,
                        error = action.error,
                        onDismiss = { dialog = null },
                        onConfirm = { note, _ -> vm.resolve(DisputeResolution.REFUND_BUYER, note, me.id) },
                    )
                    DisputeDialog.Release -> ModerationActionDialog(
                        title = "Release $payout to ${case.seller.displayName}?",
                        message = "${case.seller.displayName} is paid (the $paid sale minus the platform fee), the order is " +
                            "completed and the buyer can no longer get a refund in the app. This can't be undone.",
                        confirmLabel = "Release to seller",
                        noteLabel = "Note (required, internal: shown in the moderation log)",
                        noteRequired = true,
                        submitting = action.submitting,
                        error = action.error,
                        onDismiss = { dialog = null },
                        onConfirm = { note, _ -> vm.resolve(DisputeResolution.RELEASE_TO_SELLER, note, me.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DisputeBody(case: DisputeCase, nav: NavController, canResolve: Boolean, isParty: Boolean, onOpenDialog: (DisputeDialog) -> Unit) {
    val d = case.dispute
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(d.status.label, if (d.status == DisputeStatus.OPEN) ChipTone.Warning else ChipTone.Neutral)
        OrderStatusChip(case.orderStatus)
        Spacer(Modifier.weight(1f))
        Text(Format.dateTime(d.openedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SectionHeader("Problem")
    Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(d.reason.label, style = MaterialTheme.typography.titleMedium)
        when {
            d.openedBySystem -> Text(
                "Placed on hold automatically because the seller's account was suspended. Nothing is released to a suspended seller without a moderator's decision.",
                style = MaterialTheme.typography.bodyMedium,
            )
            d.details.isBlank() -> Text("No details provided.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(d.details, style = MaterialTheme.typography.bodyMedium)
        }
    }

    SectionHeader("Listing")
    ListingRow(case.listing, onClick = { nav.navigate(ListingDetailRoute(case.listing.id)) })

    PartySection("Buyer", case.buyer, case.buyerSuspended, nav)
    PartySection("Seller", case.seller, case.sellerSuspended, nav)

    SectionHeader("Payment")
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            AmountRow("Buyer paid", Format.price(case.amountCents, case.currency))
            AmountRow("Platform fee", Format.price(case.feeCents, case.currency))
            AmountRow("Seller would receive", Format.price(case.sellerShareCents, case.currency))
            case.paidAt?.let { AmountRow("Paid", Format.dateTime(it)) }
            AmountRow("Order placed", Format.dateTime(case.orderCreatedAt))
        }
    }

    if (d.status == DisputeStatus.CLOSED) {
        SectionHeader("Outcome")
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(d.outcome?.label ?: d.status.label, style = MaterialTheme.typography.titleMedium)
            val by = listOfNotNull(case.resolvedBy?.displayName?.let { "by $it" }, d.closedAt?.let(Format::dateTime))
            if (by.isNotEmpty()) {
                Text(by.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!case.resolutionNote.isNullOrBlank()) Text(case.resolutionNote!!, style = MaterialTheme.typography.bodyMedium)
        }
    } else if (canResolve) {
        SectionHeader("Decide")
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                "Check the order, the listing and the conversation first. Both decisions move money and are final; the parties see the outcome, not your note.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton("Release to seller", onClick = { onOpenDialog(DisputeDialog.Release) }, modifier = Modifier.fillMaxWidth())
            DestructiveButton("Refund buyer", onClick = { onOpenDialog(DisputeDialog.Refund) }, modifier = Modifier.fillMaxWidth())
        }
    } else if (isParty) {
        Text(
            "You're the buyer or the seller of this order, so another moderator has to resolve it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md),
        )
    }
}

@Composable
private fun PartySection(title: String, user: UserPublic, suspended: Boolean, nav: NavController) {
    SectionHeader(title)
    UserRow(
        user,
        onClick = { nav.navigate(UserProfileRoute(user.id)) },
        supporting = user.campusDomain,
        trailing = if (suspended) ({ StatusChip("Suspended", ChipTone.Negative) }) else null,
    )
}

@Composable
private fun AmountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
