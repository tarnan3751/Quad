package com.campusmarket.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.rememberTimeFormat
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.UserPublic

/**
 * Grid tile for a listing: square photo, price, title, condition · age. Shows a status chip when the listing isn't
 * ACTIVE (or always with [showStatus]) and a heart when [onToggleFavorite] is given.
 */
@Composable
fun ListingCard(
    listing: ListingSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (() -> Unit)? = null,
    showStatus: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box {
            // The title is right below and the card reads as one item, so the photo needs no description of its own.
            RemoteImage(
                url = listing.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (showStatus || listing.status != ListingStatus.ACTIVE) {
                ListingStatusChip(listing.status, Modifier.align(Alignment.TopStart).padding(Spacing.sm))
            }
            if (onToggleFavorite != null) {
                val haptics = LocalHapticFeedback.current
                // A 48 dp touch target around the 34 dp chip, so a near miss doesn't open the listing instead.
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(if (listing.favorited) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        onToggleFavorite()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
                        modifier = Modifier.size(34.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (listing.favorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (listing.favorited) "Remove from favorites" else "Add to favorites",
                                tint = if (listing.favorited) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier.padding(start = Spacing.md, end = Spacing.md, top = 10.dp, bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PriceText(listing.priceCents, currency = listing.currency)
            Text(
                listing.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${listing.condition.label} · ${rememberTimeFormat().age(listing.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Compact one-line listing for lists (my listings, orders, conversations header, moderation). */
@Composable
fun ListingRow(
    listing: ListingSummary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = { ListingStatusChip(listing.status) },
) {
    val clickModifier = if (onClick != null) Modifier.clickableRow(onClick) else Modifier
    ListItem(
        modifier = modifier.then(clickModifier),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            RemoteImage(
                listing.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
            )
        },
        headlineContent = { Text(listing.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PriceText(listing.priceCents, currency = listing.currency, style = MaterialTheme.typography.bodyMedium)
                if (supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        trailingContent = trailing,
    )
}

/** Avatar + name + rating (seller card, order parties, report targets, blocked users). */
@Composable
fun UserRow(
    user: UserPublic,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickableRow(onClick) else Modifier
    ListItem(
        modifier = modifier.then(clickModifier),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = { Avatar(user.avatarUrl, user.displayName, size = 44.dp, contentDescription = null) },
        headlineContent = { Text(user.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (supporting != null) Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else RatingSummary(user.ratingAverage, user.ratingCount)
        },
        trailingContent = trailing,
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
