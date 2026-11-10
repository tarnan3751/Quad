package com.campusmarket.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusmarket.app.data.ChatSocket
import com.campusmarket.app.ui.components.FilterChip
import com.campusmarket.app.ui.components.ListingStatusChip
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.SkeletonBox
import com.campusmarket.app.ui.components.listImageUrl
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import kotlinx.coroutines.delay

/**
 * "Connection lost" strip while the live connection is down (REST polling keeps things fresh meanwhile). Waits a
 * moment before showing so a quick reconnect doesn't flash it. [onRetry] skips the backoff wait.
 */
@Composable
internal fun ConnectionBanner(status: ChatSocket.Status, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (status == ChatSocket.Status.Reconnecting) {
            delay(CONNECTION_BANNER_DELAY_MS)
            show = true
        } else {
            show = false
        }
    }
    AnimatedVisibility(show, modifier = modifier, enter = expandVertically(), exit = shrinkVertically()) {
        Surface(color = CampusTheme.colors.warningContainer, contentColor = CampusTheme.colors.onWarningContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(start = Spacing.screen, end = Spacing.xs).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = CampusTheme.colors.onWarningContainer)
                Text(
                    "Connection lost. Reconnecting…",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Retry", color = CampusTheme.colors.onWarningContainer, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private const val CONNECTION_BANNER_DELAY_MS = 1_500L

/**
 * The listing this conversation is about: photo, title, price, status. Tap opens it; buyers get "Buy" while it can
 * be bought; sellers see "Your listing".
 */
@Composable
internal fun ListingStrip(listing: ListingSummary, mine: Boolean, showBuy: Boolean, onOpen: () -> Unit, onBuy: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open listing", onClick = onOpen)
                    .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteImage(listing.listImageUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (mine) {
                        Text("Your listing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(listing.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PriceText(listing.priceCents, currency = listing.currency, style = MaterialTheme.typography.titleSmall)
                        if (listing.status != ListingStatus.ACTIVE) ListingStatusChip(listing.status)
                    }
                }
                if (showBuy) {
                    Spacer(Modifier.width(Spacing.sm))
                    FilledTonalButton(onClick = onBuy, contentPadding = PaddingValues(horizontal = 20.dp)) { Text("Buy") }
                } else {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

/** "Today" / "Yesterday" / "Wednesday" / "Mar 4" pill between days. */
@Composable
internal fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.sm), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50), modifier = Modifier.semantics { heading() }) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 4.dp),
            )
        }
    }
}

/**
 * One message. Mine: right, primary colour; theirs: left, neutral. The time sits in the last bubble of a group.
 * Long-press theirs to report; a failed one of mine offers retry / delete (tap, long-press or the caption).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    row: ChatRow.Bubble,
    time: String,
    senderName: String,
    onReport: (() -> Unit)?,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val failed = row.delivery == Delivery.Failed
    val sending = row.delivery == Delivery.Sending
    val hasMenu = failed || onReport != null
    val scheme = MaterialTheme.colorScheme
    val container = when {
        failed -> scheme.errorContainer
        row.mine -> scheme.primary
        else -> scheme.surfaceContainerHighest
    }
    val content = when {
        failed -> scheme.onErrorContainer
        row.mine -> scheme.onPrimary
        else -> scheme.onSurface
    }
    val showTime = !row.groupedWithNewer || row.delivery != Delivery.Sent
    val shape = bubbleShape(row.mine, row.groupedWithOlder)
    val spoken = "${if (row.mine) "You" else senderName}, $time: ${row.body}"

    Column(
        Modifier.fillMaxWidth().padding(top = if (row.groupedWithOlder) 2.dp else Spacing.sm),
        horizontalAlignment = if (row.mine) Alignment.End else Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth(0.8f), contentAlignment = if (row.mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Surface(
                color = container,
                contentColor = content,
                shape = shape,
                modifier = Modifier
                    .alpha(if (sending) 0.7f else 1f)
                    .clip(shape)
                    .then(
                        if (hasMenu) {
                            Modifier.combinedClickable(
                                onClick = { if (failed) menuOpen = true },
                                onLongClick = { menuOpen = true },
                                onClickLabel = if (failed) "Message options" else null,
                                onLongClickLabel = if (failed) "Message options" else "Report message",
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clearAndSetSemantics { contentDescription = spoken },
            ) {
                Column(Modifier.widthIn(min = 48.dp).padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = if (showTime) 6.dp else 8.dp)) {
                    Text(row.body, style = MaterialTheme.typography.bodyLarge)
                    if (showTime) {
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.75f),
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (failed) {
                    DropdownMenuItem(text = { Text("Try again") }, onClick = { menuOpen = false; onRetry() })
                    DropdownMenuItem(text = { Text("Delete message") }, onClick = { menuOpen = false; onDiscard() })
                } else if (onReport != null) {
                    DropdownMenuItem(text = { Text("Report message") }, onClick = { menuOpen = false; onReport() })
                }
            }
        }
        when (row.delivery) {
            Delivery.Sending -> BubbleCaption("Sending…")
            Delivery.Failed -> Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClickLabel = "Retry sending", onClick = onRetry)
                    .padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(14.dp), tint = scheme.error)
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    "${(row.error ?: "Not sent").trimEnd('.')}. Tap to retry",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.error,
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
    val big = 20.dp
    val small = 6.dp
    // The corner pointing at the previous bubble of a group is tucked in.
    return if (mine) {
        RoundedCornerShape(topStart = big, topEnd = if (groupedWithOlder) small else big, bottomEnd = small, bottomStart = big)
    } else {
        RoundedCornerShape(topStart = if (groupedWithOlder) small else big, topEnd = big, bottomEnd = big, bottomStart = small)
    }
}

/**
 * Message input + send button. The field grows to six lines; Send is disabled while the draft is blank. Shows a
 * counter near [Limits.MESSAGE_MAX] and any validation error. [suggestions] are one-tap openers for an empty chat.
 */
@Composable
internal fun Composer(
    draft: String,
    error: String?,
    placeholder: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    suggestions: List<String> = emptyList(),
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    suggestions.forEach { s -> FilterChip(selected = false, onClick = { onDraft(s) }, label = { Text(s) }) }
                }
            }
            val nearLimit = draft.length > Limits.MESSAGE_MAX - COUNTER_FROM_REMAINING
            if (error != null || nearLimit) {
                Row(Modifier.fillMaxWidth().padding(start = Spacing.screen + 4.dp, end = Spacing.screen, top = Spacing.sm)) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (nearLimit) {
                        Text(
                            "${draft.length} / ${Limits.MESSAGE_MAX}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (draft.length >= Limits.MESSAGE_MAX) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    maxLines = 6,
                    isError = error != null,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                FilledIconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

private const val COUNTER_FROM_REMAINING = 200

/** Replaces the composer when messages can't be sent. */
@Composable
internal fun RestrictionBanner(text: String, actionLabel: String?, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.sm, top = Spacing.md, bottom = Spacing.md),
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
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Couldn't load earlier messages. Tap to retry")
            }
            endReached -> Text(
                "This is the start of your conversation about “$listingTitle”. Keep payment in the app: the money is " +
                    "held until the item is handed over.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            else -> Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------- skeletons

/** Placeholder bubbles while a conversation's history loads. */
@Composable
internal fun BubbleSkeletons(modifier: Modifier = Modifier) {
    val widths = listOf(0.55f to false, 0.4f to false, 0.62f to true, 0.3f to true, 0.5f to false, 0.68f to true)
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.lg).clearAndSetSemantics { contentDescription = "Loading messages" },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.Bottom),
    ) {
        widths.forEach { (w, mine) ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                SkeletonBox(Modifier.fillMaxWidth(w).height(40.dp), RoundedCornerShape(20.dp))
            }
        }
    }
}
