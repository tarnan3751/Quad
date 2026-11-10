package com.campusmarket.app.feature.listing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShoppingCart
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.campusmarket.app.ui.components.ChipTone
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.ConditionChip
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.ListingStatusChip
import com.campusmarket.app.ui.components.PriceText
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.RatingSummary
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.SecondaryButton
import com.campusmarket.app.ui.components.StatusChip
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderRole
import com.campusmarket.core.Photo
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserPublic
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Photo pager, price, details, seller card, favorite, message seller, buy, report; edit/delete when `isMine`. */
@Composable
fun ListingDetailScreen(route: ListingDetailRoute, nav: NavController) {
    val vm = koinViewModel<ListingDetailViewModel> { parametersOf(route) }
    val state by vm.listing.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var reporting by rememberSaveable { mutableStateOf(false) }
    var viewerPage by rememberSaveable { mutableStateOf<Int?>(null) }

    CollectEvents(vm.events.flow) { event ->
        when (event) {
            ListingDetailEvent.Deleted -> nav.popBackStack()
        }
    }
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }

    val listing = state.valueOrNull
    Scaffold(
        topBar = {
            AppTopBar(
                title = listing?.category?.label ?: "Listing",
                onBack = { nav.popBackStack() },
                actions = {
                    if (listing != null && !listing.isMine) {
                        FavoriteAction(listing.favorited, onClick = vm::toggleFavorite)
                        OverflowMenu(onReport = { reporting = true })
                    }
                },
            )
        },
        bottomBar = {
            if (listing != null) {
                DetailActions(
                    listing = listing,
                    onMessage = { nav.navigate(MessageSellerRoute(listing.id)) },
                    onBuy = { nav.navigate(CheckoutRoute(listing.id)) },
                    onEdit = { nav.navigate(EditListingRoute(listing.id)) },
                    onDelete = vm::askDelete,
                    onViewOrders = { nav.navigate(OrdersRoute(role = OrderRole.SELLER.name.lowercase())) },
                )
            }
        },
    ) { padding ->
        UiStateContent(state, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { l ->
            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                DetailBody(
                    listing = l,
                    onPhotoClick = { viewerPage = it },
                    onSellerClick = { nav.navigate(UserProfileRoute(l.seller.id)) },
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
            onDismiss = { reporting = false },
            onReported = {
                reporting = false
                vm.onReported()
            },
        )
    }
    val photos = listing?.photos.orEmpty()
    viewerPage?.let { page ->
        if (photos.isNotEmpty()) PhotoViewer(photos, initialPage = page.coerceIn(0, photos.lastIndex), onDismiss = { viewerPage = null })
    }
}

// ---------------------------------------------------------------------------------------------------- top bar

@Composable
private fun FavoriteAction(favorited: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (favorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (favorited) "Remove from favorites" else "Add to favorites",
            tint = if (favorited) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun OverflowMenu(onReport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Report listing") },
                leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                onClick = {
                    open = false
                    onReport()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------- body

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(listing: ListingDetail, onPhotoClick: (Int) -> Unit, onSellerClick: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        PhotoPager(listing, onPhotoClick)

        // Readable line length on wide windows.
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                PriceText(listing.priceCents, currency = listing.currency, style = CampusTheme.priceLarge)
                Text(listing.title, style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (listing.status != ListingStatus.ACTIVE) ListingStatusChip(listing.status)
                    ConditionChip(listing.condition)
                    StatusChip(listing.category.label, ChipTone.Info)
                }
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
                statusNotice(listing)?.let { (tone, text) -> StatusNotice(tone, text) }
            }

            HorizontalDivider(Modifier.padding(horizontal = Spacing.screen))
            DetailSection("Description") {
                if (listing.description.isBlank()) {
                    Text("No description.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(listing.description, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (listing.pickupLocation.isNotBlank()) {
                DetailSection("Pickup") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(listing.pickupLocation, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            DetailSection(if (listing.isMine) "Seller (you)" else "Seller") {
                SellerCard(listing.seller, onClick = onSellerClick)
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** Banner text for non-ACTIVE listings, worded for the owner or for everyone else. */
private fun statusNotice(listing: ListingDetail): Pair<ChipTone, String>? = when (listing.status) {
    ListingStatus.ACTIVE -> null
    ListingStatus.RESERVED -> ChipTone.Warning to if (listing.isMine) {
        "An order is in progress. You can't edit or delete this listing until it's completed or cancelled."
    } else {
        "Someone is buying this right now. It becomes available again if their order is cancelled."
    }
    ListingStatus.SOLD -> ChipTone.Neutral to if (listing.isMine) "You sold this item." else "This item has been sold."
    ListingStatus.REMOVED -> ChipTone.Negative to if (listing.isMine) {
        "A moderator removed this listing. Other students can't see it. You can still delete it."
    } else {
        "A moderator removed this listing."
    }
    ListingStatus.DELETED -> ChipTone.Neutral to "This listing was deleted."
}

@Composable
private fun StatusNotice(tone: ChipTone, text: String) {
    val (container, content, icon) = when (tone) {
        ChipTone.Warning -> Triple(CampusTheme.colors.warningContainer, CampusTheme.colors.onWarningContainer, Icons.Filled.Info)
        ChipTone.Negative -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Filled.Warning)
        else -> Triple(CampusTheme.colors.neutralContainer, CampusTheme.colors.onNeutralContainer, Icons.Filled.Info)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SellerCard(seller: UserPublic, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Avatar(seller.avatarUrl, seller.displayName, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(seller.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                RatingSummary(seller.ratingAverage, seller.ratingCount)
                Text(Format.memberSince(seller.joinedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "View profile", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- photos

@Composable
private fun PhotoPager(listing: ListingDetail, onPhotoClick: (Int) -> Unit) {
    val photos = listing.photos
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Square on phones, capped on wide windows so the details stay above the fold.
        val height = minOf(maxWidth, 520.dp)
        if (photos.isEmpty()) {
            RemoteImage(null, contentDescription = null, modifier = Modifier.fillMaxWidth().height(height))
            return@BoxWithConstraints
        }
        val pagerState = rememberPagerState { photos.size }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { photos[it].id }) { page ->
                RemoteImage(
                    url = photos[page].url,
                    contentDescription = "${listing.title}, photo ${page + 1} of ${photos.size}",
                    modifier = Modifier.fillMaxSize().clickable { onPhotoClick(page) },
                    contentScale = ContentScale.Crop,
                )
            }
            if (photos.size > 1) {
                PagerArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous photo", visible = pagerState.currentPage > 0, Modifier.align(Alignment.CenterStart)) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }
                PagerArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next photo", visible = pagerState.currentPage < photos.lastIndex, Modifier.align(Alignment.CenterEnd)) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
                PageCounter(pagerState, Modifier.align(Alignment.TopEnd).padding(Spacing.md))
                PageDots(pagerState, Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.md))
            }
        }
    }
}

@Composable
private fun PagerArrow(icon: ImageVector, description: String, visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (!visible) return
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        modifier = modifier.padding(horizontal = Spacing.sm).size(36.dp),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = description) }
    }
}

@Composable
private fun PageCounter(state: PagerState, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f), modifier = modifier) {
        Text(
            "${state.currentPage + 1}/${state.pageCount}",
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun PageDots(state: PagerState, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.45f), modifier = modifier) {
        Row(Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(state.pageCount) { index ->
                val selected = index == state.currentPage
                Box(
                    Modifier
                        .size(if (selected) 8.dp else 6.dp)
                        .background(
                            MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = if (selected) 1f else 0.55f),
                            CircleShape,
                        )
                        .align(Alignment.CenterVertically),
                )
            }
        }
    }
}

/** Full-screen photo viewer (fit, swipeable). */
@Composable
private fun PhotoViewer(photos: List<Photo>, initialPage: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pagerState = rememberPagerState(initialPage = initialPage) { photos.size }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { photos[it].id }) { page ->
                RemoteImage(
                    url = photos[page].url,
                    contentDescription = "Photo ${page + 1} of ${photos.size}",
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim),
                    contentScale = ContentScale.Fit,
                    placeholderIcon = null,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Spacing.sm)) {
                Icon(Icons.Filled.Close, contentDescription = "Close photos", tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
            if (photos.size > 1) {
                PageCounter(pagerState, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Spacing.lg))
                PageDots(pagerState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Spacing.lg))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- actions

@Composable
private fun DetailActions(
    listing: ListingDetail,
    onMessage: () -> Unit,
    onBuy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onViewOrders: () -> Unit,
) {
    val buttons: List<@Composable (Modifier) -> Unit> = buildList {
        if (listing.isMine) {
            if (listing.canDelete) add { m -> SecondaryButton("Delete", onClick = onDelete, icon = Icons.Filled.Delete, modifier = m) }
            if (listing.canEdit) add { m -> PrimaryButton("Edit listing", onClick = onEdit, icon = Icons.Filled.Edit, modifier = m) }
            if (listing.status == ListingStatus.RESERVED || listing.status == ListingStatus.SOLD) {
                add { m -> SecondaryButton("View my sales", onClick = onViewOrders, modifier = m) }
            }
        } else {
            if (listing.canMessage) add { m -> SecondaryButton("Message seller", onClick = onMessage, icon = Icons.Filled.Email, modifier = m) }
            when {
                listing.canBuy -> add { m -> PrimaryButton("Buy · ${Format.price(listing.priceCents, listing.currency)}", onClick = onBuy, icon = Icons.Filled.ShoppingCart, modifier = m) }
                listing.status == ListingStatus.RESERVED -> add { m -> PrimaryButton("Reserved", onClick = {}, enabled = false, modifier = m) }
            }
        }
    }
    if (buttons.isEmpty()) return
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.screen, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
        ) {
            buttons.forEach { button -> button(Modifier.weight(1f).widthIn(max = 320.dp)) }
        }
    }
}
