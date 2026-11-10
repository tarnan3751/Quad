package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing

/**
 * Standard top bar. Pass [onBack] on every non-tab screen. The title is a heading for screen readers. It turns
 * card-coloured once content scrolls under it when you pass a [scrollBehavior]
 * (`TopAppBarDefaults.pinnedScrollBehavior()` + `Modifier.nestedScroll(behavior.nestedScrollConnection)`).
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (title.isNotBlank()) Modifier.semantics { heading() } else Modifier,
            )
        },
        modifier = modifier,
        navigationIcon = { if (onBack != null) BackButton(onBack) },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

/**
 * Up arrow. Taps only count while this screen is the RESUMED back-stack entry: a quick second tap during the pop
 * transition would otherwise pop the screen underneath too (and on a tab's first screen empty the back stack, leaving
 * a blank page).
 */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = dropUnlessResumed(block = onClick)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/**
 * Yes/no confirmation. Use [destructive] for deletes / cancellations: the confirm action becomes a solid button in
 * the theme's error colour (see [DialogConfirmButton]) and Cancel stays a neutral text button, so the two never look
 * alike. Keep it shown while the action runs by passing [loading].
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    loading: Boolean = false,
    icon: ImageVector? = null,
) = ConfirmDialog(title, confirmLabel, onConfirm, onDismiss, dismissLabel, destructive, loading, icon = icon) { Text(message) }

/**
 * [ConfirmDialog] with richer [content] (bullets, a field, an `InlineError`) in place of the message; it scrolls when
 * it is taller than the dialog can be (landscape, large text).
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    loading: Boolean = false,
    confirmEnabled: Boolean = true,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        icon = icon?.let {
            { Icon(it, contentDescription = null, tint = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current) }
        },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
        },
        confirmButton = {
            DialogConfirmButton(
                confirmLabel,
                onClick = {
                    haptics.performHapticFeedback(if (destructive) HapticFeedbackType.LongPress else HapticFeedbackType.Confirm)
                    onConfirm()
                },
                destructive = destructive,
                enabled = confirmEnabled,
                loading = loading,
            )
        },
        dismissButton = { DialogDismissButton(dismissLabel, onDismiss, enabled = !loading) },
    )
}

/**
 * The confirm action of a dialog. [destructive] (delete, remove, suspend, cancel an order): a solid pill in the
 * theme's error colour. A red text button is not enough: the brand's berry text buttons are almost the same colour.
 * Pair it with [DialogDismissButton].
 */
@Composable
fun DialogConfirmButton(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(label)
    }
    if (destructive) {
        Button(
            onClick = onClick,
            enabled = enabled && !loading,
            shape = CampusTheme.pill,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.error,
                contentColor = scheme.onError,
                // Working keeps its colour; not yet allowed (e.g. the confirmation word isn't typed) is plainly off.
                disabledContainerColor = if (loading) scheme.error else scheme.onSurface.copy(alpha = 0.10f),
                disabledContentColor = if (loading) scheme.onError else scheme.onSurface.copy(alpha = 0.45f),
            ),
            elevation = null,
            contentPadding = PaddingValues(horizontal = 20.dp),
            content = content,
        )
    } else {
        TextButton(onClick = onClick, enabled = enabled && !loading, content = content)
    }
}

/** The way out of a dialog: a neutral text button, so it can't be mistaken for the action. */
@Composable
fun DialogDismissButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) { Text(label) }
}

/** What a [UserMessages][com.campusmarket.app.ui.state.UserMessages] snackbar shows; errors get an alert icon. */
class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    val isError: Boolean = false,
) : SnackbarVisuals

/** The app's snackbar: rounded, "inverse" colours, alert icon for errors. Use as `SnackbarHost(state) { AppSnackbar(it) }`. */
@Composable
fun AppSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val isError = (data.visuals as? AppSnackbarVisuals)?.isError == true
    Snackbar(
        modifier = modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        shape = MaterialTheme.shapes.small,
        containerColor = scheme.inverseSurface,
        contentColor = scheme.inverseOnSurface,
        actionContentColor = scheme.inversePrimary,
        dismissActionContentColor = scheme.inverseOnSurface,
        action = data.visuals.actionLabel?.let { label ->
            { TextButton(onClick = data::performAction) { Text(label, color = scheme.inversePrimary) } }
        },
        dismissAction = if (data.visuals.withDismissAction) {
            {
                IconButton(onClick = data::dismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
        } else {
            null
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isError) {
                Icon(QuadIcons.ErrorOutline, contentDescription = "Error", modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.md))
            }
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
