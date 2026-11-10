package com.campusmarket.app.feature.profile

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.BuildInfo
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.data.isModerator
import com.campusmarket.app.feature.listing.FavoritesRoute
import com.campusmarket.app.feature.listing.MyListingsRoute
import com.campusmarket.app.feature.moderation.DisputesRoute
import com.campusmarket.app.feature.moderation.ModerationLogRoute
import com.campusmarket.app.feature.moderation.ModerationQueueRoute
import com.campusmarket.app.feature.moderation.StaffRoute
import com.campusmarket.app.feature.orders.OrdersRoute
import com.campusmarket.app.feature.orders.PayoutsRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.navigation.OnTabReselected
import com.campusmarket.app.navigation.TopLevelTab
import com.campusmarket.app.platform.ExternalLinks
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.QuadIcons
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.rememberTimeFormat
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserRole
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Profile tab: who I am (avatar, name, campus, member since, rating, bio), my numbers (active listings, sold, rating),
 * then grouped menus: buying & selling, moderation (staff only), account, safety, legal, sign out, and the app
 * version. Refreshes on every resume (back from Edit profile, the payouts browser, ...) and on pull.
 */
@Composable
fun ProfileScreen(nav: NavController) {
    val user = LocalCurrentUser.current
    val vm = koinViewModel<ProfileViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val openPage = rememberPageOpener()
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }
    val scroll = rememberScrollState()
    OnTabReselected(TopLevelTab.Profile) { scroll.animateScrollTo(0) }

    Scaffold(
        topBar = {
            AppTopBar(
                "Profile",
                actions = {
                    IconButton(onClick = { nav.navigate(SettingsRoute) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh(pulled = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = Spacing.xl)) {
                OwnProfileHeader(
                    user,
                    onEdit = { nav.navigate(EditProfileRoute) },
                    onPreview = { nav.navigate(UserProfileRoute(user.id)) },
                )
                val stats = state.stats
                StatsCard(
                    listOf(
                        Stat(stats?.format(stats.active) ?: "", if (stats?.active == 1) "Active listing" else "Active listings", onClick = { nav.navigate(MyListingsRoute) }),
                        Stat(stats?.format(stats.sold) ?: "", "Sold", onClick = { nav.navigate(MyListingsRoute) }),
                        ratingStat(user.ratingAverage, user.ratingCount) { nav.navigate(UserProfileRoute(user.id, showRatings = true)) },
                    ),
                    loading = stats == null,
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )

                MenuGroup("Buying & selling") {
                    MenuRow(QuadIcons.Tag, "My listings", "Edit, mark sold or delete", onClick = { nav.navigate(MyListingsRoute) })
                    MenuDivider()
                    MenuRow(Icons.Default.Favorite, "Favorites", "Listings you've saved", onClick = { nav.navigate(FavoritesRoute) })
                    MenuDivider()
                    MenuRow(QuadIcons.Receipt, "Orders", "Purchases and sales", onClick = { nav.navigate(OrdersRoute()) })
                    MenuDivider()
                    MenuRow(
                        QuadIcons.Wallet,
                        "Payouts",
                        if (user.payoutsEnabled) "Sales are paid to your bank" else "Get paid for what you sell",
                        onClick = { nav.navigate(PayoutsRoute) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                if (user.payoutsEnabled) StatusChip("On", ChipTone.Positive) else StatusChip("Set up", ChipTone.Warning)
                                Chevron()
                            }
                        },
                    )
                }

                if (user.isModerator) {
                    MenuGroup("Moderation") {
                        MenuRow(QuadIcons.Flag, "Reports", "Review what students reported", onClick = { nav.navigate(ModerationQueueRoute) })
                        MenuDivider()
                        MenuRow(QuadIcons.Gavel, "Disputes", "Decide held payments", onClick = { nav.navigate(DisputesRoute) })
                        MenuDivider()
                        MenuRow(QuadIcons.History, "Moderation log", "Every action staff have taken", onClick = { nav.navigate(ModerationLogRoute) })
                        if (user.role == UserRole.ADMIN) {
                            MenuDivider()
                            MenuRow(QuadIcons.Group, "Staff", "Add or remove moderators", onClick = { nav.navigate(StaffRoute) })
                        }
                    }
                }

                MenuGroup("Account") {
                    MenuRow(Icons.Default.Edit, "Edit profile", "Name, photo and bio", onClick = { nav.navigate(EditProfileRoute) })
                    MenuDivider()
                    MenuRow(Icons.Default.Settings, "Settings", "Email, campus and account deletion", onClick = { nav.navigate(SettingsRoute) })
                }

                MenuGroup("Safety") {
                    MenuRow(QuadIcons.Block, "Blocked users", "People who can't contact you", onClick = { nav.navigate(BlockedUsersRoute) })
                    MenuDivider()
                    MenuRow(QuadIcons.Shield, "Community guidelines", "What's allowed on Quad", onClick = { openPage(ApiRoutes.LEGAL_GUIDELINES) }, trailing = { ExternalMark() })
                }

                MenuGroup("Legal") {
                    MenuRow(QuadIcons.Document, "Terms of service", onClick = { openPage(ApiRoutes.LEGAL_TERMS) }, trailing = { ExternalMark() })
                    MenuDivider()
                    MenuRow(Icons.Default.Lock, "Privacy policy", onClick = { openPage(ApiRoutes.LEGAL_PRIVACY) }, trailing = { ExternalMark() })
                }

                MenuGroup(null) {
                    MenuRow(Icons.AutoMirrored.Filled.ExitToApp, "Sign out", onClick = vm::askSignOut, trailing = null)
                }

                VersionFooter()
            }
        }
    }

    if (state.confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "You'll need your campus email and password to sign in to Quad on this device again.",
            confirmLabel = "Sign out",
            onConfirm = vm::signOut,
            onDismiss = vm::cancelSignOut,
        )
    }
}

@Composable
private fun OwnProfileHeader(user: UserPrivate, onEdit: () -> Unit, onPreview: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(top = Spacing.md, bottom = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Avatar(user.avatarUrl, user.displayName, size = 96.dp)
        Text(
            user.displayName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        if (user.role != UserRole.USER) StaffBadge(user.role)
        CampusLine(user.campusDomain, user.joinedAt)
        if (user.bio.isNotBlank()) {
            Text(
                user.bio,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        } else {
            TextButton(onClick = onEdit) { Text("Add a short bio so buyers know who they're meeting") }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SecondaryButton("Edit profile", onClick = onEdit, icon = Icons.Default.Edit, modifier = Modifier.weight(1f))
            SecondaryButton("Public view", onClick = onPreview, icon = QuadIcons.Visibility, modifier = Modifier.weight(1f))
        }
    }
}

/** "state.edu · Joined Sep 2026" with a school mark. */
@Composable
internal fun CampusLine(campusDomain: String, joinedAt: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(QuadIcons.School, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            "$campusDomain · ${rememberTimeFormat().memberSince(joinedAt)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Moderator" / "Admin" with a verified mark, so staff are recognisable on their profile. */
@Composable
internal fun StaffBadge(role: UserRole) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(QuadIcons.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text("Quad ${role.label.lowercase()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The rating cell of a [StatsCard]: "★ 4.8" over "12 ratings", or "New" when nobody has rated them yet. */
internal fun ratingStat(average: Double?, count: Int, onClick: () -> Unit): Stat {
    val rated = average != null && count > 0
    return Stat(
        value = if (rated) Format.rating(average).orEmpty() else "New",
        label = when (count) {
            0 -> "No ratings yet"
            1 -> "1 rating"
            else -> "$count ratings"
        },
        onClick = onClick,
        description = if (rated) "Rated ${Format.rating(average)} out of 5 from ${if (count == 1) "1 rating" else "$count ratings"}" else "No ratings yet",
        star = rated,
    )
}

@Composable
private fun VersionFooter() {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Quad ${BuildInfo.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        Text("Buy and sell on your campus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Opens one of the server-rendered pages (terms, privacy, guidelines) in the browser. */
@Composable
internal fun rememberPageOpener(): (String) -> Unit {
    val server: ServerConfig = koinInject()
    val links: ExternalLinks = koinInject()
    val messages: UserMessages = koinInject()
    return { path -> if (!links.open(server.url(path))) messages.show("Couldn't open the page.") }
}
