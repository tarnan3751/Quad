package com.campusmarket.app.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.BuildInfo
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.isModerator
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.moderation.ActionRunner
import com.campusmarket.app.feature.moderation.ModerationActionDialog
import com.campusmarket.app.feature.moderation.ReportSheet
import com.campusmarket.app.feature.orders.OrdersRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.platform.rememberImagePicker
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTab
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.ListingCard
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.PagedLazyGrid
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.RatingStars
import com.campusmarket.app.ui.components.RatingSummary
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.SectionHeader
import com.campusmarket.app.ui.components.ServerSettingsDialog
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.components.UserRow
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Limits
import com.campusmarket.core.Rating
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.SuspendUserRequest
import com.campusmarket.core.UserPublic
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------------- edit profile

/**
 * Edit display name / bio (Limits.DISPLAY_NAME_*, BIO_MAX) and avatar (picker -> `api.uploadPhoto` ->
 * `UpdateProfileRequest(avatarPhotoId)`); then `SessionManager.updateUser(result)`.
 */
@Composable
fun EditProfileScreen(nav: NavController) {
    val vm = koinViewModel<EditProfileViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    CollectEvents(vm.saved.flow) { nav.popBackStack() }

    val picker = rememberImagePicker(maxItems = 1, onError = { vm.onAvatarPickFailed() }) { vm.onAvatarPicked(it) }
    val name = state.displayName.ifBlank { LocalCurrentUser.current.displayName }
    val goBack: () -> Unit = { if (state.dirty && !state.saving) confirmDiscard = true else nav.popBackStack() }

    Scaffold(topBar = { AppTopBar("Edit profile", onBack = goBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Box(contentAlignment = Alignment.Center) {
                    Avatar(state.avatarUrl, name, size = 96.dp)
                    if (state.uploadingAvatar) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SecondaryButton(
                        if (state.hasAvatar) "Change photo" else "Add photo",
                        onClick = picker::launch,
                        enabled = !state.busy,
                    )
                    if (state.hasAvatar) {
                        TextButton(onClick = vm::removeAvatar, enabled = !state.busy) { Text("Remove photo") }
                    }
                }
            }
            AppTextField(
                value = state.displayName,
                onValueChange = vm::onDisplayName,
                label = "Display name",
                error = state.fieldErrors["displayName"],
                maxLength = Limits.DISPLAY_NAME_MAX,
                enabled = !state.saving,
            )
            AppTextField(
                value = state.bio,
                onValueChange = vm::onBio,
                label = "Bio",
                placeholder = "Tell other students a little about yourself",
                error = state.fieldErrors["bio"],
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                maxLength = Limits.BIO_MAX,
                enabled = !state.saving,
            )
            InlineError(state.formError, Modifier.fillMaxWidth())
            PrimaryButton(
                "Save changes",
                onClick = vm::save,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.dirty && !state.uploadingAvatar,
                loading = state.saving,
            )
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your edits haven't been saved.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = { confirmDiscard = false; nav.popBackStack() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------------- public profile

private enum class ModUserAction { Suspend, Unsuspend }

/**
 * Public profile: `api.user(id)`, `api.userRatings(id)`, `api.userListings(id)`; block (`BlockUserDialog`) / report
 * from the overflow menu; moderators also get suspend / unsuspend there.
 */
@Composable
fun UserProfileScreen(route: UserProfileRoute, nav: NavController) {
    val vm = koinViewModel<UserProfileViewModel> { parametersOf(route) }
    val userState by vm.user.collectAsStateWithLifecycle()
    val me = LocalCurrentUser.current
    val isMe = me.id == route.userId
    val api: ApiClient = koinInject()
    val scope = rememberCoroutineScope()
    val modRunner = remember { ActionRunner(scope) }
    val modState by modRunner.state.collectAsStateWithLifecycle()
    val messages: UserMessages = koinInject()

    var menuOpen by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var modAction by remember { mutableStateOf<ModUserAction?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val loaded = (userState as? com.campusmarket.app.ui.state.UiState.Content)?.value

    Scaffold(
        topBar = {
            AppTopBar(
                title = loaded?.takeUnless { it.isDeleted }?.displayName ?: "Profile",
                onBack = { nav.popBackStack() },
                actions = {
                    if (loaded != null && isMe) {
                        IconButton(onClick = { nav.navigate(EditProfileRoute) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit profile")
                        }
                    } else if (loaded != null && !loaded.isDeleted) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (loaded.blockedByMe) "Unblock user" else "Block user") },
                                    onClick = { menuOpen = false; blocking = true },
                                )
                                DropdownMenuItem(text = { Text("Report user") }, onClick = { menuOpen = false; reporting = true })
                                if (me.isModerator) {
                                    DropdownMenuItem(
                                        text = { Text("Suspend user (moderator)") },
                                        onClick = { menuOpen = false; modRunner.clearError(); modAction = ModUserAction.Suspend },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Lift suspension (moderator)") },
                                        onClick = { menuOpen = false; modRunner.clearError(); modAction = ModUserAction.Unsuspend },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            UiStateContent(userState, onRetry = vm::reload) { user ->
                val header: @Composable () -> Unit = {
                    PublicProfileHeader(user, isMe, onUnblock = { blocking = true })
                }
                if (user.isDeleted) {
                    Column(Modifier.fillMaxSize()) {
                        header()
                        EmptyState(
                            "This account has been deleted",
                            message = "Their listings are gone, but ratings they received stay on the orders they came from.",
                            icon = Icons.Default.Person,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                } else if (tab == 0) {
                    PagedLazyGrid(
                        vm.listings,
                        key = { it.id },
                        // No top padding and a full-bleed header, so the header and tab row sit exactly where they do
                        // on the Ratings tab (a list without side padding) instead of jumping when switching tabs.
                        contentPadding = PaddingValues(start = GridSidePadding, end = GridSidePadding, bottom = Spacing.md),
                        header = {
                            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.ignoreHorizontalPadding(GridSidePadding)) {
                                    ProfileHeaderWithTabs(user, isMe, tab, { tab = it }, { blocking = true })
                                }
                            }
                        },
                        empty = {
                            EmptyState(
                                if (isMe) "You have no active listings" else "No active listings",
                                message = if (isMe) "Listings you post will show up here." else "${user.displayName} isn't selling anything right now.",
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                            )
                        },
                    ) { listing ->
                        ListingCard(listing, onClick = { nav.navigate(ListingDetailRoute(listing.id)) })
                    }
                } else {
                    PagedLazyColumn(
                        vm.ratings,
                        key = { it.id },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.lg),
                        header = {
                            item(key = "header") { ProfileHeaderWithTabs(user, isMe, tab, { tab = it }, { blocking = true }) }
                        },
                        empty = {
                            EmptyState(
                                "No ratings yet",
                                message = "Ratings appear after an order is completed.",
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                            )
                        },
                    ) { rating ->
                        RatingRow(rating, onClickRater = { nav.navigate(UserProfileRoute(rating.rater.id)) })
                        HorizontalDivider(Modifier.padding(horizontal = Spacing.screen))
                    }
                }
            }
        }
    }

    val user = loaded
    if (blocking && user != null) {
        BlockUserDialog(
            user,
            onDismiss = { blocking = false },
            onDone = { nowBlocked -> blocking = false; vm.onBlockChanged(nowBlocked) },
        )
    }
    if (reporting && user != null) {
        ReportSheet(
            ReportTargetType.USER,
            user.id,
            onDismiss = { reporting = false },
            onReported = { reporting = false; vm.onReported() },
        )
    }
    if (user != null && modAction == ModUserAction.Suspend) {
        ModerationActionDialog(
            title = "Suspend ${user.displayName}?",
            message = "They are signed out everywhere and can't sign in until the suspension ends. Moderators can only suspend students; admins can also suspend moderators.",
            confirmLabel = "Suspend",
            noteLabel = "Reason (required, shown in the log)",
            noteRequired = true,
            showDuration = true,
            submitting = modState.submitting,
            error = modState.error,
            onDismiss = { modAction = null },
            onConfirm = { note, days ->
                modRunner.run(
                    block = { api.suspendUser(user.id, SuspendUserRequest(note, days)) },
                    onSuccess = { modAction = null; messages.show("${user.displayName} suspended") },
                )
            },
        )
    }
    if (user != null && modAction == ModUserAction.Unsuspend) {
        ModerationActionDialog(
            title = "Lift suspension for ${user.displayName}?",
            message = "They will be able to sign in again.",
            confirmLabel = "Lift suspension",
            destructive = false,
            submitting = modState.submitting,
            error = modState.error,
            onDismiss = { modAction = null },
            onConfirm = { note, _ ->
                modRunner.run(
                    block = { api.unsuspendUser(user.id, note) },
                    onSuccess = { modAction = null; messages.show("Suspension lifted for ${user.displayName}") },
                )
            },
        )
    }
}

private val GridSidePadding = Spacing.md

/** Lets a full-span grid item extend over the grid's horizontal content padding ([padding] on each side). */
private fun Modifier.ignoreHorizontalPadding(padding: Dp): Modifier = layout { measurable, constraints ->
    val extra = padding.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun ProfileHeaderWithTabs(user: UserPublic, isMe: Boolean, tab: Int, onTab: (Int) -> Unit, onUnblock: () -> Unit) {
    Column {
        PublicProfileHeader(user, isMe, onUnblock)
        TabRow(selectedTabIndex = tab) {
            AppTab(selected = tab == 0, onClick = { onTab(0) }, text = "Listings")
            AppTab(
                selected = tab == 1,
                onClick = { onTab(1) },
                text = if (user.ratingCount > 0) "Ratings (${user.ratingCount})" else "Ratings",
            )
        }
    }
}

@Composable
private fun PublicProfileHeader(user: UserPublic, isMe: Boolean, onUnblock: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Avatar(user.avatarUrl, user.displayName, size = 80.dp)
        Text(
            if (isMe) "${user.displayName} (you)" else user.displayName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        if (!user.isDeleted) {
            Text(
                "${user.campusDomain} · ${Format.memberSince(user.joinedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (user.ratingCount > 0) RatingSummary(user.ratingAverage, user.ratingCount)
            else Text("No ratings yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (user.bio.isNotBlank()) {
                Text(user.bio, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.sm))
            }
            if (user.blockedByMe) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = Spacing.sm),
                ) {
                    Row(Modifier.padding(start = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Text("You blocked this user", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onUnblock) { Text("Unblock") }
                    }
                }
            }
        }
    }
}

/** One received rating: rater, stars, comment, item title, date. */
@Composable
private fun RatingRow(rating: Rating, onClickRater: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(Modifier.clickableNoRipple(onClickRater)) { Avatar(rating.rater.avatarUrl, rating.rater.displayName, size = 40.dp) }
            Column(Modifier.weight(1f)) {
                Text(rating.rater.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RatingStars(rating.stars.toDouble())
                    Text(
                        Format.date(rating.createdAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (rating.comment.isNotBlank()) Text(rating.comment, style = MaterialTheme.typography.bodyMedium)
        rating.listingTitle?.let {
            Text(
                "For: $it",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

// ---------------------------------------------------------------------------------------------------- settings

/**
 * Settings: account info, blocked users, sign out (confirm), two-step account deletion (409 "open orders" handled),
 * policy links (server-rendered /legal pages in the browser), about, and in debug builds the API server override.
 */
@Composable
fun SettingsScreen(nav: NavController) {
    val vm = koinViewModel<SettingsViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val server: ServerConfig = koinInject()
    val messages: UserMessages = koinInject()
    val baseUrl by server.baseUrl.collectAsStateWithLifecycle()
    val user = LocalCurrentUser.current
    var showServer by remember { mutableStateOf(false) }
    val links: ExternalLinks = koinInject()
    val openPage = { path: String -> if (!links.open(server.url(path))) messages.show("Couldn't open the page.") }

    Scaffold(topBar = { AppTopBar("Settings", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Account")
            MenuRow(Icons.Default.AccountCircle, user.displayName, "Edit your name, bio and photo", onClick = { nav.navigate(EditProfileRoute) })
            MenuRow(Icons.Default.Info, user.email, "${user.campusDomain} · ${user.role.label}", onClick = {}, trailing = null)
            MenuRow(Icons.Default.Lock, "Blocked users", "People you've blocked", onClick = { nav.navigate(BlockedUsersRoute) })

            SectionHeader("About")
            MenuRow(Icons.Default.Info, "Terms of service", onClick = { openPage(ApiRoutes.LEGAL_TERMS) })
            MenuRow(Icons.Default.Info, "Privacy policy", onClick = { openPage(ApiRoutes.LEGAL_PRIVACY) })
            MenuRow(Icons.Default.Info, "Community guidelines", onClick = { openPage(ApiRoutes.LEGAL_GUIDELINES) })
            MenuRow(Icons.Default.Info, "Quad", "Version ${BuildInfo.VERSION_NAME}", onClick = {}, trailing = null)

            if (server.canOverride) {
                SectionHeader("Developer")
                MenuRow(
                    Icons.Default.Info,
                    "API server",
                    if (server.isOverridden) "$baseUrl (override)" else baseUrl,
                    onClick = { showServer = true },
                )
            }

            SectionHeader("Session")
            MenuRow(Icons.AutoMirrored.Filled.ExitToApp, "Sign out", onClick = vm::askSignOut, trailing = null)
            MenuRow(Icons.Default.Warning, "Delete account", "Permanently delete your account", onClick = vm::startDelete, trailing = null, destructive = true)
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (showServer) ServerSettingsDialog(onDismiss = { showServer = false })

    if (state.confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "You'll need to sign in again to use Quad on this device.",
            confirmLabel = "Sign out",
            onConfirm = vm::signOut,
            onDismiss = vm::cancelSignOut,
        )
    }
    when (state.deleteStep) {
        DeleteStep.None -> Unit
        DeleteStep.Warn -> AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text("Delete your account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("This can't be undone.")
                    Text("• Your profile, name and photo are removed.")
                    Text("• Your active listings are taken down.")
                    Text("• You're signed out on every device.")
                    Text("• You can't delete your account while you have orders awaiting payment or handoff.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = vm::continueDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("Cancel") } },
        )
        DeleteStep.Confirm -> AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text("Confirm deletion") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Type ${SettingsState.DELETE_KEYWORD} to permanently delete ${user.email}.")
                    AppTextField(
                        value = state.deleteText,
                        onValueChange = vm::onDeleteText,
                        label = "Type ${SettingsState.DELETE_KEYWORD}",
                        enabled = !state.deleting,
                    )
                    InlineError(state.deleteError)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = vm::confirmDelete,
                    enabled = state.deleteConfirmed && !state.deleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    if (state.deleting) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Delete account")
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelDelete, enabled = !state.deleting) { Text("Cancel") } },
        )
        DeleteStep.BlockedByOrders -> AlertDialog(
            onDismissRequest = vm::dismissBlocked,
            title = { Text("You have open orders") },
            text = {
                Text(
                    "Orders that are awaiting payment or handoff have to be completed or cancelled before you can delete your account. " +
                        "Finish or cancel them, then try again.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissBlocked(); nav.navigate(OrdersRoute()) }) { Text("View orders") }
            },
            dismissButton = { TextButton(onClick = vm::dismissBlocked) { Text("OK") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------------- blocked users

/** `api.myBlocks()` with Unblock (`api.unblockUser`). Tap a row for the public profile. */
@Composable
fun BlockedUsersScreen(nav: NavController) {
    val vm = koinViewModel<BlockedUsersViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val unblocking by vm.unblocking.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose {}
    }

    Scaffold(topBar = { AppTopBar("Blocked users", onBack = { nav.popBackStack() }) }) { padding ->
        UiStateContent(state, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { users ->
            if (users.isEmpty()) {
                EmptyState(
                    "No blocked users",
                    Modifier.fillMaxSize().padding(padding),
                    message = "People you block can't message you or buy from you, and you won't see their listings.",
                    icon = Icons.Default.Lock,
                )
            } else {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                    users.forEach { user ->
                        UserRow(
                            user,
                            onClick = { nav.navigate(UserProfileRoute(user.id)) },
                            supporting = user.campusDomain,
                            trailing = {
                                TextButton(onClick = { vm.unblock(user) }, enabled = user.id !in unblocking) { Text("Unblock") }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
