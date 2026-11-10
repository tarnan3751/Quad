package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.SectionHeader
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.StaffMember
import com.campusmarket.core.UserRole
import org.koin.compose.viewmodel.koinViewModel

/** Admin-only: staff list, user lookup, promote to moderator / demote to student (with confirmation). */
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

    Scaffold(topBar = { AppTopBar("Staff", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQuery,
                label = { Text("Find a student by name or email") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = vm::clearSearch) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch(); keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
            when (val s = search) {
                StaffSearch.Idle -> UiStateContent(staff, onRetry = vm::reload) { members ->
                    MemberList("Moderators and admins", members, me.id, "No staff yet", onAction = { pending = it })
                }
                StaffSearch.Searching -> CircularProgressIndicator(Modifier.padding(Spacing.lg).size(28.dp))
                is StaffSearch.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(Spacing.lg))
                is StaffSearch.Results -> MemberList("Results", s.members, me.id, "No verified user matches \"${s.query}\"", onAction = { pending = it })
            }
        }
    }

    pending?.let { member ->
        val target = roleToggleTarget(member) ?: return@let
        val promote = target == UserRole.MODERATOR
        AlertDialog(
            onDismissRequest = { if (!action.submitting) pending = null },
            title = { Text(if (promote) "Make ${member.user.displayName} a moderator?" else "Remove moderator access?") },
            text = {
                Column {
                    Text(
                        if (promote) "${member.email} will be able to review reports, remove listings and suspend students on every campus."
                        else "${member.user.displayName} will go back to being a regular student and lose access to the moderation tools.",
                    )
                    InlineError(action.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !action.submitting, onClick = { vm.setRole(member, target) }) {
                    Text(if (promote) "Make moderator" else "Make student")
                }
            },
            dismissButton = { TextButton(enabled = !action.submitting, onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemberList(
    header: String,
    members: List<StaffMember>,
    currentUserId: String,
    empty: String,
    onAction: (StaffMember) -> Unit,
) {
    if (members.isEmpty()) {
        EmptyState(empty, modifier = Modifier.fillMaxSize())
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader(header) }
        items(members, key = { it.user.id }) { m ->
            val canChange = canChangeRole(m, currentUserId)
            ListItem(
                modifier = if (canChange) Modifier.clickable { onAction(m) } else Modifier,
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                leadingContent = { Avatar(m.user.avatarUrl, m.user.displayName) },
                headlineContent = { Text(m.user.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(m.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    if (canChange) {
                        TextButton(onClick = { onAction(m) }) {
                            Text(if (m.role == UserRole.MODERATOR) "Demote" else "Promote")
                        }
                    } else {
                        StatusChip(if (m.user.id == currentUserId) "${m.role.label} (you)" else m.role.label, ChipTone.Info)
                    }
                },
            )
            HorizontalDivider()
        }
    }
}
