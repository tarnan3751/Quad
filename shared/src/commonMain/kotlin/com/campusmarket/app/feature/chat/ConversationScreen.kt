package com.campusmarket.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.campusmarket.app.feature.listing.ListingDetailRoute
import com.campusmarket.app.feature.moderation.ReportSheet
import com.campusmarket.app.feature.orders.CheckoutRoute
import com.campusmarket.app.feature.profile.BlockUserDialog
import com.campusmarket.app.feature.profile.UserProfileRoute
import com.campusmarket.app.navigation.keepSnackbarsAbove
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.isCompactHeight
import com.campusmarket.app.ui.components.Avatar
import com.campusmarket.app.ui.components.BackButton
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.ErrorState
import com.campusmarket.app.ui.components.LoadingState
import com.campusmarket.app.ui.components.RatingSummary
import com.campusmarket.app.ui.components.avatarImageUrl
import com.campusmarket.app.ui.rememberTimeFormat
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Conversation
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ReportTargetType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private const val LOAD_OLDER_THRESHOLD = 5

/**
 * One conversation: header (other person → profile, listing → detail), history paged backwards (newest at the
 * bottom), live messages, optimistic sending with retry, read receipts, report / block.
 */
@Composable
fun ConversationScreen(route: ConversationRoute, nav: NavController) {
    val vm = koinViewModel<ConversationViewModel>(key = "conversation:${route.conversationId}") { parametersOf(route) }
    val conversationState by vm.conversation.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }

    var reportUser by remember { mutableStateOf(false) }
    var reportMessageId by remember { mutableStateOf<String?>(null) }
    var blockDialog by remember { mutableStateOf(false) }
    val conversation = (conversationState as? UiState.Content)?.value
    // A phone in landscape: the listing moves into the top bar, so its strip doesn't take a third of the messages' room.
    val compact = isCompactHeight()
    val restricted by remember { derivedStateOf { vm.state.value.restriction != null } }

    Scaffold(
        topBar = {
            if (conversation == null) {
                AppTopBar("Conversation", onBack = { nav.popBackStack() })
            } else {
                ConversationTopBar(
                    conversation,
                    onBack = { nav.popBackStack() },
                    onOpenProfile = { nav.navigate(UserProfileRoute(conversation.otherUser.id)) },
                    onReport = { reportUser = true },
                    onBlock = { blockDialog = true },
                    listing = {
                        if (compact) {
                            val mine = conversation.listing.sellerId == vm.myId
                            ListingChip(
                                conversation.listing,
                                showBuy = !mine && conversation.listing.status == ListingStatus.ACTIVE && !restricted,
                                onOpen = { nav.navigate(ListingDetailRoute(conversation.listing.id)) },
                                onBuy = { nav.navigate(CheckoutRoute(conversation.listing.id)) },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        when (val s = conversationState) {
            UiState.Loading -> Column(body) {
                Box(Modifier.weight(1f)) { BubbleSkeletons(Modifier.align(Alignment.BottomCenter)) }
            }
            // The messages were loading at the same time and failed with it.
            is UiState.Error -> ErrorState(s.error, body, onRetry = { vm.loadConversation(); vm.retryHistory() })
            is UiState.Content -> BoxWithConstraints(body) {
                // Typing in landscape leaves only a strip above the keyboard: give it to the messages and the composer.
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val roomForStrip = !compact && (!imeVisible || maxHeight >= CompactChatHeight)
                Column(Modifier.fillMaxSize()) {
                    val c = s.value
                    val state by vm.state.collectAsStateWithLifecycle()
                    val mine = c.listing.sellerId == vm.myId
                    if (roomForStrip) {
                        ListingStrip(
                            c.listing,
                            mine = mine,
                            showBuy = !mine && c.listing.status == ListingStatus.ACTIVE && state.restriction == null,
                            onOpen = { nav.navigate(ListingDetailRoute(c.listing.id)) },
                            onBuy = { nav.navigate(CheckoutRoute(c.listing.id)) },
                        )
                    }
                    val connection by vm.connection.collectAsStateWithLifecycle()
                    ConnectionBanner(connection, onRetry = vm::retryConnection)
                    Box(Modifier.weight(1f)) {
                        MessageList(vm, c, onReportMessage = { reportMessageId = it })
                    }
                    Box(Modifier.keepSnackbarsAbove()) {
                        ComposerArea(vm, c, isBuyer = !mine, onUnblock = { blockDialog = true })
                    }
                }
            }
        }
    }

    if (conversation != null) {
        if (reportUser) {
            ReportSheet(
                ReportTargetType.USER,
                conversation.otherUser.id,
                onDismiss = { reportUser = false },
            )
        }
        reportMessageId?.let { id ->
            ReportSheet(
                ReportTargetType.MESSAGE,
                id,
                onDismiss = { reportMessageId = null },
            )
        }
        if (blockDialog) {
            BlockUserDialog(
                conversation.otherUser,
                onDismiss = { blockDialog = false },
                onDone = { blocked -> blockDialog = false; vm.onBlockChanged(blocked) },
            )
        }
    }
}

@Composable
private fun ConversationTopBar(
    conversation: Conversation,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    listing: @Composable () -> Unit = {},
) {
    val other = conversation.otherUser
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { BackButton(onBack) },
        title = {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = "View profile", onClick = onOpenProfile)
                    .padding(end = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(other.avatarImageUrl, other.displayName, size = 38.dp, contentDescription = null)
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(other.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RatingSummary(other.ratingAverage, other.ratingCount, starSize = 11.dp)
                }
            }
        },
        actions = {
            listing()
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("View profile") }, onClick = { menu = false; onOpenProfile() })
                    DropdownMenuItem(text = { Text("Report ${other.displayName}") }, onClick = { menu = false; onReport() })
                    DropdownMenuItem(
                        text = { Text(if (other.blockedByMe) "Unblock ${other.displayName}" else "Block ${other.displayName}") },
                        onClick = { menu = false; onBlock() },
                    )
                }
            }
        },
    )
}

@Composable
private fun MessageList(vm: ConversationViewModel, conversation: Conversation, onReportMessage: (String) -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val paged by vm.history.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val times = rememberTimeFormat()
    val otherName = conversation.otherUser.displayName

    // Older pages load as the top (the end of the reversed list) comes into view; appending there keeps the viewport still.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_OLDER_THRESHOLD
        }.distinctUntilChanged().collect { nearTop -> if (nearTop) vm.loadOlder() }
    }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    // Unsent bubbles of mine stay at the very bottom, so "newest" is tracked separately for my rows and theirs.
    val newest = rows.firstOrNull()
    val newestTheirs = rows.firstOrNull { it is ChatRow.Bubble && !it.mine }?.key
    LaunchedEffect(newest?.key) {
        // I just sent something: show it.
        if ((newest as? ChatRow.Bubble)?.mine == true) listState.animateScrollToItem(0)
    }
    LaunchedEffect(newestTheirs) {
        // They wrote: follow along if I'm at the bottom, otherwise the jump chip counts it.
        if (newestTheirs != null && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    var seenTheirsKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(atBottom, newestTheirs) { if (atBottom) seenTheirsKey = newestTheirs }
    val unseen = if (atBottom) {
        0
    } else {
        rows.asSequence().filter { it is ChatRow.Bubble && !it.mine }.takeWhile { it.key != seenTheirsKey }.count()
            .takeIf { seenTheirsKey != null } ?: 0
    }

    when {
        !paged.loaded && paged.error != null && rows.isEmpty() ->
            ErrorState(paged.error!!, Modifier.fillMaxSize(), onRetry = vm::retryHistory)
        !paged.loaded && rows.isEmpty() -> Box(Modifier.fillMaxSize()) { BubbleSkeletons(Modifier.align(Alignment.BottomCenter)) }
        rows.isEmpty() -> EmptyState(
            if (conversation.listing.sellerId == vm.myId) "${firstNameOf(otherName)} is interested" else "Say hello",
            message = if (conversation.listing.sellerId == vm.myId) {
                "$otherName wants to know about “${conversation.listing.title}”. Reply to agree on a time and place to meet."
            } else {
                "Ask about condition, pickup time or price. Pay in the app: your money is held until you've got the item."
            },
            icon = Icons.Filled.Email,
        )
        else -> Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(rows, key = { it.key }, contentType = { if (it is ChatRow.Bubble) "bubble" else "day" }) { row ->
                    when (row) {
                        is ChatRow.Bubble -> MessageBubble(
                            row,
                            time = times.time(row.createdAt),
                            senderName = otherName,
                            onReport = if (!row.mine && row.messageId != null) ({ onReportMessage(row.messageId) }) else null,
                            onRetry = { row.localId?.let(vm::retry) },
                            onDiscard = { row.localId?.let(vm::discard) },
                        )
                        is ChatRow.DayHeader -> DaySeparator(times.dayLabel(row.dayOf))
                    }
                }
                item(key = "__top", contentType = "top") {
                    HistoryTop(
                        loadingMore = paged.isLoadingMore,
                        // An unsent message can be on screen before the first page ever loaded.
                        failed = paged.loadMoreError != null || (!paged.loaded && paged.error != null),
                        endReached = paged.endReached,
                        listingTitle = conversation.listing.title,
                        onRetry = vm::retryHistory,
                    )
                }
            }
            val showJump = !atBottom
            AnimatedVisibility(
                showJump,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.md),
            ) {
                if (unseen > 0) {
                    Surface(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 3.dp,
                    ) {
                        Row(Modifier.heightIn(min = 40.dp).padding(start = Spacing.md, end = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                if (unseen == 1) "1 new message" else "$unseen new messages",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                } else {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest")
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerArea(vm: ConversationViewModel, conversation: Conversation, isBuyer: Boolean, onUnblock: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val paged by vm.history.state.collectAsStateWithLifecycle()
    val name = conversation.otherUser.displayName
    when (state.restriction) {
        SendRestriction.BlockedByMe -> RestrictionBanner("You blocked $name, so you can't message each other. Unblock them to continue.", "Unblock", onUnblock)
        SendRestriction.Blocked -> RestrictionBanner("You can't send messages here: one of you has blocked the other.", null) {}
        SendRestriction.UserUnavailable -> RestrictionBanner("$name's account is no longer active, so new messages can't be sent.", null) {}
        null -> {
            val opening = paged.isEmpty && state.pending.isEmpty() && state.draft.isEmpty()
            Composer(
                draft = state.draft,
                error = state.draftError,
                placeholder = "Message ${firstNameOf(name)}…",
                onDraft = vm::onDraft,
                onSend = vm::send,
                suggestions = when {
                    !opening -> emptyList()
                    isBuyer -> BUYER_SUGGESTIONS
                    else -> SELLER_SUGGESTIONS
                },
            )
        }
    }
}

private fun firstNameOf(name: String) = name.trim().substringBefore(' ').ifBlank { name }

private val BUYER_SUGGESTIONS = listOf("Hi! Is this still available?", "When could I pick it up?", "Would you take a bit less?")
private val SELLER_SUGGESTIONS = listOf("Hi! Yes, it's still available.", "When would you like to pick it up?", "I can meet on campus.")

/**
 * Entry from a listing's "Message seller": `POST /conversations` (idempotent), then replaces itself with the
 * conversation so Back returns to the listing.
 */
@Composable
fun MessageSellerScreen(route: MessageSellerRoute, nav: NavController) {
    val vm = koinViewModel<MessageSellerViewModel>(key = "message-seller:${route.listingId}") { parametersOf(route) }
    val problem by vm.problem.collectAsStateWithLifecycle()
    CollectEvents(vm.events.flow) { conversationId ->
        nav.navigate(ConversationRoute(conversationId)) { popUpTo<MessageSellerRoute> { inclusive = true } }
    }

    Scaffold(topBar = { AppTopBar("Message seller", onBack = { nav.popBackStack() }) }) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        val p = problem
        when {
            p == null -> LoadingState(modifier)
            p.canRetry -> ErrorState(p.message, modifier, title = p.title, onRetry = vm::start)
            else -> EmptyState(p.title, modifier, message = p.message, icon = Icons.Filled.Info, actionLabel = "Go back", onAction = { nav.popBackStack() })
        }
    }
}

/** Below this height (above the keyboard) the listing strip makes way for the messages and the composer. */
private val CompactChatHeight = 320.dp
