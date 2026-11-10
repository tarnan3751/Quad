package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow

/** Full-area spinner. */
@Composable
fun LoadingState(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Full-area "nothing here" with optional call to action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    message: String? = null,
    icon: ImageVector = Icons.Default.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    MessageState(
        icon, title, message, actionLabel, onAction, modifier,
        badgeColor = MaterialTheme.colorScheme.primaryContainer,
        iconColor = MaterialTheme.colorScheme.primary,
        primaryAction = true,
    )
}

/** Full-area error with Retry. Use the [ApiError] overload for API failures. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    title: String = "Something went wrong",
    onRetry: (() -> Unit)? = null,
) {
    MessageState(
        Icons.Default.Warning, title, message, if (onRetry != null) "Try again" else null, onRetry, modifier,
        badgeColor = MaterialTheme.colorScheme.errorContainer,
        iconColor = MaterialTheme.colorScheme.onErrorContainer,
        primaryAction = false,
    )
}

@Composable
fun ErrorState(error: ApiError, modifier: Modifier = Modifier.fillMaxSize(), onRetry: (() -> Unit)? = null) {
    val title = when {
        error.isNetwork -> "You're offline"
        error.isNotFound -> "Not found"
        else -> "Something went wrong"
    }
    ErrorState(error.message, modifier, title, onRetry)
}

@Composable
private fun MessageState(
    icon: ImageVector,
    title: String,
    message: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
    badgeColor: Color,
    iconColor: Color,
    primaryAction: Boolean,
) {
    Box(modifier.padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Surface(shape = CircleShape, color = badgeColor, contentColor = iconColor, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp)) }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(Spacing.sm))
                // An empty screen's one way forward is a call to action; a retry stays secondary.
                if (primaryAction) PrimaryButton(actionLabel, onAction)
                else SecondaryButton(actionLabel, onAction, icon = Icons.Default.Refresh)
            }
        }
    }
}

/** Small red message for a form-level (non-field) error, e.g. "Wrong email or password". */
@Composable
fun InlineError(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

/**
 * Renders a [UiState]: spinner while loading, [ErrorState] with retry on error, [content] otherwise.
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
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> LoadingState(modifier)
        is UiState.Error -> ErrorState(state.error, modifier, onRetry)
        is UiState.Content -> content(state.value)
    }
}

/** Collects one-off view-model events (see [com.campusmarket.app.ui.state.EventQueue]) while the screen is shown. */
@Composable
fun <E> CollectEvents(events: Flow<E>, onEvent: suspend (E) -> Unit) {
    val handler by rememberUpdatedState(onEvent)
    LaunchedEffect(events) { events.collect { handler(it) } }
}
