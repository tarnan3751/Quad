package com.campusmarket.app.feature.listing

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.campusmarket.app.ui.components.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import com.campusmarket.app.ui.components.InlineError
import com.campusmarket.app.ui.components.PriceField
import com.campusmarket.app.ui.components.PrimaryButton
import com.campusmarket.app.ui.components.RemoteImage
import com.campusmarket.app.ui.components.UiStateContent
import com.campusmarket.app.ui.theme.Spacing
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Sell tab: create a listing. Photos upload as they're picked; posting opens the new listing. */
@Composable
fun SellScreen(nav: NavController) {
    val vm = koinViewModel<SellViewModel>()
    val form by vm.form.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

    CollectEvents(vm.events.flow) { event ->
        if (event is ListingEditorEvent.Created) {
            // The form is reset for the next listing: start it from the top, not where "Post listing" was.
            scroll.scrollTo(0)
            nav.navigate(ListingDetailRoute(event.listingId))
        }
    }

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
            intro = "Students on your campus will see it right away. Buyers pay in the app and you hand the item over in person.",
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
}

/** Edit an ACTIVE listing of mine (`api.updateListing`); prefilled from the listing. */
@Composable
fun EditListingScreen(route: EditListingRoute, nav: NavController) {
    val vm = koinViewModel<EditListingViewModel> { parametersOf(route) }
    val original by vm.original.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()

    CollectEvents(vm.events.flow) { event ->
        if (event is ListingEditorEvent.Saved) nav.popBackStack()
    }

    Scaffold(topBar = { AppTopBar("Edit listing", onBack = { nav.popBackStack() }) }) { padding ->
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
}

// ---------------------------------------------------------------------------------------------------- form

@OptIn(ExperimentalLayoutApi::class)
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
    Box(
        modifier.fillMaxSize().imePadding().verticalScroll(scroll),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
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
            )
            AppTextField(
                value = form.title,
                onValueChange = vm::onTitle,
                label = "Title",
                error = form.fieldErrors["title"],
                placeholder = "e.g. Calculus textbook, 8th edition",
                maxLength = Limits.TITLE_MAX,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
            PriceField(
                value = form.price,
                onValueChange = vm::onPrice,
                error = form.fieldErrors["priceCents"],
                supportingText = "${Format.price(Limits.PRICE_MIN_CENTS)} to ${Format.price(Limits.PRICE_MAX_CENTS)}",
            )
            ChoiceSection(
                title = "Category",
                options = ListingCategory.entries,
                selected = form.category,
                label = { it.label },
                error = form.fieldErrors["category"],
                onSelect = vm::onCategory,
            )
            ChoiceSection(
                title = "Condition",
                options = ListingCondition.entries,
                selected = form.condition,
                label = { it.label },
                error = form.fieldErrors["condition"],
                onSelect = vm::onCondition,
            )
            AppTextField(
                value = form.description,
                onValueChange = vm::onDescription,
                label = "Description",
                error = form.fieldErrors["description"],
                supportingText = "Optional. Edition, size, defects, what's included.",
                singleLine = false,
                minLines = 4,
                maxLength = Limits.DESCRIPTION_MAX,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            AppTextField(
                value = form.pickupLocation,
                onValueChange = vm::onPickupLocation,
                label = "Pickup location",
                error = form.fieldErrors["pickupLocation"],
                placeholder = "e.g. Library steps, North Hall lobby",
                supportingText = "Optional. Somewhere public on campus.",
                maxLength = Limits.PICKUP_MAX,
                leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
            InlineError(form.formError, Modifier.fillMaxWidth())
            PrimaryButton(submitLabel, onClick = vm::submit, loading = form.submitting, modifier = Modifier.fillMaxWidth())
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            options.forEach { option ->
                val isSelected = option == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
            }
        }
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

// ---------------------------------------------------------------------------------------------------- photos

@Composable
private fun PhotosSection(
    form: ListingFormState,
    onAdd: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onMakeCover: (String) -> Unit,
) {
    val error = form.fieldErrors["photoIds"]
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Photos",
                style = MaterialTheme.typography.titleSmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${form.photos.size}/${Limits.PHOTOS_MAX}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // When photos are added, bring the first new one into view (otherwise the row stays anchored on "Add more").
        val rowState = rememberLazyListState()
        var previousCount by remember { mutableStateOf(form.photos.size) }
        LaunchedEffect(form.photos.size) {
            if (form.photos.size > previousCount) rowState.animateScrollToItem(previousCount)
            previousCount = form.photos.size
        }
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            itemsIndexed(form.photos, key = { _, item -> item.key }) { index, item ->
                PhotoTile(
                    item = item,
                    index = index,
                    count = form.photos.size,
                    onRetry = { onRetry(item.key) },
                    onRemove = { onRemove(item.key) },
                    onMove = { to -> onMove(item.key, to) },
                    onMakeCover = { onMakeCover(item.key) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (form.photosRemaining > 0) {
                item(key = "__add") { AddPhotoTile(empty = form.photos.isEmpty(), onClick = onAdd) }
            }
        }
        val failed = form.photos.firstOrNull { it.status == UploadStatus.Failed }
        val help = when {
            error != null -> error
            failed != null -> "A photo didn't upload: ${failed.error ?: "unknown error"}. " +
                if (failed.retryable) "Tap it to retry." else "Remove it and pick another."
            form.photos.size > 1 -> "The first photo is the cover. Tap a photo to reorder or remove it."
            else -> "Add up to ${Limits.PHOTOS_MAX} photos. Good light and a plain background sell faster."
        }
        Text(
            help,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null || failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val TileSize = 104.dp

@Composable
private fun AddPhotoTile(empty: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .size(TileSize)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            if (empty) "Add photos" else "Add more",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
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
    val description = "Photo ${index + 1}" + when (item.status) {
        UploadStatus.Uploading -> ", uploading"
        UploadStatus.Failed -> ", upload failed"
        UploadStatus.Uploaded -> if (index == 0) ", cover" else ""
    }
    Box(modifier.size(TileSize)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = description) { if (item.retryable) onRetry() else menuOpen = true },
        ) {
            PhotoPreview(item, Modifier.fillMaxSize())
            when (item.status) {
                UploadStatus.Uploading -> Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val progress = item.progress
                    if (progress != null && progress > 0f) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.inverseOnSurface, strokeWidth = 3.dp)
                    }
                }
                UploadStatus.Failed -> Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)),
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
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp),
        ) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove photo ${index + 1}", modifier = Modifier.size(16.dp))
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
                    text = { Text("Move left") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) },
                    onClick = { act { onMove(index - 1) } },
                )
            }
            if (index < count - 1) {
                DropdownMenuItem(
                    text = { Text("Move right") },
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
private fun PhotoPreview(item: PhotoItem, modifier: Modifier) {
    val local = item.local
    if (local != null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            AsyncImage(model = local.bytes, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    } else {
        RemoteImage(item.url, contentDescription = null, modifier = modifier)
    }
}
