package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.campusmarket.app.ui.theme.Spacing

/**
 * TEMPORARY stand-in for screens that aren't built yet. Shows the route arguments and buttons to reach the
 * destinations this screen will link to, so the navigation shell can be exercised end to end.
 * Feature owners: replace the call with your real screen and delete nothing else.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    owner: String,
    onBack: (() -> Unit)?,
    details: List<String> = emptyList(),
    links: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Scaffold(topBar = { AppTopBar(title, onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            StatusChip("Not built yet · $owner", ChipTone.Warning)
            details.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            links.forEach { (label, action) ->
                SecondaryButton(label, onClick = action, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
