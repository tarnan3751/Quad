package com.campusmarket.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * Material's indicator; unselected tabs are muted. [badgeCount] > 0 shows a count badge (99+ beyond that) and
 * [badgeDot] a plain dot; screen readers hear "[label], [badgeDescription]" (default "N new").
 */
@Composable
fun RowScope.AppNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    badgeDot: Boolean = false,
    badgeDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val progress by animateFloatAsState(if (selected) 1f else 0f, label = "tabIndicator")
    val brush = CampusTheme.actionGradient
    val haptics = LocalHapticFeedback.current
    val hasBadge = badgeCount > 0 || badgeDot
    val badgeText = badgeDescription ?: if (badgeCount > 0) "$badgeCount new" else "New"
    NavigationBarItem(
        selected = selected,
        onClick = {
            if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        },
        modifier = modifier,
        icon = {
            BadgedBox(
                badge = {
                    if (hasBadge) {
                        // Material clears the icon's semantics when the label shows, so the label carries the badge.
                        CountBadge(
                            count = badgeCount,
                            modifier = Modifier
                                .border(1.5.dp, scheme.surfaceContainer, CampusTheme.pill)
                                .clearAndSetSemantics { },
                        )
                    }
                },
            ) {
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
            }
        },
        label = {
            Text(
                label,
                modifier = if (hasBadge) Modifier.semantics { contentDescription = "$label, $badgeText" } else Modifier,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = scheme.onSurface,
            indicatorColor = Color.Transparent,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
    )
}

/**
 * Small berry count badge ("3", "99+"), or a dot when [count] is 0. For unread counts on tabs, rows and icons.
 * Give it a `contentDescription` (e.g. "3 unread") through [modifier] where the number alone isn't clear.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (count > 0) {
        Badge(modifier = modifier, containerColor = scheme.secondary, contentColor = scheme.onSecondary) {
            Text(if (count > 99) "99+" else count.toString(), fontWeight = FontWeight.Bold)
        }
    } else {
        Badge(modifier = modifier.size(10.dp), containerColor = scheme.secondary)
    }
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
