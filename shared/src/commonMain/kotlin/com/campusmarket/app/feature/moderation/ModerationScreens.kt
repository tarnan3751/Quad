package com.campusmarket.app.feature.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.profile.Chevron
import com.campusmarket.app.feature.profile.ListingLine
import com.campusmarket.app.feature.profile.MenuDivider
import com.campusmarket.app.feature.profile.MenuGroup
import com.campusmarket.app.feature.profile.MenuRow
import com.campusmarket.app.feature.profile.PersonLine
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.components.AppTab
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ErrorState
import com.campusmarket.app.ui.components.FilterChip
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.QuadIcons
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.ReportStatusChip
import com.campusmarket.app.ui.components.ShimmerGroup
import com.campusmarket.app.ui.components.SkeletonBox
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.rememberTimeFormat
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.ModerationLogEntry
import com.campusmarket.core.Report
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserRole
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock

// ---------------------------------------------------------------------------------------------------- queue

/**
 * Reports by status (Open / Resolved / Dismissed) and kind (listings, accounts, messages); tap for the detail. The app
 * bar links to disputes, the moderation log and (admins) staff.
 */
@Composable
fun ModerationQueueScreen(nav: NavController) {
    ModeratorGate("Reports", nav) { QueueContent(nav) }
}

private val TypeFilters = listOf(
    null to "All",
    ReportTargetType.LISTING to "Listings",
    ReportTargetType.USER to "Accounts",
    ReportTargetType.MESSAGE to "Messages",
)

@Composable
private fun QueueContent(nav: NavController) {
    val vm = koinViewModel<ModerationQueueViewModel>()
    val status by vm.status.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()

    // Coming back from a report that was just resolved: reload. The flag lives in the view model: this composable
    // leaves the composition while the detail is open, so a `remember`ed one would always look like the first resume.
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            AppTopBar(
                "Reports",
                onBack = { nav.popBackStack() },
                actions = { ModerationToolLinks(nav, current = ModerationQueueRoute) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = ReportStatus.entries.indexOf(status), containerColor = MaterialTheme.colorScheme.surface) {
                ReportStatus.entries.forEach { s ->
                    AppTab(selected = s == status, onClick = { vm.select(s) }, text = s.label)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TypeFilters.forEach { (t, label) ->
                    FilterChip(selected = type == t, onClick = { vm.selectType(t) }, label = { Text(label) })
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PagedLazyColumn(
                vm.reports,
                key = { it.id },
                empty = { QueueEmpty(status, type) },
            ) { report ->
                ReportRow(report, onClick = { nav.navigate(ReportDetailRoute(report.id)) })
                HorizontalDivider(Modifier.padding(start = Spacing.screen + 52.dp + Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** App-bar shortcuts between the moderation screens, labelled for screen readers. */
@Composable
internal fun ModerationToolLinks(nav: NavController, current: Any) {
    if (current != ModerationQueueRoute) {
        IconButton(onClick = { nav.navigate(ModerationQueueRoute) { launchSingleTop = true } }) { Icon(QuadIcons.Flag, contentDescription = "Reports") }
    }
    if (current != DisputesRoute) {
        IconButton(onClick = { nav.navigate(DisputesRoute) { launchSingleTop = true } }) { Icon(QuadIcons.Gavel, contentDescription = "Disputes") }
    }
    if (current != ModerationLogRoute) {
        IconButton(onClick = { nav.navigate(ModerationLogRoute) { launchSingleTop = true } }) { Icon(QuadIcons.History, contentDescription = "Moderation log") }
    }
    if (current != StaffRoute && LocalCurrentUser.current.role == UserRole.ADMIN) {
        IconButton(onClick = { nav.navigate(StaffRoute) { launchSingleTop = true } }) { Icon(QuadIcons.Group, contentDescription = "Staff") }
    }
}

@Composable
private fun QueueEmpty(status: ReportStatus, type: ReportTargetType?) {
    val kind = when (type) {
        null -> ""
        ReportTargetType.LISTING -> "listing "
        ReportTargetType.USER -> "account "
        ReportTargetType.MESSAGE -> "message "
        ReportTargetType.ORDER -> "order "
    }
    when (status) {
        ReportStatus.OPEN -> EmptyState(
            if (type == null) "The queue is clear" else "No open ${kind}reports",
            message = "New reports from students land here, oldest first.",
            icon = Icons.Default.CheckCircle,
        )
        ReportStatus.RESOLVED -> EmptyState("No resolved ${kind}reports yet", message = "Reports closed with a warning, removal or suspension show up here.", icon = QuadIcons.History)
        ReportStatus.DISMISSED -> EmptyState("No dismissed ${kind}reports yet", message = "Reports closed without action show up here.", icon = QuadIcons.History)
    }
}

/** Leading picture of a queue row: the listing's photo, the account's avatar, or a message bubble. */
@Composable
private fun ReportThumb(report: Report) {
    val listing = report.targetListing
    val user = report.targetUser
    when {
        report.targetType == ReportTargetType.LISTING && listing != null ->
            RemoteImage(listing.thumbnailUrl, contentDescription = null, modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.small))
        report.targetType == ReportTargetType.USER && user != null -> Avatar(user.avatarUrl, user.displayName, size = 52.dp)
        else -> Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(report.targetType.glyph, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

/** What it is · by whom · why · how old, at a glance. */
@Composable
private fun ReportRow(report: Report, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val owner = report.targetUser?.displayName
    val what = when (report.targetType) {
        ReportTargetType.LISTING -> if (owner != null) "Listing by $owner" else "Listing"
        ReportTargetType.USER -> "Account on ${report.targetUser?.campusDomain ?: "campus"}"
        ReportTargetType.MESSAGE -> if (owner != null) "Message from $owner" else "Message"
        ReportTargetType.ORDER -> "Order"
    }
    val headline = report.targetSummary.ifBlank { "(no summary)" }.let { if (report.targetType == ReportTargetType.MESSAGE) "“$it”" else it }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = Spacing.screen, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ReportThumb(report)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    report.reason.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AgeLabel(if (report.status == ReportStatus.OPEN) report.createdAt else report.resolvedAt ?: report.createdAt, waiting = report.status == ReportStatus.OPEN)
            }
            Text(headline, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$what · reported by ${report.reporter.displayName}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val closedBy = report.resolvedBy?.displayName
            if (report.status != ReportStatus.OPEN || report.openReportsAgainstTargetUser > 1) {
                Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (report.status != ReportStatus.OPEN) {
                        report.resolutionAction?.let { ActionChip(it) } ?: ReportStatusChip(report.status)
                        if (closedBy != null) Text("by $closedBy", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (report.status == ReportStatus.OPEN && report.openReportsAgainstTargetUser > 1) {
                        StatusChip("${report.openReportsAgainstTargetUser} open on ${owner ?: "this user"}", ChipTone.Warning)
                    }
                }
            }
        }
    }
}

/** "9h" for a fresh report; anything waiting a day or more is flagged so the oldest get picked up first. */
@Composable
private fun AgeLabel(epochMillis: Long, waiting: Boolean) {
    val age = rememberTimeFormat().age(epochMillis)
    val overADay = Clock.System.now().toEpochMilliseconds() - epochMillis >= 24 * 3_600_000L
    if (waiting && overADay) StatusChip("Waiting $age", ChipTone.Warning)
    else Text(age, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------------------------------------------------------------------------------------------- detail

private enum class ModDialog { Dismiss, RemoveListing, Warn, Suspend, DirectSuspend, Unsuspend, DirectRemove, Restore }

/**
 * One report: why and by whom, the reported content in context (listing, account, or message with its listing), the
 * user responsible with their open-report count, and the decision split into "no violation" (dismiss, warn) and
 * "take action" (remove listing, suspend). Closed reports show the outcome and follow-up tools (restore, remove,
 * suspend, lift suspension). Every action is confirmed; suspensions pick a length, direct ones need a reason.
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
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<ModDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    CollectEvents(vm.actionDone.flow) { dialog = null }
    val open: (ModDialog) -> Unit = { vm.runner.clearError(); dialog = it }
    val loaded = (state as? UiState.Content)?.value

    Scaffold(
        topBar = {
            AppTopBar(
                loaded?.let { "${it.targetType.label} report" } ?: "Report",
                onBack = { nav.popBackStack() },
                actions = {
                    if (loaded != null && loaded.status == ReportStatus.OPEN && (loaded.targetUser != null || loaded.targetListing?.status == ListingStatus.REMOVED)) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More tools") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (loaded.targetListing?.status == ListingStatus.REMOVED) {
                                    DropdownMenuItem(
                                        text = { Text("Restore listing…") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = { menuOpen = false; open(ModDialog.Restore) },
                                    )
                                }
                                if (loaded.targetUser != null) {
                                    DropdownMenuItem(
                                        text = { Text("Lift suspension…") },
                                        leadingIcon = { Icon(QuadIcons.Undo, contentDescription = null) },
                                        onClick = { menuOpen = false; open(ModDialog.Unsuspend) },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            UiState.Loading -> DetailSkeleton(Modifier.padding(padding))
            is UiState.Error -> ErrorState(s.error, Modifier.fillMaxSize().padding(padding), onRetry = vm::reload)
            is UiState.Content -> PullToRefreshBox(refreshing, onRefresh = vm::pullRefresh, modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl)) {
                    ReportBody(s.value, nav, onOpenDialog = open)
                }
            }
        }
    }

    val report = loaded ?: return
    val listing = report.targetListing
    val targetUser = report.targetUser
    val name = targetUser?.displayName ?: "this user"
    when (dialog) {
        null -> Unit
        ModDialog.Dismiss -> ModerationActionDialog(
            title = "Dismiss this report?",
            message = "No guideline was broken. The report closes, along with other open reports on this ${report.targetType.noun}, and nothing changes for $name.",
            confirmLabel = "Dismiss report",
            destructive = false,
            submitting = action.submitting,
            error = action.error,
            onDismiss = { dialog = null },
            onConfirm = { note, _ -> vm.resolve(ModerationAction.DISMISS, note) },
        )
        ModDialog.RemoveListing -> ModerationActionDialog(
            title = "Remove this listing?",
            message = "It's taken off Quad for everyone and $name can't sell it. Other open reports on it close too. You can restore it later.",
            confirmLabel = "Remove listing",
            submitting = action.submitting,
            error = action.error,
            onDismiss = { dialog = null },
            onConfirm = { note, _ -> vm.resolve(ModerationAction.REMOVE_LISTING, note) },
        )
        ModDialog.Warn -> ModerationActionDialog(
            title = "Warn $name?",
            message = "The warning is recorded in the moderation log and the report closes. $name keeps full access.",
            confirmLabel = "Warn user",
            destructive = false,
            submitting = action.submitting,
            error = action.error,
            onDismiss = { dialog = null },
            onConfirm = { note, _ -> vm.resolve(ModerationAction.WARN_USER, note) },
        )
        ModDialog.Suspend -> ModerationActionDialog(
            title = "Suspend $name?",
            message = "$name is signed out on every device and can't sign in until the suspension ends; they're only told " +
                "it was a guideline violation. Other open reports on them close too.",
            confirmLabel = "Suspend user",
            showDuration = true,
            submitting = action.submitting,
            error = action.error,
            onDismiss = { dialog = null },
            onConfirm = { note, days -> vm.resolve(ModerationAction.SUSPEND_USER, note, days) },
        )
        ModDialog.DirectSuspend -> ModerationActionDialog(
            title = "Suspend $name?",
            message = "$name is signed out on every device and can't sign in until the suspension ends. This report stays as it is.",
            confirmLabel = "Suspend user",
            noteLabel = "Reason (required, staff only)",
            noteRequired = true,
            showDuration = true,
            submitting = action.submitting,
            error = action.error,
            onDismiss = { dialog = null },
            onConfirm = { note, days -> targetUser?.let { vm.suspendUser(it.id, note, days) } },
        )
        ModDialog.Unsuspend -> ModerationActionDialog(
            title = "Lift $name's suspension?",
            message = "If $name is suspended, they can sign in again straight away. Nothing changes for an account that isn't suspended.",
            confirmLabel = "Lift suspension",
            destructive = false,
            submitting = action.submitting,
            error = action.error,
            onDismiss = { dialog = null },
            onConfirm = { note, _ -> targetUser?.let { vm.unsuspendUser(it.id, note) } },
        )
        ModDialog.DirectRemove -> ModerationActionDialog(
            title = "Remove this listing?",
            message = "It's taken off Quad for everyone. Listings that are reserved or sold can't be removed.",
            confirmLabel = "Remove listing",
            submitting = action.submitting,
            error = action.error,
            onDismiss = { dialog = null },
            onConfirm = { note, _ -> listing?.let { vm.removeListing(it.id, note) } },
        )
        ModDialog.Restore -> ModerationActionDialog(
            title = "Restore this listing?",
            message = "It becomes active again and everyone on its campus can see and buy it.",
            confirmLabel = "Restore listing",
            destructive = false,
            submitting = action.submitting,
            error = action.error,
            onDismiss = { dialog = null },
            onConfirm = { note, _ -> listing?.let { vm.restoreListing(it.id, note) } },
        )
    }
}

private val ReportTargetType.noun: String
    get() = when (this) {
        ReportTargetType.LISTING -> "listing"
        ReportTargetType.USER -> "account"
        ReportTargetType.MESSAGE -> "message"
        ReportTargetType.ORDER -> "order"
    }

@Composable
private fun ReportBody(report: Report, nav: NavController, onOpenDialog: (ModDialog) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val open = report.status == ReportStatus.OPEN
    val listing = report.targetListing
    val targetUser = report.targetUser
    val name = targetUser?.displayName ?: "the user"

    // Why, and who said so.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(top = Spacing.md),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(scheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(QuadIcons.Flag, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(report.reason.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${report.targetType.label} report · ${rememberTimeFormat().ago(report.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                ReportStatusChip(report.status)
            }
            QuoteBlock(report.details, "The reporter didn't add any details.")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { nav.navigate(UserProfileRoute(report.reporter.id)) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Avatar(report.reporter.avatarUrl, report.reporter.displayName, size = 28.dp)
                Column(Modifier.weight(1f)) {
                    Text("Reported by ${report.reporter.displayName}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rememberTimeFormat().dateTime(report.createdAt), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Chevron()
            }
        }
    }

    // What was reported, in context.
    ModHeading("Reported ${report.targetType.noun}")
    ContentCard {
        when (report.targetType) {
            ReportTargetType.LISTING ->
                if (listing != null) ListingLine(listing, onClick = { nav.navigate(ListingDetailRoute(listing.id)) })
                else MissingTarget(report.targetSummary, "This listing is no longer available.")
            ReportTargetType.USER ->
                if (targetUser != null) PersonLine(targetUser, onClick = { nav.navigate(UserProfileRoute(targetUser.id)) })
                else MissingTarget(report.targetSummary, "This account no longer exists.")
            ReportTargetType.MESSAGE -> Column(Modifier.padding(vertical = Spacing.md)) {
                Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Avatar(targetUser?.avatarUrl, targetUser?.displayName ?: "?", size = 32.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(targetUser?.displayName ?: "Unknown sender", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Surface(
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
                            color = scheme.surfaceContainerHighest,
                        ) {
                            Text(report.targetSummary.ifBlank { "(empty message)" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
                        }
                    }
                }
                if (listing != null) {
                    Text(
                        "In a chat about",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md),
                    )
                    ListingLine(listing, onClick = { nav.navigate(ListingDetailRoute(listing.id)) })
                }
            }
            ReportTargetType.ORDER -> MissingTarget(report.targetSummary, "Orders aren't reported; see Disputes.")
        }
    }

    // Who is responsible (for a reported account, that's the card above).
    if (targetUser != null) {
        if (report.targetType != ReportTargetType.USER) {
            ModHeading(if (report.targetType == ReportTargetType.LISTING) "Seller" else "Sender")
            ContentCard {
                PersonLine(targetUser, onClick = { nav.navigate(UserProfileRoute(targetUser.id)) })
            }
        }
        val count = report.openReportsAgainstTargetUser
        StatusChip(
            when (count) {
                0 -> "No other open reports against $name"
                1 -> "1 open report against this user"
                else -> "$count open reports against this user"
            },
            if (count > 1) ChipTone.Warning else ChipTone.Neutral,
            Modifier.padding(start = Spacing.screen, top = Spacing.sm),
        )
    }

    if (open) {
        val actions = resolveActionsFor(report)
        ModHeading("Decide")
        Text(
            "Your decision closes every open report on this ${report.targetType.noun}. Notes stay internal.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xs),
        )
        MenuGroup("No violation, or a first warning") {
            MenuRow(Icons.Default.CheckCircle, "Dismiss (no violation)", "The report closes. Nothing changes for $name.", onClick = { onOpenDialog(ModDialog.Dismiss) })
            if (ModerationAction.WARN_USER in actions) {
                MenuDivider()
                MenuRow(Icons.Default.Warning, "Warn user", "Recorded in the log. $name keeps full access.", onClick = { onOpenDialog(ModDialog.Warn) })
            }
        }
        if (ModerationAction.REMOVE_LISTING in actions || ModerationAction.SUSPEND_USER in actions) {
            MenuGroup("Take action", warning = true) {
                if (ModerationAction.REMOVE_LISTING in actions) {
                    val removable = listing == null || canRemoveListing(listing.status)
                    MenuRow(
                        Icons.Default.Delete,
                        "Remove listing",
                        if (removable) "Taken off Quad for everyone. You can restore it later."
                        else "Can't be removed while it's ${listing?.status?.label?.lowercase()}.",
                        onClick = if (removable) ({ onOpenDialog(ModDialog.RemoveListing) }) else null,
                        destructive = removable,
                    )
                }
                if (ModerationAction.SUSPEND_USER in actions) {
                    if (ModerationAction.REMOVE_LISTING in actions) MenuDivider()
                    MenuRow(QuadIcons.Gavel, "Suspend user", "$name is signed out everywhere until it ends.", onClick = { onOpenDialog(ModDialog.Suspend) }, destructive = true)
                }
            }
        }
    } else {
        ModHeading("Outcome")
        ContentCard {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                report.resolutionAction?.let { ActionChip(it) } ?: ReportStatusChip(report.status)
                val times = rememberTimeFormat()
                val by = listOfNotNull(report.resolvedBy?.displayName?.let { "by $it" }, report.resolvedAt?.let(times::dateTime))
                if (by.isNotEmpty()) Text(by.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                QuoteBlock(report.resolutionNote, "No note was left.")
            }
        }

        // Follow-up tools, independent of this (closed) report.
        val showRestore = listing != null && canRestoreListing(listing.status)
        val showRemove = listing != null && canRemoveListing(listing.status)
        if (showRestore || showRemove || targetUser != null) {
            MenuGroup("Follow up") {
                val rows = buildList<@Composable () -> Unit> {
                    if (showRestore) add { MenuRow(Icons.Default.Refresh, "Restore listing", "Make it active and visible again", onClick = { onOpenDialog(ModDialog.Restore) }) }
                    if (showRemove) add { MenuRow(Icons.Default.Delete, "Remove listing", "Take it off Quad", onClick = { onOpenDialog(ModDialog.DirectRemove) }, destructive = true) }
                    if (targetUser != null) {
                        add { MenuRow(QuadIcons.Undo, "Lift suspension", "If $name is suspended, let them sign in again", onClick = { onOpenDialog(ModDialog.Unsuspend) }) }
                        add { MenuRow(QuadIcons.Gavel, "Suspend user", "Needs a reason; this report stays closed", onClick = { onOpenDialog(ModDialog.DirectSuspend) }, destructive = true) }
                    }
                }
                rows.forEachIndexed { i, row ->
                    if (i > 0) MenuDivider()
                    row()
                }
            }
        }
    }
}

@Composable
private fun ContentCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
    ) {
        Column(Modifier.padding(vertical = Spacing.xs)) { content() }
    }
}

@Composable
private fun MissingTarget(summary: String, note: String) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyLarge)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** First-load stand-in for a report or dispute: a header card and two content cards. */
@Composable
internal fun DetailSkeleton(modifier: Modifier = Modifier) {
    ShimmerGroup {
        Column(modifier.fillMaxWidth().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SkeletonBox(Modifier.fillMaxWidth().height(150.dp), MaterialTheme.shapes.medium)
            SkeletonBox(Modifier.width(120.dp).height(14.dp))
            SkeletonBox(Modifier.fillMaxWidth().height(72.dp), MaterialTheme.shapes.medium)
            SkeletonBox(Modifier.width(120.dp).height(14.dp))
            SkeletonBox(Modifier.fillMaxWidth().height(72.dp), MaterialTheme.shapes.medium)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- log

/** `api.moderationLog()`, newest first, grouped by day: who did what to which kind of thing, when, and their note. */
@Composable
fun ModerationLogScreen(nav: NavController) {
    ModeratorGate("Moderation log", nav) {
        val vm = koinViewModel<ModerationLogViewModel>()
        val paged by vm.entries.state.collectAsStateWithLifecycle()
        val times = rememberTimeFormat()
        Scaffold(
            topBar = {
                AppTopBar("Moderation log", onBack = { nav.popBackStack() }, actions = { ModerationToolLinks(nav, current = ModerationLogRoute) })
            },
        ) { padding ->
            PagedLazyColumn(
                vm.entries,
                key = { it.id },
                modifier = Modifier.padding(padding),
                empty = { EmptyState("No moderation actions yet", message = "Every decision on a report, dispute or staff role is recorded here.", icon = QuadIcons.History) },
            ) { entry ->
                val index = paged.items.indexOfFirst { it.id == entry.id }
                if (index <= 0 || !times.sameDay(paged.items[index - 1].createdAt, entry.createdAt)) {
                    Text(
                        times.dayLabel(entry.createdAt),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = Spacing.screen, top = Spacing.lg, bottom = Spacing.xs),
                    )
                }
                LogRow(entry, onClick = logTarget(entry, nav))
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

private val ModerationLogEntry.targetText: String
    get() {
        val noun = targetType.noun
        val article = if (noun.first() in "aeiou") "an" else "a"
        return when {
            // The target is what the action hit (a warning hits the account even when a listing was reported).
            reportId != null -> "on $article $noun · from a report"
            targetType == ReportTargetType.ORDER -> "on a disputed order"
            else -> "on $article $noun"
        }
    }

/** The note as staff read it; role changes are logged as "USER -> MODERATOR". */
private val ModerationLogEntry.displayNote: String
    get() {
        if (action != ModerationAction.CHANGE_ROLE) return note
        val roles = note.split("->").map { it.trim() }
        val from = roles.getOrNull(0)?.let { r -> UserRole.entries.firstOrNull { it.name == r } }
        val to = roles.getOrNull(1)?.let { r -> UserRole.entries.firstOrNull { it.name == r } }
        return if (from != null && to != null) "${from.label} → ${to.label}" else note
    }

@Composable
private fun LogRow(entry: ModerationLogEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = Spacing.screen, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Avatar(entry.moderator.avatarUrl, entry.moderator.displayName, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.moderator.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(rememberTimeFormat().time(entry.createdAt), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ActionChip(entry.action)
                Text(entry.targetText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.note.isNotBlank()) QuoteBlock(entry.displayNote, "", Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.bodyMedium)
        }
        Chevron()
    }
}

