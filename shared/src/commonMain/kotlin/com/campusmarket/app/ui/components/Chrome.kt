package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.campusmarket.app.ui.theme.Spacing

/** Standard top bar. Pass [onBack] on every non-tab screen. */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = { if (onBack != null) BackButton(onBack) },
        actions = actions,
        scrollBehavior = scrollBehavior,
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

/** Section title inside a scrolling screen ("Ratings", "More from this seller"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(
        modifier.padding(start = Spacing.screen, end = Spacing.sm, top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/**
 * Yes/no confirmation. Use [destructive] for deletes / cancellations (confirm button turns red).
 * Keep it shown while the action runs by passing [loading].
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
) {
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !loading,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
            ) {
                if (loading) CircularProgressIndicator(Modifier.padding(end = 8.dp).then(Modifier), strokeWidth = 2.dp)
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(dismissLabel) }
        },
    )
}
