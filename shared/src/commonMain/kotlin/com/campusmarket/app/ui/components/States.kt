package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow

/** Full-area spinner. For content with a known shape prefer a skeleton (`ListSkeleton`, `ListingGridSkeleton`). */
@Composable
fun LoadingState(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier.semantics { contentDescription = "Loading" }, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/**
 * Full-area "nothing here": an icon, a title, one or two sentences and (ideally) the one action that gets the user
 * going. [actionLabel] is a primary button; [secondaryActionLabel] a quieter one under it.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    message: String? = null,
    icon: ImageVector = Icons.Default.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    MessageState(
        icon, title, message, modifier,
        badgeColor = MaterialTheme.colorScheme.primaryContainer,
        iconColor = MaterialTheme.colorScheme.primary,
    ) {
        if (actionLabel != null && onAction != null) PrimaryButton(actionLabel, onAction)
        if (secondaryActionLabel != null && onSecondaryAction != null) SecondaryButton(secondaryActionLabel, onSecondaryAction)
    }
}

/** Full-area error with Retry. Use the [ApiError] overload for API failures. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    title: String = "Something went wrong",
    onRetry: (() -> Unit)? = null,
    icon: ImageVector = Icons.Default.Warning,
) {
    MessageState(
        icon, title, message, modifier,
        badgeColor = MaterialTheme.colorScheme.errorContainer,
        iconColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        if (onRetry != null) SecondaryButton("Try again", onRetry, icon = Icons.Default.Refresh)
    }
}

/** [ErrorState] worded for the failure: offline (cloud icon, neutral), not found, or anything else. */
@Composable
fun ErrorState(error: ApiError, modifier: Modifier = Modifier.fillMaxSize(), onRetry: (() -> Unit)? = null) {
    when {
        error.isNetwork -> MessageState(
            QuadIcons.CloudOff, "You're offline", error.message, modifier,
            badgeColor = CampusTheme.colors.neutralContainer,
            iconColor = CampusTheme.colors.onNeutralContainer,
        ) {
            if (onRetry != null) SecondaryButton("Try again", onRetry, icon = Icons.Default.Refresh)
        }
        error.isNotFound -> ErrorState(error.message, modifier, "Not found", onRetry)
        else -> ErrorState(error.message, modifier, "Something went wrong", onRetry)
    }
}

/**
 * The shared layout of empty / error states. Centred in the space it's given, and scrolls instead of clipping when
 * that space is short (landscape, large text, the keyboard open).
 */
@Composable
private fun MessageState(
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier,
    badgeColor: Color,
    iconColor: Color,
    actions: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Only a bounded box can scroll; inside a lazy list item the height is unbounded and the list scrolls instead.
        val fill = if (constraints.hasBoundedHeight) {
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
        } else {
            Modifier
        }
        Column(
            fill.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                Modifier.widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Surface(shape = CircleShape, color = badgeColor, contentColor = iconColor, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp)) }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    actions()
                }
            }
        }
    }
}

/**
 * Form-level (non-field) error, e.g. "Wrong email or password": a soft red banner with an alert icon. Announced by
 * screen readers when it appears.
 */
@Composable
fun InlineError(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md), verticalAlignment = Alignment.Top) {
            Icon(QuadIcons.ErrorOutline, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/**
 * A neutral notice banner ("Your session has ended. Please sign in again.", "Payment is on hold"): the same shape
 * as [InlineError] in quiet colours, for information that isn't a failure.
 */
@Composable
fun InfoBanner(message: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Default.Info) {
    Surface(
        color = CampusTheme.colors.neutralContainer,
        contentColor = CampusTheme.colors.onNeutralContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/**
 * Renders a [UiState]: [loading] while loading (a spinner unless you pass a skeleton), [ErrorState] with retry on
 * error, [content] otherwise.
 *
 * ```
 * val state by vm.listing.collectAsStateWithLifecycle()
 * UiStateContent(state, onRetry = vm::reload) { listing -> ListingBody(listing) }
 * ```
 */
@Composable
fun <T> UiStateContent(
    state: UiState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    loading: @Composable () -> Unit = { LoadingState(modifier) },
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> loading()
        is UiState.Error -> ErrorState(state.error, modifier, onRetry)
        is UiState.Content -> content(state.value)
    }
}

/** Collects one-off view-model events (see [com.campusmarket.app.ui.state.EventQueue]) while the screen is shown. */
/** Runs [action] whenever the screen resumes, the first time included, for as long as [key] stays the same. */
@Composable
fun OnResume(key: Any?, action: () -> Unit) {
    LifecycleResumeEffect(key) {
        action()
        onPauseOrDispose { }
    }
}

@Composable
fun <E> CollectEvents(events: Flow<E>, onEvent: suspend (E) -> Unit) {
    val handler by rememberUpdatedState(onEvent)
    LaunchedEffect(events) { events.collect { handler(it) } }
}
