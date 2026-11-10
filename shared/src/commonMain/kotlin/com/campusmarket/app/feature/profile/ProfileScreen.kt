package com.campusmarket.app.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import com.campusmarket.app.data.isModerator
import com.campusmarket.app.feature.listing.FavoritesRoute
import com.campusmarket.app.feature.listing.MyListingsRoute
import com.campusmarket.app.feature.moderation.ModerationQueueRoute
import com.campusmarket.app.feature.orders.OrdersRoute
import com.campusmarket.app.feature.orders.PayoutsRoute
import com.campusmarket.app.navigation.LocalCurrentUser
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.RatingSummary
import com.campusmarket.app.ui.components.SectionHeader
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.UserRole
import org.koin.compose.viewmodel.koinViewModel

/**
 * Profile tab: my avatar/name/campus/rating and rows to my listings, favorites, orders, payouts, edit profile,
 * blocked users, settings, and the moderation queue for moderators. Refreshes the cached profile on every resume
 * (coming back from Edit profile, the payouts onboarding browser, ...).
 */
@Composable
fun ProfileScreen(nav: NavController) {
    val user = LocalCurrentUser.current
    val vm = koinViewModel<ProfileViewModel>()
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }

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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Avatar(user.avatarUrl, user.displayName, size = 88.dp)
                Text(
                    user.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Text(
                    "${user.campusDomain} · ${Format.memberSince(user.joinedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (user.ratingCount > 0) RatingSummary(user.ratingAverage, user.ratingCount)
                else Text("No ratings yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (user.role != UserRole.USER) {
                    StatusChip(user.role.label, ChipTone.Info, Modifier.padding(top = Spacing.xs))
                }
                if (user.bio.isNotBlank()) {
                    Text(
                        user.bio,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
            HorizontalDivider()

            SectionHeader("Buying and selling")
            MenuRow(Icons.Default.List, "My listings", onClick = { nav.navigate(MyListingsRoute) })
            MenuRow(Icons.Default.Favorite, "Favorites", onClick = { nav.navigate(FavoritesRoute) })
            MenuRow(Icons.Default.ShoppingCart, "Orders", "Purchases and sales", onClick = { nav.navigate(OrdersRoute()) })
            MenuRow(
                Icons.Default.Star,
                "Payouts",
                if (user.payoutsEnabled) "Payouts are set up" else "Set up payouts to get paid for sales",
                onClick = { nav.navigate(PayoutsRoute) },
            )

            SectionHeader("Account")
            MenuRow(Icons.Default.Edit, "Edit profile", onClick = { nav.navigate(EditProfileRoute) })
            MenuRow(Icons.Default.AccountCircle, "View my public profile", "How other students see you", onClick = { nav.navigate(UserProfileRoute(user.id)) })
            MenuRow(Icons.Default.Lock, "Blocked users", onClick = { nav.navigate(BlockedUsersRoute) })
            MenuRow(Icons.Default.Settings, "Settings", onClick = { nav.navigate(SettingsRoute) })

            if (user.isModerator) {
                SectionHeader("Moderation")
                MenuRow(Icons.Default.Warning, "Moderation queue", "Review reports from students", onClick = { nav.navigate(ModerationQueueRoute) })
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = Spacing.xl))
        }
    }
}

/** Settings-style row with a leading icon and a chevron. Shared by the profile tab and Settings. */
@Composable
internal fun MenuRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    },
    destructive: Boolean = false,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = trailing,
    )
}
