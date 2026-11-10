package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTab
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.DestructiveButton
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ListingRow
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.ReportStatusChip
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.SectionHeader
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.components.UserRow
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.Report
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserRole
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------------- queue

/** Reports by status (Open / Resolved / Dismissed), tap for the detail; app bar action opens the moderation log. */
@Composable
fun ModerationQueueScreen(nav: NavController) {
    ModeratorGate("Moderation", nav) { QueueContent(nav) }
}

@Composable
private fun QueueContent(nav: NavController) {
    val vm = koinViewModel<ModerationQueueViewModel>()
    val status by vm.status.collectAsStateWithLifecycle()

    // Coming back from a report that was just resolved: reload. The flag lives in the view model: this composable
    // leaves the composition while the detail is open, so a `remember`ed one would always look like the first resume.
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            AppTopBar(
                "Moderation",
                onBack = { nav.popBackStack() },
                actions = {
                    if (LocalCurrentUser.current.role == UserRole.ADMIN) {
                        IconButton(onClick = { nav.navigate(StaffRoute) }) {
                            Icon(Icons.Default.Person, contentDescription = "Staff")
                        }
                    }
                    IconButton(onClick = { nav.navigate(DisputesRoute) }) {
                        Icon(Icons.Default.Warning, contentDescription = "Disputes")
                    }
                    IconButton(onClick = { nav.navigate(ModerationLogRoute) }) {
                        Icon(Icons.Default.List, contentDescription = "Moderation log")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = ReportStatus.entries.indexOf(status)) {
                ReportStatus.entries.forEach { s ->
                    AppTab(selected = s == status, onClick = { vm.select(s) }, text = s.label)
                }
            }
            PagedLazyColumn(
                vm.reports,
                key = { it.id },
                empty = {
                    when (status) {
                        ReportStatus.OPEN -> EmptyState("The queue is clear", message = "No open reports right now.", icon = Icons.Default.CheckCircle)
                        ReportStatus.RESOLVED -> EmptyState("Nothing resolved yet")
                        ReportStatus.DISMISSED -> EmptyState("Nothing dismissed yet")
                    }
                },
            ) { report ->
                ReportRow(report, onClick = { nav.navigate(ReportDetailRoute(report.id)) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ReportRow(report: Report, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        overlineContent = {
            Text("${report.targetType.label} · ${report.reason.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        headlineContent = {
            Text(
                report.targetSummary.ifBlank { "(no summary)" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            val tail = if (report.status == ReportStatus.OPEN) Format.relativeTime(report.createdAt)
            else listOfNotNull(report.resolutionAction?.label, report.resolvedAt?.let(Format::relativeTime)).joinToString(" · ")
            Text("Reported by ${report.reporter.displayName} · $tail", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (report.status != ReportStatus.OPEN) ReportStatusChip(report.status)
                if (report.openReportsAgainstTargetUser > 1) {
                    StatusChip("${report.openReportsAgainstTargetUser} open", ChipTone.Warning)
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------- detail

private enum class ModDialog { Dismiss, RemoveListing, Warn, Suspend, DirectSuspend, Unsuspend, DirectRemove, Restore }

/**
 * One report: reporter, reason, details, target summary / listing / user (with their open-report count), and the
 * actions: Dismiss, Remove listing, Warn user, Suspend user (resolve the report); plus direct tools (lift suspension,
 * restore listing) that work even after the report is closed. Every action is confirmed and takes an optional note.
 */
@Composable
fun ReportDetailScreen(route: ReportDetailRoute, nav: NavController) {
    ModeratorGate("Report", nav) { ReportDetailContent(route, nav) }
}

@Composable
private fun ReportDetailContent(route: ReportDetailRoute, nav: NavController) {
    val vm = koinViewModel<ReportDetailViewModel> { parametersOf(route) }
    val state by vm.report.collectAsStateWithLifecycle()
    val action by vm.runner.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<ModDialog?>(null) }
    CollectEvents(vm.actionDone.flow) { dialog = null }

    Scaffold(topBar = { AppTopBar("Report", onBack = { nav.popBackStack() }) }) { padding ->
        UiStateContent(state, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { report ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
                ReportBody(report, nav, onOpenDialog = { vm.runner.clearError(); dialog = it })
            }
            val listing = report.targetListing
            val targetUser = report.targetUser
            val name = targetUser?.displayName ?: "this user"
            when (dialog) {
                null -> Unit
                ModDialog.Dismiss -> ModerationActionDialog(
                    title = "Dismiss this report?",
                    message = "No violation found. The reporter's report is closed and nothing happens to the content.",
                    confirmLabel = "Dismiss",
                    destructive = false,
                    submitting = action.submitting,
                    error = action.error,
                    onDismiss = { dialog = null },
                    onConfirm = { note, _ -> vm.resolve(ModerationAction.DISMISS, note) },
                )
                ModDialog.RemoveListing -> ModerationActionDialog(
                    title = "Remove this listing?",
                    message = "The listing is taken down and the seller can no longer sell it. Other open reports on it are resolved too.",
                    confirmLabel = "Remove listing",
                    submitting = action.submitting,
                    error = action.error,
                    onDismiss = { dialog = null },
                    onConfirm = { note, _ -> vm.resolve(ModerationAction.REMOVE_LISTING, note) },
                )
                ModDialog.Warn -> ModerationActionDialog(
                    title = "Warn $name?",
                    message = "Records a warning in the moderation log and closes the report. The user keeps full access.",
                    confirmLabel = "Warn user",
                    destructive = false,
                    submitting = action.submitting,
                    error = action.error,
                    onDismiss = { dialog = null },
                    onConfirm = { note, _ -> vm.resolve(ModerationAction.WARN_USER, note) },
                )
                ModDialog.Suspend -> ModerationActionDialog(
                    title = "Suspend $name?",
                    message = "They are signed out everywhere and can't sign in until the suspension ends. Other open reports on them are resolved too.",
                    confirmLabel = "Suspend user",
                    showDuration = true,
                    submitting = action.submitting,
                    error = action.error,
                    onDismiss = { dialog = null },
                    onConfirm = { note, days -> vm.resolve(ModerationAction.SUSPEND_USER, note, days) },
                )
                ModDialog.DirectSuspend -> ModerationActionDialog(
                    title = "Suspend $name?",
                    message = "They are signed out everywhere and can't sign in until the suspension ends.",
                    confirmLabel = "Suspend user",
                    noteLabel = "Reason (required, shown in the log)",
                    noteRequired = true,
                    showDuration = true,
                    submitting = action.submitting,
                    error = action.error,
                    onDismiss = { dialog = null },
                    onConfirm = { note, days -> targetUser?.let { vm.suspendUser(it.id, note, days) } },
                )
                ModDialog.Unsuspend -> ModerationActionDialog(
                    title = "Lift suspension for $name?",
                    message = "They will be able to sign in again.",
                    confirmLabel = "Lift suspension",
                    destructive = false,
                    submitting = action.submitting,
                    error = action.error,
                    onDismiss = { dialog = null },
                    onConfirm = { note, _ -> targetUser?.let { vm.unsuspendUser(it.id, note) } },
                )
                ModDialog.DirectRemove -> ModerationActionDialog(
                    title = "Remove this listing?",
                    message = "The listing is taken down. Listings that are reserved or sold can't be removed.",
                    confirmLabel = "Remove listing",
                    submitting = action.submitting,
                    error = action.error,
                    onDismiss = { dialog = null },
                    onConfirm = { note, _ -> listing?.let { vm.removeListing(it.id, note) } },
                )
                ModDialog.Restore -> ModerationActionDialog(
                    title = "Restore this listing?",
                    message = "The listing becomes active and visible on campus again.",
                    confirmLabel = "Restore listing",
                    destructive = false,
                    submitting = action.submitting,
                    error = action.error,
                    onDismiss = { dialog = null },
                    onConfirm = { note, _ -> listing?.let { vm.restoreListing(it.id, note) } },
                )
            }
        }
    }
}

@Composable
private fun ReportBody(report: Report, nav: NavController, onOpenDialog: (ModDialog) -> Unit) {
    val open = report.status == ReportStatus.OPEN

    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReportStatusChip(report.status)
        StatusChip(report.targetType.label, ChipTone.Neutral)
        Spacer(Modifier.weight(1f))
        Text(Format.dateTime(report.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    SectionHeader("Report")
    Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(report.reason.label, style = MaterialTheme.typography.titleMedium)
        if (report.details.isBlank()) {
            Text("No details provided.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(report.details, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(
        "Reported by",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.screen, top = Spacing.md),
    )
    UserRow(report.reporter, onClick = { nav.navigate(UserProfileRoute(report.reporter.id)) }, supporting = report.reporter.campusDomain)

    SectionHeader("Reported ${report.targetType.label.lowercase()}")
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(
            report.targetSummary.ifBlank { "(no summary)" },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
    val listing = report.targetListing
    if (listing != null) {
        Spacer(Modifier.height(Spacing.sm))
        ListingRow(listing, onClick = { nav.navigate(ListingDetailRoute(listing.id)) })
    }

    val targetUser = report.targetUser
    if (targetUser != null) {
        SectionHeader("Responsible user")
        UserRow(targetUser, onClick = { nav.navigate(UserProfileRoute(targetUser.id)) }, supporting = targetUser.campusDomain)
        Row(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            val count = report.openReportsAgainstTargetUser
            StatusChip(
                if (count == 1) "1 open report against this user" else "$count open reports against this user",
                if (count > 1) ChipTone.Warning else ChipTone.Neutral,
            )
        }
    }

    if (!open) {
        SectionHeader("Resolution")
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(report.resolutionAction?.label ?: report.status.label, style = MaterialTheme.typography.titleMedium)
            val by = listOfNotNull(report.resolvedBy?.displayName?.let { "by $it" }, report.resolvedAt?.let { Format.dateTime(it) })
            if (by.isNotEmpty()) {
                Text(by.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!report.resolutionNote.isNullOrBlank()) Text(report.resolutionNote!!, style = MaterialTheme.typography.bodyMedium)
        }
    }

    val actions = resolveActionsFor(report)
    if (actions.isNotEmpty()) {
        SectionHeader("Resolve")
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (ModerationAction.DISMISS in actions) {
                SecondaryButton("Dismiss (no violation)", onClick = { onOpenDialog(ModDialog.Dismiss) }, modifier = Modifier.fillMaxWidth())
            }
            if (ModerationAction.WARN_USER in actions) {
                SecondaryButton("Warn user", onClick = { onOpenDialog(ModDialog.Warn) }, modifier = Modifier.fillMaxWidth())
            }
            if (ModerationAction.REMOVE_LISTING in actions) {
                val removable = listing == null || canRemoveListing(listing.status)
                DestructiveButton("Remove listing", onClick = { onOpenDialog(ModDialog.RemoveListing) }, modifier = Modifier.fillMaxWidth(), enabled = removable)
                if (!removable) {
                    Text(
                        "This listing is ${listing?.status?.label?.lowercase()} and can't be removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ModerationAction.SUSPEND_USER in actions) {
                DestructiveButton("Suspend user", onClick = { onOpenDialog(ModDialog.Suspend) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // Direct tools (not tied to resolving this report).
    val showDirectSuspend = !open && targetUser != null
    val showUnsuspend = targetUser != null
    val showDirectRemove = !open && listing != null && canRemoveListing(listing.status)
    val showRestore = listing != null && canRestoreListing(listing.status)
    if (showDirectSuspend || showUnsuspend || showDirectRemove || showRestore) {
        SectionHeader("Other tools")
        Column(Modifier.padding(horizontal = Spacing.screen)) {
            if (showRestore) TextButton(onClick = { onOpenDialog(ModDialog.Restore) }) { Text("Restore listing") }
            if (showDirectRemove) TextButton(onClick = { onOpenDialog(ModDialog.DirectRemove) }) { Text("Remove listing") }
            if (showUnsuspend) TextButton(onClick = { onOpenDialog(ModDialog.Unsuspend) }) { Text("Lift user's suspension") }
            if (showDirectSuspend) TextButton(onClick = { onOpenDialog(ModDialog.DirectSuspend) }) { Text("Suspend user") }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- log

/** `api.moderationLog()`, newest first. Rows open the related report, listing or user. */
@Composable
fun ModerationLogScreen(nav: NavController) {
    ModeratorGate("Moderation log", nav) {
        val vm = koinViewModel<ModerationLogViewModel>()
        Scaffold(topBar = { AppTopBar("Moderation log", onBack = { nav.popBackStack() }) }) { padding ->
            PagedLazyColumn(
                vm.entries,
                key = { it.id },
                modifier = Modifier.padding(padding),
                empty = { EmptyState("No moderation actions yet") },
            ) { entry ->
                LogRow(entry, onClick = logTarget(entry, nav))
                HorizontalDivider()
            }
        }
    }
}

private fun logTarget(entry: ModerationLogEntry, nav: NavController): () -> Unit = {
    when {
        entry.reportId != null -> nav.navigate(ReportDetailRoute(entry.reportId!!))
        entry.targetType == ReportTargetType.LISTING -> nav.navigate(ListingDetailRoute(entry.targetId))
        entry.targetType == ReportTargetType.USER -> nav.navigate(UserProfileRoute(entry.targetId))
        entry.targetType == ReportTargetType.ORDER -> nav.navigate(DisputeDetailRoute(entry.targetId))
    }
}

@Composable
private fun LogRow(entry: ModerationLogEntry, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = { Avatar(entry.moderator.avatarUrl, entry.moderator.displayName) },
        overlineContent = { Text(entry.targetType.label) },
        headlineContent = { Text("${entry.moderator.displayName}: ${entry.action.label}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (entry.note.isNotBlank()) {
            { Text(entry.note, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        } else null,
        trailingContent = { Text(Format.relativeTime(entry.createdAt), style = MaterialTheme.typography.labelMedium) },
    )
}
