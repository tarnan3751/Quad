package com.campusmarket.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.ui.components.FilterChip
import com.campusmarket.app.ui.components.ListingStatusChip
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary

/** Thin "Reconnecting…" strip while the live connection is down (REST polling keeps things fresh meanwhile). */
@Composable
internal fun ConnectionBanner(status: ChatSocket.Status, modifier: Modifier = Modifier) {
    AnimatedVisibility(status == ChatSocket.Status.Reconnecting, modifier = modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = Spacing.screen, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Text(
                    "Reconnecting… new messages may be delayed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The listing this conversation is about. Tap opens it; buyers get a quick "Buy" while it's for sale. */
@Composable
internal fun ListingStrip(listing: ListingSummary, showBuy: Boolean, onOpen: () -> Unit, onBuy: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteImage(listing.thumbnailUrl, contentDescription = null, modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(listing.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PriceText(listing.priceCents, currency = listing.currency, style = MaterialTheme.typography.bodyMedium)
                        if (listing.status != ListingStatus.ACTIVE) ListingStatusChip(listing.status)
                    }
                }
                if (showBuy) {
                    FilledTonalButton(onClick = onBuy) { Text("Buy") }
                } else {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

/** "Today" / "Yesterday" / "Mar 4" pill between days. */
@Composable
internal fun DaySeparator(dayOf: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.md), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
            Text(
                ChatFormat.dayLabel(dayOf),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 3.dp),
            )
        }
    }
}

/**
 * One message. Mine: right, primary colour; theirs: left, neutral. Long-press theirs to report; a failed one of mine
 * offers retry / delete (tap or long-press).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    row: ChatRow.Bubble,
    onReport: (() -> Unit)?,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val failed = row.delivery == Delivery.Failed
    val hasMenu = failed || onReport != null
    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        row.mine -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        row.mine -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        Modifier.fillMaxWidth().padding(top = if (row.groupedWithOlder) 2.dp else Spacing.sm),
        horizontalAlignment = if (row.mine) Alignment.End else Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth(0.82f), contentAlignment = if (row.mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Surface(
                color = container,
                contentColor = content,
                shape = bubbleShape(row.mine, row.groupedWithOlder),
                modifier = Modifier
                    .clip(bubbleShape(row.mine, row.groupedWithOlder))
                    .then(
                        if (hasMenu) {
                            Modifier.combinedClickable(
                                onClick = { if (failed) menuOpen = true },
                                onLongClick = { menuOpen = true },
                                onLongClickLabel = if (failed) "Message options" else "Report message",
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Column(Modifier.widthIn(min = 56.dp).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                    Text(row.body, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        ChatFormat.time(row.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (failed) {
                    DropdownMenuItem(text = { Text("Try again") }, onClick = { menuOpen = false; onRetry() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDiscard() })
                } else if (onReport != null) {
                    DropdownMenuItem(text = { Text("Report message") }, onClick = { menuOpen = false; onReport() })
                }
            }
        }
        when (row.delivery) {
            Delivery.Sending -> BubbleCaption("Sending…")
            Delivery.Failed -> TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.sm)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text(
                    "${row.error ?: "Not sent"} · Retry",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Delivery.Sent -> row.receipt?.let { BubbleCaption(if (it == Receipt.Seen) "Seen" else "Sent") }
        }
    }
}

@Composable
private fun BubbleCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp),
    )
}

private fun bubbleShape(mine: Boolean, groupedWithOlder: Boolean): Shape {
    val big = 18.dp
    val small = 6.dp
    // The corner pointing at the previous bubble of a group is tucked in.
    return if (mine) {
        RoundedCornerShape(topStart = big, topEnd = if (groupedWithOlder) small else big, bottomEnd = small, bottomStart = big)
    } else {
        RoundedCornerShape(topStart = if (groupedWithOlder) small else big, topEnd = big, bottomEnd = big, bottomStart = small)
    }
}

/** Message input + send button. Shows a counter near [Limits.MESSAGE_MAX] and any validation error. */
@Composable
internal fun Composer(
    draft: String,
    error: String?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    suggestions: List<String> = emptyList(),
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    suggestions.forEach { s -> FilterChip(selected = false, onClick = { onDraft(s) }, label = { Text(s) }) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                val nearLimit = draft.length > Limits.MESSAGE_MAX - 200
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    isError = error != null,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    supportingText = if (error != null || nearLimit) {
                        {
                            Row {
                                Text(error.orEmpty(), modifier = Modifier.weight(1f))
                                if (nearLimit) Text("${draft.length}/${Limits.MESSAGE_MAX}", textAlign = TextAlign.End)
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                FilledIconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.padding(bottom = if (error != null || nearLimit) 22.dp else 4.dp).size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/** Replaces the composer when messages can't be sent. */
@Composable
internal fun RestrictionBanner(text: String, actionLabel: String?, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.md))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** Top of the history: older-page spinner / retry, or the start of the conversation. */
@Composable
internal fun HistoryTop(loadingMore: Boolean, failed: Boolean, endReached: Boolean, listingTitle: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
        when {
            loadingMore -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            failed -> TextButton(onClick = onRetry) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Couldn't load earlier messages. Tap to retry")
            }
            endReached -> Text(
                "This is the start of your conversation about “$listingTitle”.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            else -> Spacer(Modifier.height(24.dp))
        }
    }
}
