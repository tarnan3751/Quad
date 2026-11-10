package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.profile.MenuDivider
import com.campusmarket.app.feature.profile.MenuGroup
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ErrorState
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.ListRowSkeletons
import com.campusmarket.app.ui.components.SkeletonLeading
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.components.avatarImageUrl
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.StaffMember
import com.campusmarket.core.UserRole
import org.koin.compose.viewmodel.koinViewModel

/** Admin-only: staff list, user lookup (as you type), promote to moderator / remove moderator access (confirmed). */
@Composable
fun StaffScreen(nav: NavController) {
    if (LocalCurrentUser.current.role == UserRole.ADMIN) {
        StaffContent(nav)
    } else {
        Scaffold(topBar = { AppTopBar("Staff", onBack = { nav.popBackStack() }) }) { padding ->
            EmptyState(
                "Admins only",
                message = "You don't have access to this area.",
                icon = Icons.Default.Lock,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun StaffContent(nav: NavController) {
    val vm = koinViewModel<StaffViewModel>()
    val staff by vm.staff.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val action by vm.runner.state.collectAsStateWithLifecycle()
    val me = LocalCurrentUser.current
    val keyboard = LocalSoftwareKeyboardController.current
    var pending by remember { mutableStateOf<StaffMember?>(null) }
    CollectEvents(vm.actionDone.flow) { pending = null }
    val onAction: (StaffMember) -> Unit = { vm.runner.clearError(); pending = it }

    Scaffold(topBar = { AppTopBar("Staff", onBack = { nav.popBackStack() }, actions = { ModerationToolLinks(nav, current = StaffRoute) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
            Text(
                "Moderators review reports and decide disputes on every campus. Admins are set in the server's configuration, so they can't be changed here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md),
            )
            AppTextField(
                value = query,
                onValueChange = vm::onQuery,
                label = "Find someone by name or email",
                supportingText = "At least $STAFF_SEARCH_MIN_CHARS characters, any campus",
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = vm::clearSearch) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch(); keyboard?.hide() }),
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
            when (val s = search) {
                StaffSearch.Idle -> when (val st = staff) {
                    UiState.Loading -> ListRowSkeletons(rows = 3, Modifier.padding(top = Spacing.md), leading = SkeletonLeading.Avatar)
                    is UiState.Error -> ErrorState(st.error, Modifier.fillMaxWidth().padding(top = Spacing.xl), onRetry = vm::reload)
                    is UiState.Content -> MemberGroup("Moderators and admins (${st.value.size})", st.value, me.id, onAction)
                }
                StaffSearch.Searching -> ListRowSkeletons(rows = 3, Modifier.padding(top = Spacing.md), leading = SkeletonLeading.Avatar)
                is StaffSearch.Failed -> InlineError(s.message, Modifier.fillMaxWidth().padding(Spacing.screen))
                is StaffSearch.Results ->
                    if (s.members.isEmpty()) {
                        EmptyState(
                            "No one found",
                            Modifier.fillMaxWidth().padding(top = Spacing.xl),
                            message = "No verified account matches \"${s.query}\". Check the spelling or try their email.",
                            icon = Icons.Default.Search,
                        )
                    } else {
                        MemberGroup(if (s.members.size == 1) "1 match" else "${s.members.size} matches", s.members, me.id, onAction)
                    }
            }
        }
    }

    pending?.let { member ->
        val target = roleToggleTarget(member) ?: return@let
        val promote = target == UserRole.MODERATOR
        ConfirmDialog(
            title = if (promote) "Make ${member.user.displayName} a moderator?" else "Remove ${member.user.displayName}'s moderator access?",
            confirmLabel = if (promote) "Make moderator" else "Remove access",
            onConfirm = { vm.setRole(member, target) },
            onDismiss = { pending = null },
            destructive = !promote,
            loading = action.submitting,
        ) {
            Text(
                if (promote) "${member.email} will be able to review reports, remove listings, suspend students and decide disputes on every campus, starting right away. You can remove it at any time."
                else "${member.user.displayName} goes back to being a regular student and loses the moderation tools right away. Their past decisions stay in the log.",
            )
            InlineError(action.error)
        }
    }
}

@Composable
private fun MemberGroup(title: String, members: List<StaffMember>, currentUserId: String, onAction: (StaffMember) -> Unit) {
    MenuGroup(title) {
        members.forEachIndexed { i, m ->
            if (i > 0) MenuDivider()
            MemberRow(m, currentUserId, onAction)
        }
    }
}

private fun roleActionLabel(m: StaffMember) = if (m.role == UserRole.MODERATOR) "Remove moderator" else "Make moderator"

@Composable
private fun MemberRow(m: StaffMember, currentUserId: String, onAction: (StaffMember) -> Unit) {
    val isMe = m.user.id == currentUserId
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.md, bottom = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        val name = if (isMe) "${m.user.displayName} (you)" else m.user.displayName
        Avatar(m.user.avatarImageUrl, m.user.displayName, size = 40.dp, contentDescription = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Name, email and role are read as one element; the role button below stays its own.
            Column(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$name, ${m.email}, ${m.role.label}" },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(m.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Role and the change sit on their own line so long names and emails aren't squeezed by the button.
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    m.role.label,
                    when (m.role) {
                        UserRole.ADMIN -> ChipTone.Info
                        UserRole.MODERATOR -> ChipTone.Positive
                        UserRole.USER -> ChipTone.Neutral
                    },
                    Modifier.clearAndSetSemantics { },
                )
                Spacer(Modifier.weight(1f))
                if (canChangeRole(m, currentUserId)) {
                    TextButton(onClick = { onAction(m) }) {
                        Text(roleActionLabel(m))
                    }
                } else {
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }
    }
}
