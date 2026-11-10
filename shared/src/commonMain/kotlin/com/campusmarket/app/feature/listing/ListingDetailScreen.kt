package com.campusmarket.app.feature.listing

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.chat.MessageSellerRoute
import com.campusmarket.app.feature.moderation.ReportSheet
import com.campusmarket.app.feature.orders.CheckoutRoute
import com.campusmarket.app.feature.orders.OrdersRoute
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.ErrorState
import com.campusmarket.app.ui.components.ListingStatusChip
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.RatingSummary
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderRole
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserPublic
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.campusmarket.app.navigation.keepSnackbarsAbove

/**
 * Listing detail: photo pager (side by side with the details on wide windows), price and title, scannable facts,
 * description, seller card, and a pinned action bar that always says what you can do: buy / message, edit, or why
 * not (reserved, sold, removed, blocked).
 */
@Composable
fun ListingDetailScreen(route: ListingDetailRoute, nav: NavController) {
    val vm = koinViewModel<ListingDetailViewModel> { parametersOf(route) }
    val state by vm.listing.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var reporting by rememberSaveable { mutableStateOf(false) }
    var viewerPage by rememberSaveable { mutableStateOf<Int?>(null) }
    val scroll = rememberScrollState()
    // The title joins the top bar once the one under the photo has scrolled away.
    var titleScrollThreshold by remember { mutableStateOf(Int.MAX_VALUE) }
    val showTitleInBar by remember { derivedStateOf { scroll.value > titleScrollThreshold } }

    CollectEvents(vm.events.flow) { event ->
        when (event) {
            ListingDetailEvent.Deleted -> nav.popBackStack()
        }
    }
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }

    val listing = (state as? UiState.Content)?.value
    val openSales = { nav.navigate(OrdersRoute(role = OrderRole.SELLER.name.lowercase())) }
    Scaffold(
        topBar = {
            AppTopBar(
                title = if (showTitleInBar) listing?.title.orEmpty() else "",
                onBack = { nav.popBackStack() },
                actions = {
                    if (listing != null) {
                        if (!listing.isMine) FavoriteButton(listing.favorited, onToggle = vm::toggleFavorite, onPhoto = false)
                        OverflowMenu(
                            listing = listing,
                            onReport = { reporting = true },
                            onSellerProfile = { nav.navigate(UserProfileRoute(listing.seller.id)) },
                            onEdit = { nav.navigate(EditListingRoute(listing.id)) },
                            onDelete = vm::askDelete,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (listing != null) Box(Modifier.keepSnackbarsAbove()) {
                DetailActionBar(
                    listing = listing,
                    unblocking = ui.unblocking,
                    onMessage = { nav.navigate(MessageSellerRoute(listing.id)) },
                    onBuy = { nav.navigate(CheckoutRoute(listing.id)) },
                    onEdit = { nav.navigate(EditListingRoute(listing.id)) },
                    onDelete = vm::askDelete,
                    onViewSales = openSales,
                    onUnblock = vm::unblockSeller,
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (val s = state) {
            UiState.Loading -> DetailSkeleton(modifier)
            is UiState.Error -> ErrorState(s.error, modifier, onRetry = vm::reload)
            is UiState.Content -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = vm::refresh, modifier = modifier) {
                DetailBody(
                    listing = s.value,
                    scroll = scroll,
                    onTitlePositioned = { titleScrollThreshold = it },
                    onPhotoClick = { viewerPage = it },
                    onSellerClick = { nav.navigate(UserProfileRoute(s.value.seller.id)) },
                )
            }
        }
    }

    if (ui.confirmingDelete) {
        ConfirmDialog(
            title = "Delete this listing?",
            message = "It disappears from Quad right away and can't be restored. Your conversations about it stay in your inbox.",
            confirmLabel = "Delete",
            destructive = true,
            loading = ui.deleting,
            onConfirm = vm::delete,
            onDismiss = vm::cancelDelete,
        )
    }
    if (reporting) {
        ReportSheet(
            targetType = ReportTargetType.LISTING,
            targetId = route.listingId,
            // The sheet confirms with its own "Report sent" page, so no snackbar here.
            onDismiss = { reporting = false },
        )
    }
    val photos = listing?.photos.orEmpty()
    viewerPage?.let { page ->
        if (photos.isNotEmpty()) PhotoViewer(photos, initialPage = page.coerceIn(0, photos.lastIndex), onDismiss = { viewerPage = null })
    }
}

// ---------------------------------------------------------------------------------------------------- top bar

@Composable
private fun OverflowMenu(
    listing: ListingDetail,
    onReport: () -> Unit,
    onSellerProfile: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    if (listing.isMine && !listing.canEdit && !listing.canDelete) return
    var open by remember { mutableStateOf(false) }
    fun pick(action: () -> Unit) {
        open = false
        action()
    }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (listing.isMine) {
                if (listing.canEdit) {
                    DropdownMenuItem(
                        text = { Text("Edit listing") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { pick(onEdit) },
                    )
                }
                if (listing.canDelete) {
                    DropdownMenuItem(
                        text = { Text("Delete listing", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { pick(onDelete) },
                    )
                }
            } else {
                DropdownMenuItem(
                    text = { Text("Seller's profile") },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    onClick = { pick(onSellerProfile) },
                )
                DropdownMenuItem(
                    text = { Text("Report listing") },
                    leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                    onClick = { pick(onReport) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- body

/** Side by side when the window is wide and short (landscape phones, tablets, desktop); stacked otherwise. */
@Composable
private fun DetailBody(
    listing: ListingDetail,
    scroll: ScrollState,
    onTitlePositioned: (Int) -> Unit,
    onPhotoClick: (Int) -> Unit,
    onSellerClick: () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                PhotoPager(listing, onPhotoClick, Modifier.weight(1f).fillMaxHeight(), showArrows = true)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) {
                    DetailContent(listing, onSellerClick)
                }
            }
            SideEffect { onTitlePositioned(Int.MAX_VALUE) }
        } else {
            // Square on phones, but never more than ~60% of the height (so price and title stay in view).
            val photoHeight = minOf(maxWidth, maxHeight * 0.6f, 560.dp)
            val arrows = maxWidth >= 600.dp
            val threshold = with(density) { (photoHeight + 96.dp).roundToPx() }
            SideEffect { onTitlePositioned(threshold) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                PhotoPager(listing, onPhotoClick, Modifier.fillMaxWidth().height(photoHeight), showArrows = arrows)
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) { DetailContent(listing, onSellerClick) }
            }
        }
    }
}

@Composable
private fun DetailContent(listing: ListingDetail, onSellerClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PriceText(listing.priceCents, currency = listing.currency, style = CampusTheme.priceLarge)
                if (listing.status != ListingStatus.ACTIVE) ListingStatusChip(listing.status)
            }
            Text(listing.title, style = MaterialTheme.typography.titleLarge)
            Text(
                buildString {
                    append(listedLabel(listing.createdAt))
                    if (listing.favoriteCount > 0) {
                        append(" · ")
                        append(if (listing.favoriteCount == 1) "1 favorite" else "${listing.favoriteCount} favorites")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Facts(listing)

        if (listing.description.isNotBlank()) {
            Section("Description") { Text(listing.description, style = MaterialTheme.typography.bodyLarge) }
        }

        Section("Seller") { SellerCard(listing.seller, isMe = listing.isMine, onClick = onSellerClick) }

        if (listing.canBuy) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(ListingIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(
                    "Pay in the app, then meet somewhere public on campus. The seller is paid once you confirm the " +
                        "handoff, or automatically 3 days later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** Condition, category and pickup as labelled rows: the things buyers scan for first. */
@Composable
private fun Facts(listing: ListingDetail) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Fact(ListingIcons.Sparkle, "Condition", listing.condition.label, supporting = listing.condition.hint)
            FactDivider()
            Fact(listing.category.icon, "Category", listing.category.label)
            FactDivider()
            Fact(
                Icons.Filled.LocationOn,
                "Pickup",
                listing.pickupLocation.ifBlank { "Arrange with the seller" },
                muted = listing.pickupLocation.isBlank(),
            )
        }
    }
}

@Composable
private fun FactDivider() = HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
private fun Fact(icon: ImageVector, label: String, value: String, supporting: String? = null, muted: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (muted) FontWeight.Normal else FontWeight.SemiBold,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SellerCard(seller: UserPublic, isMe: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Avatar(seller.avatarUrl, seller.displayName, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (isMe) "${seller.displayName} (you)" else seller.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RatingSummary(seller.ratingAverage, seller.ratingCount)
                Text(Format.memberSince(seller.joinedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "View profile", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- photos

@Composable
private fun PhotoPager(listing: ListingDetail, onPhotoClick: (Int) -> Unit, modifier: Modifier, showArrows: Boolean) {
    val photos = listing.photos
    if (photos.isEmpty()) {
        ListingImage(null, contentDescription = "No photos", modifier = modifier, iconSize = 56.dp)
        return
    }
    val pagerState = rememberPagerState { photos.size }
    val scope = rememberCoroutineScope()
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { photos[it].id }) { page ->
            ListingImage(
                url = photos[page].url,
                contentDescription = "${listing.title}, photo ${page + 1} of ${photos.size}",
                modifier = Modifier.fillMaxSize().clickable(onClickLabel = "View full screen") { onPhotoClick(page) },
                iconSize = 56.dp,
                errorText = "This photo couldn't be loaded",
            )
        }
        if (photos.size > 1) {
            if (showArrows) {
                PagerArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous photo", pagerState.currentPage > 0, Modifier.align(Alignment.CenterStart)) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }
                PagerArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next photo", pagerState.currentPage < photos.lastIndex, Modifier.align(Alignment.CenterEnd)) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            }
            PageDots(pagerState, Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.md))
        }
    }
}

@Composable
private fun PagerArrow(icon: ImageVector, description: String, visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (!visible) return
    IconButton(onClick = onClick, modifier = modifier.padding(horizontal = Spacing.xs)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f), shadowElevation = 1.dp, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- loading

@Composable
private fun DetailSkeleton(modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val block = MaterialTheme.colorScheme.surfaceContainerHighest
        val photoHeight = if (maxWidth >= 600.dp && maxWidth > maxHeight) maxHeight else minOf(maxWidth, maxHeight * 0.6f, 560.dp)
        Column {
            Pulse(Modifier.fillMaxWidth().height(photoHeight), block)
            Column(Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SkeletonLine(96.dp, 28.dp, block)
                SkeletonLine(280.dp, 22.dp, block)
                SkeletonLine(140.dp, 14.dp, block)
                Spacer(Modifier.height(Spacing.sm))
                Pulse(Modifier.fillMaxWidth().height(150.dp).clip(MaterialTheme.shapes.medium), block)
            }
        }
    }
}

@Composable
private fun SkeletonLine(width: Dp, height: Dp, color: Color) =
    Pulse(Modifier.width(width).height(height).clip(RoundedCornerShape(6.dp)), color)

// ---------------------------------------------------------------------------------------------------- action bar

private enum class Tone { Info, Warning, Negative, Neutral }

private data class BarNotice(val tone: Tone, val icon: ImageVector, val title: String, val body: String?)

/** Why the usual actions aren't available, worded for the owner or for everyone else. Null when they are. */
private fun ListingDetail.notice(): BarNotice? = when {
    isMine -> when (status) {
        ListingStatus.ACTIVE -> null
        ListingStatus.RESERVED -> BarNotice(Tone.Warning, ListingIcons.Clock, "Order in progress", "You can edit or delete it again if the order is cancelled.")
        ListingStatus.SOLD -> BarNotice(Tone.Info, ListingIcons.Sparkle, "Sold", "Nice one. This item has found a new home.")
        ListingStatus.REMOVED -> BarNotice(Tone.Negative, Icons.Filled.Warning, "Removed by a moderator", "Other students can't see it. You can still delete it.")
        ListingStatus.DELETED -> BarNotice(Tone.Neutral, Icons.Filled.Info, "Deleted", "This listing was deleted.")
    }
    status == ListingStatus.SOLD -> BarNotice(Tone.Neutral, Icons.Filled.Info, "Sold", "This item has already been sold.")
    status == ListingStatus.REMOVED -> BarNotice(Tone.Negative, Icons.Filled.Warning, "Removed by a moderator", null)
    status == ListingStatus.DELETED -> BarNotice(Tone.Neutral, Icons.Filled.Info, "No longer available", "The seller deleted this listing.")
    seller.blockedByMe -> BarNotice(Tone.Neutral, ListingIcons.Block, "You blocked ${seller.displayName}", "Unblock them to message or buy.")
    blocked -> BarNotice(Tone.Neutral, ListingIcons.Block, "Not available to you", "You can't buy this item or message this seller.")
    status == ListingStatus.RESERVED -> BarNotice(Tone.Warning, ListingIcons.Clock, "Reserved", "Someone is buying this. It's back on sale if their order is cancelled.")
    else -> null
}

/** The pinned bottom bar: what you can do with this listing, or why you can't. */
@Composable
private fun DetailActionBar(
    listing: ListingDetail,
    unblocking: Boolean,
    onMessage: () -> Unit,
    onBuy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onViewSales: () -> Unit,
    onUnblock: () -> Unit,
) {
    val notice = listing.notice()
    val buttons: List<@Composable (Modifier) -> Unit> = buildList {
        if (listing.isMine) {
            when {
                listing.canEdit -> add { m -> PrimaryButton("Edit listing", onClick = onEdit, icon = Icons.Filled.Edit, modifier = m) }
                listing.status == ListingStatus.RESERVED || listing.status == ListingStatus.SOLD ->
                    add { m -> SecondaryButton("View sale", onClick = onViewSales, modifier = m) }
                listing.canDelete -> add { m -> SecondaryButton("Delete listing", onClick = onDelete, icon = Icons.Filled.Delete, modifier = m) }
            }
        } else {
            if (listing.seller.blockedByMe) add { m -> SecondaryButton("Unblock", onClick = onUnblock, loading = unblocking, modifier = m) }
            if (listing.canMessage) add { m -> SecondaryButton("Message", onClick = onMessage, icon = Icons.Filled.Email, modifier = m) }
            if (listing.canBuy) add { m -> PrimaryButton("Buy now", onClick = onBuy, modifier = m) }
        }
    }
    if (notice == null && buttons.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (notice != null) Notice(notice, Modifier.widthIn(max = 640.dp).fillMaxWidth())
            if (buttons.isNotEmpty()) {
                Row(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    buttons.forEach { button -> button(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Notice(notice: BarNotice, modifier: Modifier) {
    val (container, content) = when (notice.tone) {
        Tone.Info -> CampusTheme.colors.successContainer to CampusTheme.colors.onSuccessContainer
        Tone.Warning -> CampusTheme.colors.warningContainer to CampusTheme.colors.onWarningContainer
        Tone.Negative -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Tone.Neutral -> CampusTheme.colors.neutralContainer to CampusTheme.colors.onNeutralContainer
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(notice.icon, container, content, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(notice.title, style = MaterialTheme.typography.titleSmall)
            if (notice.body != null) {
                Text(notice.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
