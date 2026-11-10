package com.campusmarket.app.feature.profile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusmarket.app.ui.theme.CampusTheme
import com.campusmarket.app.ui.theme.Spacing

// Building blocks shared by the profile and moderation screens (feature-local on purpose: see docs/CLIENT.md).

/**
 * Icons that `material-icons-core` lacks. Same 24-unit grid and path data as the Material icons of the same name
 * (Apache 2.0), so they sit next to `Icons.*`.
 */
internal object Glyphs {
    val Flag = glyph("Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    val Block = glyph(
        "Block",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9," +
            "1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0," +
            "4.42 -3.58,8 -8,8z",
    )
    val ChatBubble = glyph("ChatBubble", "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z")
    val OpenInNew = glyph(
        "OpenInNew",
        "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 " +
            "1.41,1.41L19,6.41V10h2V3h-7z",
    )
    val Shield = glyph("Shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z")
    val Tag = glyph(
        "Tag",
        "M21.41,11.58l-9,-9C12.05,2.22 11.55,2 11,2H4c-1.1,0 -2,0.9 -2,2v7c0,0.55 0.22,1.05 0.59,1.42l9,9c0.36,0.36 0.86," +
            "0.58 1.41,0.58 0.55,0 1.05,-0.22 1.41,-0.59l7,-7c0.37,-0.36 0.59,-0.86 0.59,-1.41 0,-0.55 -0.23,-1.06 -0.59," +
            "-1.42zM5.5,7C4.67,7 4,6.33 4,5.5S4.67,4 5.5,4 7,4.67 7,5.5 6.33,7 5.5,7z",
    )
    val Receipt = glyph(
        "Receipt",
        "M18,17H6v-2h12v2zM18,13H6v-2h12v2zM18,9H6V7h12v2zM3,22l1.5,-1.5L6,22l1.5,-1.5L9,22l1.5,-1.5L12,22l1.5,-1.5L15,22" +
            "l1.5,-1.5L18,22l1.5,-1.5L21,22V2l-1.5,1.5L18,2l-1.5,1.5L15,2l-1.5,1.5L12,2l-1.5,1.5L9,2 7.5,3.5 6,2 4.5,3.5 3,2v20z",
    )
    val Wallet = glyph(
        "Wallet",
        "M21,18v1c0,1.1 -0.9,2 -2,2H5c-1.11,0 -2,-0.9 -2,-2V5c0,-1.1 0.89,-2 2,-2h14c1.1,0 2,0.9 2,2v1h-9c-1.11,0 -2,0.9 " +
            "-2,2v8c0,1.1 0.89,2 2,2h9zM12,16h10V8H12v8zM16,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 " +
            "1.5,1.5 -0.67,1.5 -1.5,1.5z",
    )
    val Gavel = glyph(
        "Gavel",
        "M1,21h12v2H1zM5.245,8.07l2.83,-2.827 14.14,14.142 -2.828,2.828zM12.317,1l5.657,5.656 -2.83,2.83 -5.654,-5.66z" +
            "M3.825,9.485l5.657,5.657 -2.828,2.828 -5.657,-5.657z",
    )
    val History = glyph(
        "History",
        "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 " +
            "-3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28," +
            "2.54 0.72,-1.21 -3.5,-2.08V8H12z",
    )
    val Group = glyph(
        "Group",
        "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66," +
            "5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29," +
            "0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z",
    )
    val Camera = glyph(
        "PhotoCamera",
        "M12,15.2A3.2,3.2 0,1 0,12 8.8a3.2,3.2 0,1 0,0 6.4zM9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2," +
            "-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
    )
    val School = glyph("School", "M5,13.18v4L12,21l7,-3.82v-4L12,17l-7,-3.82zM12,3L1,9l11,6 9,-4.91V17h2V9L12,3z")
    val Document = glyph(
        "Description",
        "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8" +
            "v2zM13,9V3.5L18.5,9H13z",
    )
    val Visibility = glyph(
        "Visibility",
        "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76," +
            "0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34," +
            "-3 -3,-3z",
    )
    val Verified = glyph(
        "Verified",
        "M23,12l-2.44,-2.79 0.34,-3.69 -3.61,-0.82 -1.89,-3.2L12,2.96 8.6,1.5 6.71,4.69 3.1,5.5l0.34,3.7L1,12l2.44,2.79 " +
            "-0.34,3.7 3.61,0.82L8.6,22.5l3.4,-1.47 3.4,1.46 1.89,-3.19 3.61,-0.82 -0.34,-3.69L23,12zM10.09,16.72l-3.8,-3.81 " +
            "1.48,-1.48 2.32,2.33 5.85,-5.87 1.48,1.48 -7.33,7.35z",
    )
    val Undo = glyph(
        "Undo",
        "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6," +
            "5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
    )

    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}

// ---------------------------------------------------------------------------------------------------- menu

/** A titled card of [MenuRow]s (the grouped-list look of the profile tab and settings). */
@Composable
internal fun MenuGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xl, bottom = Spacing.sm).semantics { heading() },
            )
        } else {
            Box(Modifier.height(Spacing.lg))
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** Hairline between two rows of a [MenuGroup], inset past the icon. */
@Composable
internal fun MenuDivider() {
    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}

/**
 * One row of a [MenuGroup]: tinted icon tile, title, optional supporting line, then [trailing] (a chevron by
 * default, so it reads as "opens a screen"). Rows without [onClick] are plain information.
 */
@Composable
internal fun MenuRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = if (onClick != null) ({ Chevron() }) else null,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val tileColor = if (destructive) scheme.errorContainer else scheme.primaryContainer
    val iconColor = if (destructive) scheme.onErrorContainer else scheme.onPrimaryContainer
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            Modifier.size(32.dp).background(tileColor, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (destructive) scheme.error else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Trailing mark for rows that open a web page in the browser. */
@Composable
internal fun ExternalMark() {
    Icon(Glyphs.OpenInNew, contentDescription = "Opens in your browser", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
}

// ---------------------------------------------------------------------------------------------------- stats

/** A number with its label ("3" / "Active listings"); tappable when [onClick] is set. [star] puts a rating star before it. */
internal data class Stat(
    val value: String,
    val label: String,
    val onClick: (() -> Unit)? = null,
    val description: String = "$value $label",
    val star: Boolean = false,
)

/** A card with up to three [Stat]s split by hairlines. [loading] shows dashes. */
@Composable
internal fun StatsCard(stats: List<Stat>, modifier: Modifier = Modifier, loading: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            stats.forEachIndexed { i, stat ->
                if (i > 0) VerticalDivider(Modifier.fillMaxHeight().padding(vertical = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
                StatCell(stat, loading)
            }
        }
    }
}

@Composable
private fun RowScope.StatCell(stat: Stat, loading: Boolean) {
    Column(
        Modifier.weight(1f)
            .then(if (stat.onClick != null && !loading) Modifier.clickable(role = Role.Button, onClick = stat.onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.xs, vertical = Spacing.md)
            .clearAndSetSemantics { contentDescription = if (loading) "Loading ${stat.label}" else stat.description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (stat.star && !loading) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = CampusTheme.colors.star, modifier = Modifier.size(20.dp))
            }
            Text(
                if (loading) "–" else stat.value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
        }
        Text(
            stat.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

// ---------------------------------------------------------------------------------------------------- skeleton

/** A softly pulsing placeholder block in the shape of content that is still loading. */
@Composable
internal fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Box(modifier.alpha(pulse).background(MaterialTheme.colorScheme.surfaceContainerHighest, shape))
}

/** Placeholder rows for a list of people or items: [rows] × (circle/square + two lines). */
@Composable
internal fun SkeletonRows(rows: Int = 6, round: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier.semantics { contentDescription = "Loading" }) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                SkeletonBlock(Modifier.size(44.dp), if (round) RoundedCornerShape(50) else MaterialTheme.shapes.small)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(14.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(10.dp))
                }
            }
        }
    }
}
