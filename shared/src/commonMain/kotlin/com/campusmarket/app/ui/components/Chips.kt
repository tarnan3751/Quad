package com.campusmarket.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.ReportStatus
import androidx.compose.material3.FilterChip as MaterialFilterChip

enum class ChipTone { Positive, Info, Warning, Negative, Neutral }

/** Small pill for statuses. Prefer the typed variants below so statuses look the same everywhere. */
@Composable
fun StatusChip(text: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val (container, content) = toneColors(tone)
    Surface(color = container, contentColor = content, shape = CampusTheme.pill, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun toneColors(tone: ChipTone): Pair<Color, Color> {
    val c = CampusTheme.colors
    val s = MaterialTheme.colorScheme
    return when (tone) {
        ChipTone.Positive -> c.successContainer to c.onSuccessContainer
        ChipTone.Info -> s.primaryContainer to s.onPrimaryContainer
        ChipTone.Warning -> c.warningContainer to c.onWarningContainer
        ChipTone.Negative -> s.errorContainer to s.onErrorContainer
        ChipTone.Neutral -> c.neutralContainer to c.onNeutralContainer
    }
}

@Composable
fun ListingStatusChip(status: ListingStatus, modifier: Modifier = Modifier) {
    val tone = when (status) {
        ListingStatus.ACTIVE -> ChipTone.Positive
        ListingStatus.RESERVED -> ChipTone.Warning
        ListingStatus.SOLD -> ChipTone.Neutral
        ListingStatus.REMOVED -> ChipTone.Negative
        ListingStatus.DELETED -> ChipTone.Neutral
    }
    StatusChip(status.label, tone, modifier)
}

@Composable
fun OrderStatusChip(status: OrderStatus, modifier: Modifier = Modifier) {
    val tone = when (status) {
        OrderStatus.PENDING_PAYMENT -> ChipTone.Warning
        OrderStatus.PAID -> ChipTone.Info
        OrderStatus.COMPLETED -> ChipTone.Positive
        OrderStatus.CANCELLED -> ChipTone.Neutral
        OrderStatus.REFUNDED -> ChipTone.Neutral
        OrderStatus.DISPUTED -> ChipTone.Negative
    }
    StatusChip(status.label, tone, modifier)
}

@Composable
fun ReportStatusChip(status: ReportStatus, modifier: Modifier = Modifier) {
    val tone = when (status) {
        ReportStatus.OPEN -> ChipTone.Warning
        ReportStatus.RESOLVED -> ChipTone.Positive
        ReportStatus.DISMISSED -> ChipTone.Neutral
    }
    StatusChip(status.label, tone, modifier)
}

@Composable
fun ConditionChip(condition: ListingCondition, modifier: Modifier = Modifier) {
    StatusChip(condition.label, ChipTone.Neutral, modifier)
}

/**
 * Quad's filter chip: Material's `FilterChip` API (import this one instead of `androidx.compose.material3.FilterChip`)
 * drawn as a pill. Unselected it's a quiet card-coloured pill with a hairline; selected it fills with the
 * Golden Hour action gradient and white label/icons (the gradient is a brand moment, like the primary button).
 */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val branded = selected && enabled
    MaterialFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = if (branded) modifier.chipFill(CampusTheme.actionGradient, FilterChipDefaults.Height) else modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = CampusTheme.pill,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.surfaceContainerLow,
            labelColor = scheme.onSurface,
            iconColor = scheme.onSurfaceVariant,
            selectedContainerColor = Color.Transparent,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
            selectedTrailingIconColor = Color.White,
            disabledSelectedContainerColor = scheme.onSurface.copy(alpha = 0.10f),
        ),
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
    )
}

/**
 * Paints [brush] as a pill of the chip's visual [height], centred in the (possibly taller, 48 dp touch target)
 * bounds that a modifier passed to a Material chip receives.
 */
private fun Modifier.chipFill(brush: Brush, height: Dp): Modifier = drawBehind {
    val h = minOf(size.height, height.toPx())
    val top = (size.height - h) / 2f
    drawRoundRect(brush, topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2f))
}
