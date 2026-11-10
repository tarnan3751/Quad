package com.campusmarket.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.navigation.TopLevelTab
import com.campusmarket.app.navigation.navigateToTab
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ListingStatusChip
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Conversation
import com.campusmarket.core.ListingStatus
import org.koin.compose.viewmodel.koinViewModel

/** Inbox tab: one row per conversation (listing photo, person, last message, time, unread count). Live. */
@Composable
fun InboxScreen(nav: NavController) {
    val vm = koinViewModel<InboxViewModel>()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val paged by vm.conversations.state.collectAsStateWithLifecycle()
    val times = rememberTimeFormat()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { AppTopBar("Inbox") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(connection, onRetry = vm::retryConnection)
            if (!paged.loaded && paged.error == null) {
                LaunchedEffect(Unit) { if (!paged.isLoading) vm.conversations.refresh() }
                RowSkeletons()
                return@Column
            }
            PagedLazyColumn(
                vm.conversations,
                key = { it.id },
                contentPadding = PaddingValues(bottom = Spacing.sm),
                empty = {
                    EmptyState(
                        "No messages yet",
                        message = "Found something you like? Message the seller from the listing to ask a question or arrange a pickup. " +
                            "Buyers who message you about your listings show up here too.",
                        icon = Icons.Filled.Email,
                        actionLabel = "Browse listings",
                        onAction = { nav.navigateToTab(TopLevelTab.Browse) },
                    )
                },
            ) { conversation ->
                ConversationRow(
                    conversation,
                    stamp = times.inboxStamp(conversation.activityAt),
                    onClick = {
                        vm.onOpen(conversation.id)
                        nav.navigate(ConversationRoute(conversation.id))
                    },
                )
                HorizontalDivider(Modifier.padding(start = 88.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, stamp: String, onClick: () -> Unit) {
    val unread = conversation.unreadCount > 0
    val other = conversation.otherUser
    val scheme = MaterialTheme.colorScheme
    val preview = conversation.lastMessagePreview
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (unread) scheme.primaryContainer.copy(alpha = 0.28f) else scheme.surface)
            .clickable(onClickLabel = "Open conversation", onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Listing photo with the other person's avatar tucked into the corner.
        Box(Modifier.size(60.dp)) {
            RemoteImage(
                conversation.listing.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
            )
            // Initials sit in a fixed-size circle: keep them at their designed size under large font scales.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                Avatar(
                    other.avatarUrl,
                    other.displayName,
                    size = 28.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .border(2.dp, scheme.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        other.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (other.blockedByMe) {
                        Spacer(Modifier.width(Spacing.sm))
                        StatusChip("Blocked", ChipTone.Neutral)
                    }
                }
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    stamp,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    color = if (unread) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.listing.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.listing.status != ListingStatus.ACTIVE) {
                    Spacer(Modifier.width(Spacing.sm))
                    ListingStatusChip(conversation.listing.status)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    fontStyle = if (preview == null) FontStyle.Italic else FontStyle.Normal,
                    color = if (unread) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(Spacing.sm))
                    UnreadCount(conversation.unreadCount)
                }
            }
        }
    }
}

/** Filled count pill ("3", "99+"). */
@Composable
private fun UnreadCount(count: Int) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clearAndSetSemantics { contentDescription = if (count == 1) "1 unread message" else "$count unread messages" }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}
