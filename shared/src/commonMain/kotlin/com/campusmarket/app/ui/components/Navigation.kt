package com.campusmarket.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.theme.CampusTheme

/** The bottom tab bar: a quiet card-coloured bar with a hairline on top. Fill it with [AppNavigationBarItem]s. */
@Composable
fun AppNavigationBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            content = content,
        )
    }
}

/**
 * A tab: the selected one sits in a pill of the Golden Hour action gradient (white icon) that grows in like
 * Material's indicator; unselected tabs are muted.
 */
@Composable
fun RowScope.AppNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val progress by animateFloatAsState(if (selected) 1f else 0f, label = "tabIndicator")
    val brush = CampusTheme.actionGradient
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        icon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.drawBehind {
                    if (progress > 0f) {
                        // Material's indicator is 64 × 32 dp around the 24 dp icon.
                        val w = 64.dp.toPx() * (0.5f + 0.5f * progress)
                        val h = 32.dp.toPx()
                        drawRoundRect(
                            brush = brush,
                            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(h / 2f),
                            alpha = progress,
                        )
                    }
                },
            )
        },
        label = { Text(label, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = scheme.onSurface,
            indicatorColor = Color.Transparent,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
    )
}

/** A tab for Material's (Primary)TabRow in the kit's colours: muted label when unselected, brand colour (bold) when selected. */
@Composable
fun AppTab(selected: Boolean, onClick: () -> Unit, text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        selectedContentColor = scheme.primary,
        unselectedContentColor = scheme.onSurfaceVariant,
        text = { Text(text, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold) },
    )
}
