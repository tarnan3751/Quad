package com.campusmarket.app.feature.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.navigation.TopLevelTab
import com.campusmarket.app.navigation.navigateToTab
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Conversation
import org.koin.compose.viewmodel.koinViewModel

/** Inbox tab: conversations with listing thumbnail, other person, last message, time and unread badge. Live. */
@Composable
fun InboxScreen(nav: NavController) {
    val vm = koinViewModel<InboxViewModel>()
    val connection by vm.connection.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { AppTopBar("Inbox") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(connection)
            PagedLazyColumn(
                vm.conversations,
                key = { it.id },
                contentPadding = PaddingValues(bottom = Spacing.sm),
                empty = {
                    EmptyState(
                        "No messages yet",
                        message = "When you message a seller, or a buyer messages you about a listing, the conversation shows up here.",
                        icon = Icons.Filled.Email,
                        actionLabel = "Browse listings",
                        onAction = { nav.navigateToTab(TopLevelTab.Browse) },
                    )
                },
            ) { conversation ->
                ConversationRow(conversation, onClick = { nav.navigate(ConversationRoute(conversation.id)) })
                HorizontalDivider(Modifier.padding(start = 88.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    val unread = conversation.unreadCount > 0
    val other = conversation.otherUser
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.screen, vertical = Spacing.md)
            .semantics(mergeDescendants = true) {
                if (unread) contentDescription = "${conversation.unreadCount} unread"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Listing photo with the other person's avatar tucked into the corner.
        Box(Modifier.size(60.dp)) {
            RemoteImage(
                conversation.listing.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
            )
            Avatar(
                other.avatarUrl,
                other.displayName,
                size = 26.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    other.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Format.relativeTime(conversation.activityAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                conversation.listing.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = conversation.lastMessagePreview
                Text(
                    preview ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    fontStyle = if (preview == null) FontStyle.Italic else FontStyle.Normal,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(Spacing.sm))
                    Badge(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary) {
                        Text(if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString())
                    }
                }
            }
        }
    }
}
