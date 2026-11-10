package com.campusmarket.app.feature.listing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.campusmarket.app.platform.rememberImagePicker
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.components.AppTextField
import com.campusmarket.app.ui.components.AppTopBar
import com.campusmarket.app.ui.components.CollectEvents
import com.campusmarket.app.ui.components.ConfirmDialog
import com.campusmarket.app.ui.components.EmptyState
import com.campusmarket.app.ui.components.FilterChip
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Sell tab: create a listing. Photos upload as they're picked; posting opens the new listing. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SellScreen(nav: NavController) {
    val vm = koinViewModel<SellViewModel>()
    val form by vm.form.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

    CollectEvents(vm.events.flow) { event ->
        if (event is ListingEditorEvent.Created) {
            // The form is reset for the next listing: start it from the top, not where "Post listing" was.
            scroll.scrollTo(0)
            nav.navigate(ListingDetailRoute(event.listingId))
        }
    }
    // Back on the Sell tab leaves it (and its form) for good: ask first if there's something to lose.
    BackHandler(enabled = vm.hasUnsavedChanges) { confirmLeave = true }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Sell an item",
                actions = {
                    if (!form.isBlank && !form.submitting) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                    IconButton(onClick = { nav.navigate(MyListingsRoute) }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "My listings")
                    }
                },
            )
        },
    ) { padding ->
        ListingForm(
            form = form,
            vm = vm,
            intro = "Students on your campus see it right away. Buyers pay in the app and you hand the item over in person.",
            submitLabel = "Post listing",
            modifier = Modifier.padding(padding),
            scroll = scroll,
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear this listing?",
            message = "Your photos and details will be discarded.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                vm.clearForm()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Discard this listing?",
            message = "You haven't posted it yet. Your photos and details will be lost.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                vm.clearForm()
                nav.popBackStack()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

/** Edit an ACTIVE listing of mine (`api.updateListing`); prefilled from the listing. Asks before discarding edits. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditListingScreen(route: EditListingRoute, nav: NavController) {
    val vm = koinViewModel<EditListingViewModel> { parametersOf(route) }
    val original by vm.original.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val unsaved = remember(form, original) { vm.hasUnsavedChanges }

    CollectEvents(vm.events.flow) { event ->
        if (event is ListingEditorEvent.Saved) nav.popBackStack()
    }
    val leave = { if (unsaved) confirmDiscard = true else nav.popBackStack() }
    BackHandler(enabled = unsaved) { confirmDiscard = true }

    Scaffold(topBar = { AppTopBar("Edit listing", onBack = { leave() }) }) { padding ->
        UiStateContent(original, onRetry = vm::reload, modifier = Modifier.fillMaxSize().padding(padding)) { listing ->
            val blocked = listing.editBlockedReason()
            if (blocked != null) {
                EmptyState(
                    "Can't edit this listing",
                    modifier = Modifier.fillMaxSize().padding(padding),
                    message = blocked,
                    actionLabel = "Go back",
                    onAction = { nav.popBackStack() },
                )
            } else {
                ListingForm(form = form, vm = vm, intro = null, submitLabel = "Save changes", modifier = Modifier.padding(padding))
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard your changes?",
            message = "The listing stays as it was.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                nav.popBackStack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------------- form

/** Field keys in screen order (also the order errors are brought into view). */
private val FieldOrder = listOf("photoIds", "title", "priceCents", "category", "condition", "description", "pickupLocation")

@Composable
private fun ListingForm(
    form: ListingFormState,
    vm: ListingFormViewModel,
    intro: String?,
    submitLabel: String,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
) {
    val picker = rememberImagePicker(
        maxItems = form.photosRemaining.coerceAtLeast(1),
        onError = { vm.onPickFailed() },
        onPicked = vm::addPhotos,
    )
    // Where each field sits in the scrolling column, to bring the first error into view after a rejected submit.
    val positions = remember { mutableMapOf<String, Int>() }
    LaunchedEffect(form.rejectedSubmits) {
        if (form.rejectedSubmits == 0) return@LaunchedEffect
        val first = FieldOrder.firstOrNull { it in form.fieldErrors } ?: return@LaunchedEffect
        positions[first]?.let { y -> scroll.animateScrollTo((y - 48).coerceAtLeast(0)) }
    }
    fun Modifier.field(key: String) = onGloballyPositioned { positions[key] = it.positionInParent().y.toInt() }

    Box(
        modifier.fillMaxSize().imePadding().verticalScroll(scroll),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (intro != null) {
                Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PhotosSection(
                form = form,
                onAdd = picker::launch,
                onRetry = vm::retryUpload,
                onRemove = vm::removePhoto,
                onMove = vm::movePhoto,
                onMakeCover = vm::makeCover,
                modifier = Modifier.field("photoIds"),
            )
            AppTextField(
                value = form.title,
                onValueChange = vm::onTitle,
                label = "Title",
                modifier = Modifier.field("title"),
                error = form.fieldErrors["title"],
                placeholder = "e.g. Calculus textbook, 8th edition",
                maxLength = Limits.TITLE_MAX,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
            PriceInput(
                value = form.price,
                onValueChange = vm::onPrice,
                modifier = Modifier.field("priceCents"),
                error = form.fieldErrors["priceCents"],
                supportingText = "${Format.price(Limits.PRICE_MIN_CENTS)} to ${Format.price(Limits.PRICE_MAX_CENTS)}. Buyers pay in the app.",
            )
            ChoiceSection(
                title = "Category",
                options = ListingCategory.entries,
                selected = form.category,
                label = { it.label },
                icon = { it.icon },
                error = form.fieldErrors["category"],
                onSelect = vm::onCategory,
                modifier = Modifier.field("category"),
            )
            ChoiceSection(
                title = "Condition",
                options = ListingCondition.entries,
                selected = form.condition,
                label = { it.label },
                error = form.fieldErrors["condition"],
                onSelect = vm::onCondition,
                hint = form.condition?.hint ?: "How worn is it? Being upfront avoids awkward handoffs.",
                modifier = Modifier.field("condition"),
            )
            AppTextField(
                value = form.description,
                onValueChange = vm::onDescription,
                label = "Description (optional)",
                modifier = Modifier.field("description"),
                error = form.fieldErrors["description"],
                supportingText = "Edition, size, defects, what's included.",
                singleLine = false,
                minLines = 4,
                maxLength = Limits.DESCRIPTION_MAX,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            AppTextField(
                value = form.pickupLocation,
                onValueChange = vm::onPickupLocation,
                label = "Pickup spot (optional)",
                modifier = Modifier.field("pickupLocation"),
                error = form.fieldErrors["pickupLocation"],
                placeholder = "e.g. Library steps, North Hall lobby",
                supportingText = "Somewhere public on campus.",
                maxLength = Limits.PICKUP_MAX,
                leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
            InlineError(form.formError, Modifier.fillMaxWidth())
            val uploading = form.uploadingCount
            PrimaryButton(
                text = when {
                    form.submitting -> if (submitLabel == "Post listing") "Posting…" else "Saving…"
                    uploading > 0 -> if (uploading == 1) "Uploading 1 photo…" else "Uploading $uploading photos…"
                    else -> submitLabel
                },
                onClick = vm::submit,
                loading = form.submitting || uploading > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceSection(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    error: String?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
    hint: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            options.forEach { option ->
                val isSelected = option == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    leadingIcon = when {
                        isSelected -> {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        }
                        icon != null -> {
                            { Icon(icon(option), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        }
                        else -> null
                    },
                )
            }
        }
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            hint != null -> Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- photos

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotosSection(
    form: ListingFormState,
    onAdd: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onMakeCover: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = form.fieldErrors["photoIds"]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Photos",
                style = MaterialTheme.typography.titleSmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${form.photos.size} of ${Limits.PHOTOS_MAX}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (form.photos.isEmpty()) {
            EmptyPhotosTile(onAdd, isError = error != null)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth < 480.dp) 4 else 5
                val gap = Spacing.sm
                val tile = (maxWidth - gap * (columns - 1)) / columns
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                    form.photos.forEachIndexed { index, item ->
                        PhotoTile(
                            item = item,
                            index = index,
                            count = form.photos.size,
                            onRetry = { onRetry(item.key) },
                            onRemove = { onRemove(item.key) },
                            onMove = { to -> onMove(item.key, to) },
                            onMakeCover = { onMakeCover(item.key) },
                            modifier = Modifier.size(tile),
                        )
                    }
                    if (form.photosRemaining > 0) AddPhotoTile(onAdd, Modifier.size(tile))
                }
            }
        }
        val failed = form.photos.firstOrNull { it.status == UploadStatus.Failed }
        val help = when {
            error != null -> error
            failed != null -> "A photo didn't upload: ${failed.error ?: "unknown error"}. " +
                if (failed.retryable) "Tap it to try again." else "Remove it and pick another."
            form.photos.size > 1 -> "The first photo is the cover. Tap a photo to move it or make it the cover."
            form.photos.size == 1 -> "This is your cover photo. Add a few more angles: listings with several photos sell faster."
            else -> null
        }
        if (help != null) {
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null || failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyPhotosTile(onClick: () -> Unit, isError: Boolean) {
    val outline = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            .height(148.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(BorderStroke(1.5.dp, outline), MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Add photos", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(ListingIcons.Camera, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, size = 52.dp)
        Spacer(Modifier.height(Spacing.sm))
        Text("Add photos", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Up to ${Limits.PHOTOS_MAX}. Good light and a plain background sell faster.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
    }
}

@Composable
private fun AddPhotoTile(onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Add more photos", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Add", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PhotoTile(
    item: PhotoItem,
    index: Int,
    count: Int,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onMakeCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val description = "Photo ${index + 1} of $count" + when (item.status) {
        UploadStatus.Uploading -> ", uploading"
        UploadStatus.Failed -> ", upload failed"
        UploadStatus.Uploaded -> if (index == 0) ", cover" else ""
    }
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = if (item.retryable) "Retry upload" else "Photo options") {
                    if (item.retryable) onRetry() else menuOpen = true
                },
        ) {
            PhotoPreview(item, description, Modifier.fillMaxSize())
            when (item.status) {
                UploadStatus.Uploading -> Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val progress = item.progress
                    if (progress != null && progress > 0f) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.inverseOnSurface, strokeWidth = 3.dp)
                    }
                }
                UploadStatus.Failed -> Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (item.retryable) Icons.Filled.Refresh else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        if (item.retryable) "Retry" else "Too large",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                UploadStatus.Uploaded -> Unit
            }
            if (index == 0 && item.status == UploadStatus.Uploaded) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(topEnd = 8.dp),
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    Text("Cover", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        // 48 dp touch target over the corner; the visible button is the small circle inside it.
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f), shadowElevation = 1.dp, modifier = Modifier.size(24.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove photo ${index + 1}", modifier = Modifier.size(14.dp))
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            fun act(action: () -> Unit) {
                menuOpen = false
                action()
            }
            if (index > 0) {
                DropdownMenuItem(
                    text = { Text("Make cover photo") },
                    leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    onClick = { act(onMakeCover) },
                )
                DropdownMenuItem(
                    text = { Text("Move earlier") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) },
                    onClick = { act { onMove(index - 1) } },
                )
            }
            if (index < count - 1) {
                DropdownMenuItem(
                    text = { Text("Move later") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    onClick = { act { onMove(index + 1) } },
                )
            }
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { act(onRemove) },
            )
        }
    }
}

/** The local pick while it uploads (no round trip), the server copy otherwise. */
@Composable
private fun PhotoPreview(item: PhotoItem, description: String, modifier: Modifier) {
    val local = item.local
    if (local != null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            AsyncImage(model = local.bytes, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    } else {
        ListingImage(item.url, contentDescription = description, modifier = modifier, iconSize = 24.dp)
    }
}
