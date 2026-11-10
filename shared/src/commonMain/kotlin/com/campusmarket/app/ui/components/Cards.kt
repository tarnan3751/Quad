package com.campusmarket.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusmarket.core.UserPublic

/** Avatar + name + rating (seller card, order parties, report targets, blocked users). */
@Composable
fun UserRow(
    user: UserPublic,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickableRow(onClick) else Modifier
    ListItem(
        modifier = modifier.then(clickModifier),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = { Avatar(user.avatarUrl, user.displayName, size = 44.dp, contentDescription = null) },
        headlineContent = { Text(user.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (supporting != null) Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else RatingSummary(user.ratingAverage, user.ratingCount)
        },
        trailingContent = trailing,
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
