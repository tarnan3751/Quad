package com.campusmarket.app.feature.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.BuildInfo
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.isModerator
import com.campusmarket.app.feature.chat.MessageSellerRoute
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.listing.ListingTile
import com.campusmarket.app.feature.listing.PagedListingGrid
import com.campusmarket.app.feature.moderation.ActionRunner
import com.campusmarket.app.feature.moderation.ModerationActionDialog
import com.campusmarket.app.feature.moderation.ReportSheet
import com.campusmarket.app.feature.orders.OrdersRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.platform.rememberImagePicker
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTab
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ErrorState
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PagedLazyColumn
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.RatingStars
import com.campusmarket.app.ui.components.ServerSettingsDialog
import com.campusmarket.app.ui.components.avatarImageUrl
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Rating
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.SuspendUserRequest
import com.campusmarket.core.UserPublic
import com.campusmarket.core.UserRole
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------------------------------------------------------------------------------------------- edit profile

/**
 * Edit display name / bio (Limits.DISPLAY_NAME_*, BIO_MAX, checked as you type) and avatar (tap the photo: picker ->
 * `api.uploadPhoto` -> `UpdateProfileRequest(avatarPhotoId)`); then `SessionManager.updateUser(result)`. Leaving with
 * unsaved edits (up arrow or system back) asks first.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditProfileScreen(nav: NavController) {
    val vm = koinViewModel<EditProfileViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val me = LocalCurrentUser.current
    var confirmDiscard by remember { mutableStateOf(false) }
    CollectEvents(vm.saved.flow) { nav.popBackStack() }

    val picker = rememberImagePicker(maxItems = 1, onError = { vm.onAvatarPickFailed() }) { vm.onAvatarPicked(it) }
    val name = state.displayName.ifBlank { me.displayName }
    val unsaved = state.dirty && !state.saving
    val goBack: () -> Unit = { if (unsaved) confirmDiscard = true else nav.popBackStack() }
    BackHandler(enabled = unsaved) { confirmDiscard = true }

    Scaffold(topBar = { AppTopBar("Edit profile", onBack = goBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Box(
                    Modifier.size(120.dp)
                        .clickable(enabled = !state.busy, role = Role.Button, onClick = picker::launch)
                        .semantics { contentDescription = if (state.hasAvatar) "Change profile photo" else "Add a profile photo" },
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(state.avatarUrl, name, size = 112.dp)
                    if (state.uploadingAvatar) {
                        Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.surface)
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomEnd).size(36.dp)
                            .border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Glyphs.Camera, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(onClick = picker::launch, enabled = !state.busy) { Text(if (state.hasAvatar) "Change photo" else "Add photo") }
                    if (state.hasAvatar) {
                        TextButton(onClick = vm::removeAvatar, enabled = !state.busy) { Text("Remove") }
                    }
                }
                Text(
                    if (state.uploadingAvatar) "Uploading photo…" else "A clear photo of your face helps buyers recognise you at pickup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            AppTextField(
                value = state.displayName,
                onValueChange = vm::onDisplayName,
                label = "Display name",
                error = state.fieldErrors["displayName"],
                supportingText = "Shown on your listings, chats and ratings",
                maxLength = Limits.DISPLAY_NAME_MAX,
                enabled = !state.saving,
            )
            AppTextField(
                value = state.bio,
                onValueChange = vm::onBio,
                label = "Bio (optional)",
                placeholder = "Year, major, where you like to meet…",
                error = state.fieldErrors["bio"],
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                maxLength = Limits.BIO_MAX,
                enabled = !state.saving,
            )
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.School, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(
                        "${me.email} · ${me.campusDomain}\nYour email and campus come from your school account and can't be changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InlineError(state.formError, Modifier.fillMaxWidth())
            PrimaryButton(
                "Save changes",
                onClick = vm::save,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave,
                loading = state.saving,
            )
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard your changes?",
            message = "Your new name, bio or photo won't be saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = { confirmDiscard = false; nav.popBackStack() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------------- public profile

private enum class ModUserAction { Suspend, Unsuspend }

/**
 * Public profile: `api.user(id)`, `api.userListings(id)` (grid) and `api.userRatings(id)` (summary with a star
 * breakdown, then each rating). Other people's profiles have Message (pick one of their listings), Report and
 * Block / Unblock; moderators also get suspend / lift suspension in the overflow menu.
 */
@Composable
fun UserProfileScreen(route: UserProfileRoute, nav: NavController) {
    val vm = koinViewModel<UserProfileViewModel> { parametersOf(route) }
    val userState by vm.user.collectAsStateWithLifecycle()
    val listings by vm.listings.state.collectAsStateWithLifecycle()
    val ratings by vm.ratings.state.collectAsStateWithLifecycle()
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
    var messaging by remember { mutableStateOf(false) }
    var modAction by remember { mutableStateOf<ModUserAction?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(if (route.showRatings) 1 else 0) }

    val loaded = (userState as? UiState.Content)?.value
    val listingCount = if (listings.loaded) "${listings.items.size}${if (listings.endReached) "" else "+"}" else null
    val actions = ProfileActions(
        onMessage = { messaging = true },
        onReport = { reporting = true },
        onBlock = { blocking = true },
        onRatings = { tab = 1 },
        onListings = { tab = 0 },
        onEdit = { nav.navigate(EditProfileRoute) },
    )

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
                                    text = { Text("Report ${loaded.displayName}") },
                                    leadingIcon = { Icon(Glyphs.Flag, contentDescription = null) },
                                    onClick = { menuOpen = false; reporting = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (loaded.blockedByMe) "Unblock ${loaded.displayName}" else "Block ${loaded.displayName}") },
                                    leadingIcon = { Icon(Glyphs.Block, contentDescription = null) },
                                    onClick = { menuOpen = false; blocking = true },
                                )
                                if (me.isModerator) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Suspend account…") },
                                        leadingIcon = { Icon(Glyphs.Gavel, contentDescription = null) },
                                        onClick = { menuOpen = false; modRunner.clearError(); modAction = ModUserAction.Suspend },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Lift suspension…") },
                                        leadingIcon = { Icon(Glyphs.Undo, contentDescription = null) },
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
            when (val s = userState) {
                UiState.Loading -> ProfileHeaderSkeleton()
                is UiState.Error -> ErrorState(s.error, onRetry = vm::reload)
                is UiState.Content -> {
                    val user = s.value
                    val header: @Composable () -> Unit = {
                        Column {
                            PublicProfileHeader(user, isMe, listingCount, actions)
                            if (!user.isDeleted) ProfileTabs(user, tab, listingCount) { tab = it }
                        }
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
                        // The storefront's grid and tile, so listings look the same here as in Browse (even rows).
                        PagedListingGrid(
                            vm.listings,
                            // No top padding and a full-bleed header, so the header and tab row sit exactly where they
                            // do on the Ratings tab (a list without side padding) instead of jumping when switching tabs.
                            contentPadding = PaddingValues(start = GridSidePadding, end = GridSidePadding, bottom = Spacing.lg),
                            endOfList = null,
                            header = {
                                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.ignoreHorizontalPadding(GridSidePadding)) { header() }
                                }
                            },
                            empty = {
                                EmptyState(
                                    if (isMe) "You have no active listings" else "Nothing for sale right now",
                                    message = if (isMe) "Listings you post show up here for everyone on ${user.campusDomain}."
                                    else "${user.displayName} isn't selling anything at the moment. Check back later.",
                                    icon = Glyphs.Tag,
                                    modifier = Modifier.fillMaxWidth().height(240.dp),
                                )
                            },
                        ) { listing ->
                            ListingTile(listing, onClick = { nav.navigate(ListingDetailRoute(listing.id)) })
                        }
                    } else {
                        PagedLazyColumn(
                            vm.ratings,
                            key = { it.id },
                            contentPadding = PaddingValues(bottom = Spacing.lg),
                            header = {
                                item(key = "header") { header() }
                                if (ratings.items.isNotEmpty()) {
                                    item(key = "summary") {
                                        RatingsOverview(user, RatingBreakdown.of(ratings.items, complete = ratings.endReached))
                                    }
                                }
                            },
                            empty = {
                                EmptyState(
                                    "No ratings yet",
                                    message = if (isMe) "After a sale or purchase is completed, the other person can rate you here."
                                    else "${user.displayName} hasn't been rated yet. Ratings appear after a completed order.",
                                    icon = Icons.Default.Star,
                                    modifier = Modifier.fillMaxWidth().height(240.dp),
                                )
                            },
                        ) { rating ->
                            RatingRow(rating, onClickRater = { nav.navigate(UserProfileRoute(rating.rater.id)) })
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.screen), color = MaterialTheme.colorScheme.outlineVariant)
                        }
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
            onReported = { reporting = false },
        )
    }
    if (messaging && user != null) {
        MessageAboutSheet(
            user,
            listings.items,
            loading = !listings.loaded,
            onDismiss = { messaging = false },
            onPick = { listing -> messaging = false; nav.navigate(MessageSellerRoute(listing.id)) },
        )
    }
    if (user != null && modAction == ModUserAction.Suspend) {
        ModerationActionDialog(
            title = "Suspend ${user.displayName}?",
            message = "They're signed out on every device and can't sign in until the suspension ends; they only see " +
                "\"Violation of community guidelines\". Their paid sales go on hold for review. Moderators can suspend students; admins can also suspend moderators.",
            confirmLabel = "Suspend",
            noteLabel = "Reason (required, staff only)",
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
            title = "Lift ${user.displayName}'s suspension?",
            message = "If they're suspended, they can sign in again straight away. Nothing changes for an account that isn't suspended.",
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

private class ProfileActions(
    val onMessage: () -> Unit,
    val onReport: () -> Unit,
    val onBlock: () -> Unit,
    val onRatings: () -> Unit,
    val onListings: () -> Unit,
    val onEdit: () -> Unit,
)

private val GridSidePadding = Spacing.screen

/** Lets a full-span grid item extend over the grid's horizontal content padding ([padding] on each side). */
private fun Modifier.ignoreHorizontalPadding(padding: Dp): Modifier = layout { measurable, constraints ->
    val extra = padding.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun ProfileTabs(user: UserPublic, tab: Int, listingCount: String?, onTab: (Int) -> Unit) {
    TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
        AppTab(selected = tab == 0, onClick = { onTab(0) }, text = if (listingCount != null) "Listings ($listingCount)" else "Listings")
        AppTab(
            selected = tab == 1,
            onClick = { onTab(1) },
            text = if (user.ratingCount > 0) "Ratings (${user.ratingCount})" else "Ratings",
        )
    }
}

@Composable
private fun PublicProfileHeader(user: UserPublic, isMe: Boolean, listingCount: String?, actions: ProfileActions) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(top = Spacing.md, bottom = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (user.isDeleted) {
            // No initials on a colour for an account that no longer exists.
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            }
        } else {
            Avatar(user.avatarImageUrl, user.displayName, size = 96.dp)
        }
        Text(
            user.displayName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        if (user.isDeleted) return@Column
        CampusLine(user.campusDomain, user.joinedAt)
        if (user.bio.isNotBlank()) {
            Text(user.bio, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xs))
        }
        Spacer(Modifier.height(Spacing.sm))
        StatsCard(
            listOf(
                Stat(listingCount ?: "", if (listingCount == "1") "Listing for sale" else "Listings for sale", onClick = actions.onListings),
                ratingStat(user.ratingAverage, user.ratingCount, actions.onRatings),
            ),
            loading = listingCount == null,
        )
        Spacer(Modifier.height(Spacing.xs))
        when {
            isMe -> Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        "This is how other students see you",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm),
                    )
                    TextButton(onClick = actions.onEdit) { Text("Edit") }
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ActionTile(Glyphs.ChatBubble, "Message", actions.onMessage, emphasized = true, enabled = !user.blockedByMe, modifier = Modifier.weight(1f))
                    ActionTile(Glyphs.Flag, "Report", actions.onReport, modifier = Modifier.weight(1f))
                    ActionTile(Glyphs.Block, if (user.blockedByMe) "Unblock" else "Block", actions.onBlock, modifier = Modifier.weight(1f))
                }
                if (user.blockedByMe) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Glyphs.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                            Text(
                                "You blocked ${user.displayName}. You can't message or buy from each other, and their listings are hidden from you.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Square-ish button with an icon over a short label (Message / Report / Block). */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (emphasized) scheme.primaryContainer else scheme.surfaceContainerLow
    val content = if (emphasized) scheme.onPrimaryContainer else scheme.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = if (enabled) content else content.copy(alpha = 0.4f),
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Column(
            Modifier.padding(vertical = Spacing.md, horizontal = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** First-load stand-in for the profile header, in its shape. */
@Composable
private fun ProfileHeaderSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(Spacing.screen).semantics { contentDescription = "Loading profile" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SkeletonBlock(Modifier.size(96.dp), CircleShape)
        SkeletonBlock(Modifier.width(180.dp).height(24.dp))
        SkeletonBlock(Modifier.width(140.dp).height(14.dp))
        SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(14.dp))
        Spacer(Modifier.height(Spacing.sm))
        SkeletonBlock(Modifier.fillMaxWidth().height(72.dp), MaterialTheme.shapes.medium)
        SkeletonBlock(Modifier.fillMaxWidth().height(64.dp), MaterialTheme.shapes.medium)
    }
}

/** Average, stars, count and a 5-to-1 star breakdown above the list of ratings. */
@Composable
private fun RatingsOverview(user: UserPublic, breakdown: RatingBreakdown) {
    val average = user.ratingAverage ?: return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
    ) {
        Row(Modifier.padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.rating(average).orEmpty(), style = MaterialTheme.typography.displaySmall)
                RatingStars(average, starSize = 14.dp)
                Text(
                    if (user.ratingCount == 1) "1 rating" else "${user.ratingCount} ratings",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (stars in 5 downTo 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.semantics { contentDescription = "$stars stars: ${breakdown.counts[stars - 1]}" },
                    ) {
                        Text("$stars", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(10.dp))
                        LinearProgressIndicator(
                            progress = { breakdown.share(stars) },
                            modifier = Modifier.weight(1f).height(6.dp),
                            color = CampusTheme.colors.star,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            drawStopIndicator = {},
                            gapSize = 0.dp,
                        )
                        Text(
                            "${breakdown.counts[stars - 1]}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(24.dp),
                        )
                    }
                }
                if (!breakdown.complete) {
                    Text(
                        "Bars show the latest ${breakdown.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One received rating: who (tap for their profile), stars, when, the comment and what the order was. */
@Composable
private fun RatingRow(rating: Rating, onClickRater: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClickRater)
                .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Avatar(rating.rater.avatarImageUrl, rating.rater.displayName, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(rating.rater.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RatingStars(rating.stars.toDouble(), modifier = Modifier.semantics { contentDescription = "${rating.stars} out of 5 stars" })
                    Text(ago(rating.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(start = Spacing.screen + 40.dp + Spacing.md, end = Spacing.screen, bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (rating.comment.isNotBlank()) Text(rating.comment, style = MaterialTheme.typography.bodyMedium)
            rating.listingTitle?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Icon(Glyphs.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "5m ago", "3h ago", "2d ago", then a date. */
internal fun ago(epochMillis: Long): String {
    val r = Format.relativeTime(epochMillis)
    return if (r.isNotEmpty() && r.first().isDigit() && r.last() in "mhd" && ' ' !in r) "$r ago" else r
}

/** Conversations are about a listing, so "Message" asks which of their listings it's about. */
@Composable
private fun MessageAboutSheet(
    user: UserPublic,
    listings: List<ListingSummary>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPick: (ListingSummary) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("Message ${user.displayName}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.screen))
            Text(
                "Chats on Quad are about a listing. Which one are you asking about?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen).padding(bottom = Spacing.sm),
            )
            when {
                loading -> SkeletonRows(rows = 3, round = false)
                listings.isEmpty() -> Text(
                    "${user.displayName} has nothing for sale right now, so there's nothing to message them about yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md),
                )
                else -> listings.forEach { listing ->
                    ListingLine(listing, onClick = { onPick(listing) }, trailing = { Chevron() })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- settings

/**
 * Settings: account details (email, campus, role), profile, blocked users, policy links (server-rendered /legal pages
 * in the browser), version, in debug builds the API server override, sign out (confirmed) and two-step account
 * deletion that spells out what happens (409 "open orders" handled).
 */
@Composable
fun SettingsScreen(nav: NavController) {
    val vm = koinViewModel<SettingsViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val server: ServerConfig = koinInject()
    val baseUrl by server.baseUrl.collectAsStateWithLifecycle()
    val user = LocalCurrentUser.current
    var showServer by remember { mutableStateOf(false) }
    val openPage = rememberPageOpener()

    Scaffold(topBar = { AppTopBar("Settings", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
            MenuGroup("Account") {
                MenuRow(Icons.Default.Email, user.email, "Your sign-in email. It can't be changed.")
                MenuDivider()
                MenuRow(Glyphs.School, user.campusDomain, "Your campus: you see and trade with students here")
                if (user.role != UserRole.USER) {
                    MenuDivider()
                    MenuRow(Glyphs.Verified, user.role.label, "Staff access to moderation tools")
                }
            }
            MenuGroup("Profile") {
                MenuRow(Icons.Default.AccountCircle, "Edit profile", "Name, photo and bio", onClick = { nav.navigate(EditProfileRoute) })
            }
            MenuGroup("Privacy & safety") {
                MenuRow(Glyphs.Block, "Blocked users", "People who can't message you or buy from you", onClick = { nav.navigate(BlockedUsersRoute) })
                MenuDivider()
                MenuRow(Glyphs.Shield, "Community guidelines", onClick = { openPage(ApiRoutes.LEGAL_GUIDELINES) }, trailing = { ExternalMark() })
            }
            MenuGroup("About") {
                MenuRow(Glyphs.Document, "Terms of service", onClick = { openPage(ApiRoutes.LEGAL_TERMS) }, trailing = { ExternalMark() })
                MenuDivider()
                MenuRow(Icons.Default.Lock, "Privacy policy", onClick = { openPage(ApiRoutes.LEGAL_PRIVACY) }, trailing = { ExternalMark() })
                MenuDivider()
                MenuRow(Icons.Default.Info, "Quad", "Version ${BuildInfo.VERSION_NAME}")
            }
            if (server.canOverride) {
                MenuGroup("Developer") {
                    MenuRow(
                        Icons.Default.Settings,
                        "API server",
                        if (server.isOverridden) "$baseUrl (override)" else baseUrl,
                        onClick = { showServer = true },
                    )
                }
            }
            MenuGroup(null) {
                MenuRow(Icons.AutoMirrored.Filled.ExitToApp, "Sign out", onClick = vm::askSignOut, trailing = null)
            }
            MenuGroup(null) {
                MenuRow(Icons.Default.Delete, "Delete account", "Permanently remove your profile and listings", onClick = vm::startDelete, trailing = null, destructive = true)
            }
        }
    }

    if (showServer) ServerSettingsDialog(onDismiss = { showServer = false })

    if (state.confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "You'll need your campus email and password to sign in to Quad on this device again.",
            confirmLabel = "Sign out",
            onConfirm = vm::signOut,
            onDismiss = vm::cancelSignOut,
        )
    }
    when (state.deleteStep) {
        DeleteStep.None -> Unit
        DeleteStep.Warn -> AlertDialog(
            onDismissRequest = vm::cancelDelete,
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete your account?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Your Quad account (${user.email}) will be deleted. This can't be undone.", style = MaterialTheme.typography.bodyMedium)
                    Bullet("Your name, photo and bio are removed. Messages you sent and ratings you gave stay, shown as \"Deleted user\".")
                    Bullet("Your active and reserved listings are taken down. Your favorites and blocked list are cleared.")
                    Bullet("You're signed out on every device. Past orders stay in the other person's history.")
                    Text(
                        "You can't delete your account while an order is awaiting payment, paid or on hold. Finish or cancel those first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = vm::continueDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("Keep my account") } },
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
                    "An order that's awaiting payment, paid or on hold has to be completed or cancelled before you can delete your account. " +
                        "Finish or cancel it, then try again.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissBlocked(); nav.navigate(OrdersRoute()) }) { Text("View orders") }
            },
            dismissButton = { TextButton(onClick = vm::dismissBlocked) { Text("OK") } },
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------------------------------- blocked users

/** `api.myBlocks()` with Unblock (`api.unblockUser`, Undo in the snackbar). Tap a row for the public profile. */
@Composable
fun BlockedUsersScreen(nav: NavController) {
    val vm = koinViewModel<BlockedUsersViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val unblocking by vm.unblocking.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose {}
    }

    Scaffold(topBar = { AppTopBar("Blocked users", onBack = { nav.popBackStack() }) }) { padding ->
        when (val s = state) {
            UiState.Loading -> SkeletonRows(modifier = Modifier.padding(padding).padding(top = Spacing.md))
            is UiState.Error -> ErrorState(s.error, Modifier.fillMaxSize().padding(padding), onRetry = vm::reload)
            is UiState.Content -> PullToRefreshBox(refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
                    Text(
                        "Blocked people can't message you or buy from you, and you won't see their listings. They aren't told.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md),
                    )
                    if (s.value.isEmpty()) {
                        EmptyState(
                            "No one blocked",
                            Modifier.fillMaxWidth().padding(top = Spacing.xxl),
                            message = "To block someone, open their profile or a chat with them and choose Block.",
                            icon = Glyphs.Block,
                        )
                    } else {
                        MenuGroup(null, Modifier.padding(top = 0.dp)) {
                            s.value.forEachIndexed { i, user ->
                                if (i > 0) MenuDivider()
                                BlockedRow(user, unblocking = user.id in unblocking, onOpen = { nav.navigate(UserProfileRoute(user.id)) }, onUnblock = { vm.unblock(user) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedRow(user: UserPublic, unblocking: Boolean, onOpen: () -> Unit, onUnblock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Avatar(user.avatarImageUrl, user.displayName, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(user.campusDomain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onUnblock, enabled = !unblocking) {
            if (unblocking) CircularProgressIndicator(Modifier.padding(end = Spacing.sm).size(14.dp), strokeWidth = 2.dp)
            Text("Unblock")
        }
    }
}
