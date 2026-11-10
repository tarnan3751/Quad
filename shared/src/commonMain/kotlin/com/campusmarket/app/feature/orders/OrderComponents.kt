package com.campusmarket.app.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.TimeFormat
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.OrderStatusChip
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.listImageUrl
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus

/** Orders list row: listing photo, title, the other party and date, status, what's next, amount. */
@Composable
internal fun OrderRow(order: Order, times: TimeFormat, onClick: () -> Unit) {
    val hint = order.listHint(times)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open order", onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        RemoteImage(order.listing.listImageUrl, contentDescription = null, modifier = Modifier.size(60.dp).clip(MaterialTheme.shapes.small))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    order.listing.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                PriceText(order.amountCents, currency = order.currency, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                (if (order.isBuyer) "From " else "To ") + order.otherParty.displayName + " · " + times.date(order.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OrderStatusChip(order.status, Modifier.padding(top = 2.dp))
            if (hint != null) {
                Text(
                    hint.text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (hint.mine) FontWeight.Bold else FontWeight.Medium,
                    color = if (hint.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun toneColors(tone: InfoTone): Pair<Color, Color> = when (tone) {
    InfoTone.Info -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    InfoTone.Warning -> CampusTheme.colors.warningContainer to CampusTheme.colors.onWarningContainer
    InfoTone.Neutral -> CampusTheme.colors.neutralContainer to CampusTheme.colors.onNeutralContainer
    InfoTone.Success -> CampusTheme.colors.successContainer to CampusTheme.colors.onSuccessContainer
}

/** Tinted explanation card ("Reporting a problem puts your payment on hold…"). */
@Composable
internal fun InfoCard(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Info, tone: InfoTone = InfoTone.Info) {
    val (container, content) = toneColors(tone)
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.md))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The order's headline: what's going on, whose turn it is, any clock running (with a countdown), then [actions].
 * [checking] replaces the headline while a return from Stripe Checkout is being confirmed.
 */
@Composable
internal fun NextStepCard(
    step: NextStep,
    otherFirstName: String,
    times: TimeFormat,
    checking: PaymentCheck?,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val (badgeColor, badgeContent) = toneColors(step.tone)
    val icon = when {
        checking == PaymentCheck.Checking -> null
        checking == PaymentCheck.NotReceived -> Icons.Filled.Warning
        step.tone == InfoTone.Success -> Icons.Filled.CheckCircle
        step.tone == InfoTone.Warning && step.actor != NextActor.Me -> Icons.Filled.Lock
        step.tone == InfoTone.Warning -> Icons.Filled.Warning
        step.tone == InfoTone.Neutral -> Icons.Filled.Info
        step.actor == NextActor.Me -> Icons.Filled.Person
        else -> Icons.Filled.DateRange
    }
    SectionCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(40.dp).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) {
                    if (icon == null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = badgeContent)
                    } else {
                        Icon(icon, contentDescription = null, tint = badgeContent, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    val actor = when (step.actor) {
                        NextActor.Me -> "Your turn"
                        NextActor.Them -> "Waiting for $otherFirstName"
                        NextActor.Quad -> "With Quad's team"
                        NextActor.Nobody -> null
                    }
                    if (actor != null && checking == null) ActorPill(actor, mine = step.actor == NextActor.Me)
                    Text(
                        when (checking) {
                            PaymentCheck.Checking -> "Confirming your payment…"
                            PaymentCheck.NotReceived -> "Payment not finished yet"
                            null -> step.title
                        },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        when (checking) {
                            PaymentCheck.Checking -> "Stripe is letting us know. This usually takes a few seconds."
                            PaymentCheck.NotReceived ->
                                "We haven't received your payment. If you closed checkout, continue to payment to finish. " +
                                    "If you just paid, refresh in a moment."
                            null -> step.body
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val deadline = step.deadline
            if (deadline != null && checking != PaymentCheck.Checking) {
                DeadlineRow(deadline, times)
            }
            actions()
        }
    }
}

@Composable
private fun ActorPill(text: String, mine: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CampusTheme.pill,
        color = if (mine) scheme.primary else CampusTheme.colors.neutralContainer,
        contentColor = if (mine) scheme.onPrimary else CampusTheme.colors.onNeutralContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** "Released to Maya automatically · in 2 days, 4 hours · Thu, Nov 12 at 9:24 AM". */
@Composable
private fun DeadlineRow(deadline: Deadline, times: TimeFormat) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f), shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(deadline.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${times.countdown(deadline.at).replaceFirstChar { it.uppercaseChar() }} · ${times.dateTime(deadline.at)}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/** "How was trading with Jordan?" with five big stars; a tap opens the rating form with that many selected. */
@Composable
internal fun RatingPromptCard(name: String, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("How was trading with $name?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your rating helps other students trade with confidence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 1..5) {
                    IconButton(onClick = { onRate(i) }, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Rate $i star${if (i > 1) "s" else ""}",
                            tint = CampusTheme.colors.star.copy(alpha = 0.35f),
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Vertical status timeline with dots and connectors. */
@Composable
internal fun StatusTimeline(steps: List<TimelineStep>, times: TimeFormat, modifier: Modifier = Modifier) {
    Column(modifier) {
        steps.forEachIndexed { i, step ->
            val last = i == steps.lastIndex
            val stateWord = when (step.state) {
                StepState.Done -> "done"
                StepState.Current -> "in progress"
                StepState.Upcoming -> "next"
                StepState.Stopped -> "stopped"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    // Title and time are read from the texts; the state is the value (a description would repeat the title).
                    .semantics(mergeDescendants = true) { stateDescription = stateWord },
            ) {
                Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    StepDot(step.state)
                    if (!last) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .weight(1f)
                                .background(
                                    if (steps[i + 1].state == StepState.Upcoming) MaterialTheme.colorScheme.outlineVariant
                                    else MaterialTheme.colorScheme.primary,
                                ),
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else Spacing.lg)) {
                    Text(
                        step.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = when (step.state) {
                            StepState.Upcoming -> MaterialTheme.colorScheme.onSurfaceVariant
                            StepState.Stopped -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    val sub = listOfNotNull(step.at?.let(times::dateTime), step.detail).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDot(state: StepState) {
    val size = 22.dp
    when (state) {
        StepState.Done -> Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
        StepState.Current -> Box(
            Modifier.size(size).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
        StepState.Upcoming -> Box(Modifier.size(size).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
        StepState.Stopped -> Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(14.dp)) }
    }
}

/** The money for this order and role (see [amountRows]): every amount in the order's currency. */
@Composable
internal fun AmountBreakdown(order: Order, modifier: Modifier = Modifier) {
    val rows = order.amountRows()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        rows.forEach { row ->
            if (row.emphasis) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AmountLine(
                row.label,
                row.text ?: ((if (row.negative) "−" else "") + Format.price(row.cents ?: 0, order.currency)),
                emphasize = row.emphasis,
                muted = order.status == OrderStatus.CANCELLED || order.status == OrderStatus.REFUNDED,
            )
        }
    }
}

@Composable
private fun AmountLine(label: String, value: String, emphasize: Boolean, muted: Boolean) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (emphasize) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (emphasize && !muted) CampusTheme.colors.price else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Numbered "what happens next" step (checkout, payouts). */
@Composable
internal fun NumberedStep(number: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Card container used to group sections on detail screens. */
@Composable
internal fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(Spacing.lg)) { content() }
    }
}

/** Title inside a [SectionCard] ("Progress", "Payment"). */
@Composable
internal fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}
