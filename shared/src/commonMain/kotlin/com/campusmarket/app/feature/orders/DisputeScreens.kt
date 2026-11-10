package com.campusmarket.app.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.ui.TimeFormat
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.FormColumn
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.components.formSubmit
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.Limits
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------------- report a problem

/** The buyer's "Report a problem" form: why (reason) and what happened (details), with what it does to the payment. */
@Composable
fun ReportProblemScreen(route: ReportProblemRoute, nav: NavController) {
    val vm = koinViewModel<ReportProblemViewModel>(key = "dispute:${route.orderId}") { parametersOf(route) }
    val orderState by vm.order.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.done.flow) { nav.popBackStack() }

    Scaffold(topBar = { AppTopBar("Report a problem", onBack = { nav.popBackStack() }) }) { padding ->
        UiStateContent(orderState, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { order ->
            if (!order.isBuyer || order.status != OrderStatus.PAID) {
                EmptyState(
                    "You can't report a problem here",
                    Modifier.fillMaxSize().padding(padding),
                    message = "Problems can be reported by the buyer of a paid order, until the payment is released to the seller.",
                    icon = Icons.Filled.Warning,
                    actionLabel = "Back to the order",
                    onAction = { nav.popBackStack() },
                )
                return@UiStateContent
            }
            FormColumn(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = Spacing.screen, vertical = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text("What went wrong with “${order.listing.title}”?", style = MaterialTheme.typography.headlineSmall)
                    InfoCard(
                        "Your payment of ${Format.price(order.amountCents, order.currency)} stays on hold while the report is " +
                            "open: ${order.seller.displayName} isn't paid. If you sort it out together you can withdraw it; " +
                            "otherwise our team reviews the order and refunds you or pays the seller.",
                        icon = Icons.Filled.Lock,
                        tone = InfoTone.Warning,
                    )
                    Column(Modifier.selectableGroup()) {
                        vm.reasons.forEach { reason ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .selectable(
                                        selected = state.reason == reason,
                                        onClick = { vm.onReason(reason) },
                                        enabled = !state.submitting,
                                        role = Role.RadioButton,
                                    )
                                    .padding(horizontal = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = state.reason == reason, onClick = null, enabled = !state.submitting)
                                Spacer(Modifier.width(Spacing.md))
                                Text(reason.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        state.fieldErrors["reason"]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    AppTextField(
                        value = state.details,
                        onValueChange = vm::onDetails,
                        label = if (state.detailsRequired) "What happened?" else "What happened? (optional)",
                        placeholder = "When and where you met, what was wrong with the item…",
                        supportingText = "Shared with ${order.seller.displayName} and our team",
                        error = state.fieldErrors["details"],
                        singleLine = false,
                        minLines = 4,
                        maxLength = Limits.DISPUTE_DETAILS_MAX,
                        enabled = !state.submitting,
                    )
                    InlineError(state.formError, Modifier.fillMaxWidth())
                    PrimaryButton("Report problem", onClick = vm::submit, loading = state.submitting, modifier = Modifier.fillMaxWidth().formSubmit())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- dispute card

/** The order's dispute as both parties see it: reason, what the buyer wrote, who decides, and how it ended. */
@Composable
internal fun DisputeCard(order: Order, times: TimeFormat, modifier: Modifier = Modifier) {
    val d = order.dispute ?: return
    val open = d.status == DisputeStatus.OPEN
    SectionCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(if (d.openedBySystem) "Order on hold" else "Problem report", Modifier.weight(1f))
                StatusChip(if (open) "Open" else "Closed", if (open) ChipTone.Warning else ChipTone.Neutral)
            }
            Text(d.reason.label, style = MaterialTheme.typography.bodyLarge)
            if (d.details.isNotBlank()) {
                Text("“${d.details}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            }
            Text(
                (if (d.openedBySystem) "Placed by our team" else if (order.isBuyer) "Reported by you" else "Reported by ${order.buyer.displayName}") +
                    " · " + times.dateTime(d.openedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val paths = order.disputePaths()
            if (paths.isNotEmpty()) {
                Text("How it can end", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
                paths.forEach { path ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).padding(0.dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(6.dp)) {}
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Text(path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            order.disputeOutcomeText()?.let { outcome ->
                Text(
                    outcome + (d.closedAt?.let { " · ${times.dateTime(it)}" }.orEmpty()),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}
