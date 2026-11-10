package com.campusmarket.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.ListingStatusChip
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.QuadIcons
import com.campusmarket.app.ui.components.RatingSummary
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.avatarImageUrl
import com.campusmarket.app.ui.components.listImageUrl
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.UserPublic

// Building blocks shared by the profile and moderation screens (feature-local on purpose: see docs/CLIENT.md).

// ---------------------------------------------------------------------------------------------------- menu

/** A titled card of [MenuRow]s (the grouped-list look of the profile tab and settings). [warning] tints the title red. */
@Composable
internal fun MenuGroup(title: String?, modifier: Modifier = Modifier, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xl, bottom = Spacing.sm).semantics { heading() },
            )
        } else {
            Box(Modifier.height(Spacing.lg))
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** Hairline between two rows of a [MenuGroup], inset past the icon. */
@Composable
internal fun MenuDivider() {
    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}

/**
 * One row of a [MenuGroup]: tinted icon tile, title, optional supporting line, then [trailing] (a chevron by
 * default, so it reads as "opens a screen"). Rows without [onClick] are plain information.
 */
@Composable
internal fun MenuRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = if (onClick != null) ({ Chevron() }) else null,
    destructive: Boolean = false,
    /** What [trailing] says ("Opens in your browser", "Set up"), for screen readers. */
    trailingDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val tileColor = if (destructive) scheme.errorContainer else scheme.primaryContainer
    val iconColor = if (destructive) scheme.onErrorContainer else scheme.onPrimaryContainer
    // Read as one element in this order, whatever part of the row is on screen.
    val spoken = listOfNotNull(title, supporting, trailingDescription).joinToString(", ")
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = spoken }
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            Modifier.size(32.dp).background(tileColor, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (destructive) scheme.error else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal const val EXTERNAL_MARK_DESCRIPTION = "Opens in your browser"

/** Trailing mark for rows that open a web page in the browser. */
@Composable
internal fun ExternalMark() {
    Icon(QuadIcons.OpenInNew, contentDescription = EXTERNAL_MARK_DESCRIPTION, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
}

/**
 * A listing as one tappable line inside a card (no background of its own, unlike `ListingRow`): thumbnail, title,
 * price, then [trailing] (the status by default).
 */
@Composable
internal fun ListingLine(
    listing: ListingSummary,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit = { ListingStatusChip(listing.status) },
) {
    val spoken = "${listing.title}, ${Format.price(listing.priceCents, listing.currency)}, ${listing.status.label}"
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RemoteImage(listing.listImageUrl, contentDescription = null, modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.small))
        Column(Modifier.weight(1f)) {
            Text(listing.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            PriceText(listing.priceCents, currency = listing.currency, style = MaterialTheme.typography.bodyMedium)
        }
        trailing()
    }
}

/** A person as one tappable line inside a card: avatar, name, [supporting] (rating by default), chevron. */
@Composable
internal fun PersonLine(user: UserPublic, onClick: (() -> Unit)?, supporting: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "View profile", onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Avatar(user.avatarImageUrl, user.displayName, size = 44.dp, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else RatingSummary(user.ratingAverage, user.ratingCount)
        }
        trailing?.invoke()
        if (onClick != null) Chevron()
    }
}

// ---------------------------------------------------------------------------------------------------- stats

/** A number with its label ("3" / "Active listings"); tappable when [onClick] is set. [star] puts a rating star before it. */
internal data class Stat(
    val value: String,
    val label: String,
    val onClick: (() -> Unit)? = null,
    val description: String = "$value $label",
    val star: Boolean = false,
)

/** A card with up to three [Stat]s split by hairlines. [loading] shows dashes. */
@Composable
internal fun StatsCard(stats: List<Stat>, modifier: Modifier = Modifier, loading: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            stats.forEachIndexed { i, stat ->
                if (i > 0) VerticalDivider(Modifier.fillMaxHeight().padding(vertical = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
                StatCell(stat, loading)
            }
        }
    }
}

@Composable
private fun RowScope.StatCell(stat: Stat, loading: Boolean) {
    Column(
        Modifier.weight(1f)
            .then(if (stat.onClick != null && !loading) Modifier.clickable(role = Role.Button, onClick = stat.onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.xs, vertical = Spacing.md)
            .clearAndSetSemantics { contentDescription = if (loading) "Loading ${stat.label}" else stat.description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (stat.star && !loading) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = CampusTheme.colors.star, modifier = Modifier.size(20.dp))
            }
            Text(
                if (loading) "–" else stat.value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
        }
        Text(
            stat.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
internal fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) = ConfirmDialog(
    title = "Sign out?",
    message = "You'll need your campus email and password to sign in to Quad on this device again.",
    confirmLabel = "Sign out",
    onConfirm = onConfirm,
    onDismiss = onDismiss,
)
