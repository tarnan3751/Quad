package com.campusmarket.app.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.ui.rememberTimeFormat
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.PayoutAccountStatus
import org.koin.compose.viewmodel.koinViewModel

/**
 * Seller payouts: where setup stands (with the three Stripe steps), the one thing to do next, and how getting paid
 * works. Reloads on every resume: the browser returns via `campusmarket://payouts`, or the user just switches back.
 */
@Composable
fun PayoutsScreen(nav: NavController) {
    val vm = koinViewModel<PayoutsViewModel>()
    val account by vm.account.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { AppTopBar("Payouts", onBack = { nav.popBackStack() }) }) { padding ->
        UiStateContent(account, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { status ->
            val stage = status.stage
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                StatusCard(status) {
                    InlineError(error, Modifier.fillMaxWidth())
                    when (stage) {
                        PayoutStage.NotStarted -> PrimaryButton(
                            "Set up payouts with Stripe",
                            onClick = vm::startOnboarding,
                            loading = starting,
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PayoutStage.DetailsNeeded -> PrimaryButton(
                            "Continue setup",
                            onClick = vm::startOnboarding,
                            loading = starting,
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PayoutStage.InReview -> {
                            SecondaryButton("Check status", onClick = vm::refresh, icon = Icons.Filled.Refresh, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = vm::startOnboarding, enabled = !starting, modifier = Modifier.fillMaxWidth()) {
                                Text("Review my details with Stripe")
                            }
                        }
                        PayoutStage.Enabled -> {
                            SecondaryButton("View my sales", onClick = { nav.navigate(OrdersRoute(role = "seller")) }, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = vm::startOnboarding, enabled = !starting, modifier = Modifier.fillMaxWidth()) {
                                Text("Update payout details")
                            }
                        }
                    }
                }

                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        CardTitle("How getting paid works")
                        NumberedStep(1, "A buyer pays in the app", "They check out with Stripe. You never handle card details or cash.")
                        NumberedStep(2, "Quad holds the money", "It's held safely while you meet up and hand over the item.")
                        NumberedStep(
                            3,
                            "You're paid on pickup",
                            "When the buyer confirms they received it (or $DEFAULT_AUTO_RELEASE_HOURS hours after they pay, if " +
                                "they don't report a problem), the price minus the platform fee goes to your payout account.",
                        )
                    }
                }

                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "Why Stripe? Payouts are handled by Stripe Connect, which verifies your identity and bank account as the law " +
                            "requires. Quad never sees your bank details. You only need to set this up once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: PayoutAccountStatus, actions: @Composable () -> Unit) {
    data class Look(val icon: ImageVector, val chip: String, val tone: ChipTone, val title: String, val body: String)

    val stage = status.stage
    val look = when (stage) {
        PayoutStage.NotStarted -> Look(
            Icons.Filled.AccountBox, "Not set up", ChipTone.Neutral,
            "Get paid for what you sell",
            "Buyers can only pay you in the app once payouts are set up. It takes about five minutes: have your bank details and ID ready.",
        )
        PayoutStage.DetailsNeeded -> Look(
            Icons.Filled.Edit, "Details needed", ChipTone.Warning,
            "Finish setting up payouts",
            "Stripe needs a few more details before buyers can pay you. Pick up where you left off.",
        )
        PayoutStage.InReview -> Look(
            Icons.Filled.DateRange, "In review", ChipTone.Info,
            "Stripe is checking your details",
            "This usually takes a few minutes. Buyers can pay you as soon as it's done; we'll update this page when you come back.",
        )
        PayoutStage.Enabled -> Look(
            Icons.Filled.CheckCircle, "Payouts on", ChipTone.Positive,
            "You're ready to get paid",
            "Buyers can pay for your listings in the app, and you're paid when they confirm pickup.",
        )
    }
    val (badge, onBadge) = when (look.tone) {
        ChipTone.Positive -> CampusTheme.colors.successContainer to CampusTheme.colors.onSuccessContainer
        ChipTone.Warning -> CampusTheme.colors.warningContainer to CampusTheme.colors.onWarningContainer
        ChipTone.Info -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    }
    val times = rememberTimeFormat()
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(48.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(look.icon, contentDescription = null, tint = onBadge)
                }
                Spacer(Modifier.width(Spacing.md))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    StatusChip(look.chip, look.tone)
                    Text(look.title, style = MaterialTheme.typography.titleLarge)
                    Text(look.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (stage != PayoutStage.Enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                StatusTimeline(payoutSteps(status), times)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { actions() }
        }
    }
}

/** Account → details → verified, as Stripe Connect onboarding goes. */
internal fun payoutSteps(status: PayoutAccountStatus): List<TimelineStep> {
    fun state(done: Boolean, current: Boolean) = when {
        done -> StepState.Done
        current -> StepState.Current
        else -> StepState.Upcoming
    }
    return listOf(
        TimelineStep("Create your Stripe account", null, state(status.hasAccount, !status.hasAccount)),
        TimelineStep(
            "Add your bank and identity details",
            null,
            state(status.detailsSubmitted, status.hasAccount && !status.detailsSubmitted),
        ),
        TimelineStep(
            "Verified by Stripe",
            null,
            state(status.payoutsEnabled, status.detailsSubmitted && !status.payoutsEnabled),
            if (status.detailsSubmitted && !status.payoutsEnabled) "Usually takes a few minutes" else null,
        ),
    )
}
