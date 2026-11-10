package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.theme.CampusTheme

/** "$12.50" in the price colour (berry on light, amber on dark), Plus Jakarta Sans 800 with tight tracking. */
@Composable
fun PriceText(
    cents: Int,
    modifier: Modifier = Modifier,
    currency: String = "usd",
    style: TextStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, letterSpacing = (-0.03).em),
    color: Color = CampusTheme.colors.price,
) {
    Text(Format.price(cents, currency), modifier = modifier, style = style, color = color, fontWeight = FontWeight.ExtraBold, maxLines = 1)
}

/** Read-only 0..5 stars with fractional fill (4.5 shows four and a half). */
@Composable
fun RatingStars(rating: Double, modifier: Modifier = Modifier, starSize: Dp = 16.dp) {
    val empty = MaterialTheme.colorScheme.outlineVariant
    val filled = CampusTheme.colors.star
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 0 until 5) {
            val fraction = (rating - i).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.size(starSize)) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = empty, modifier = Modifier.size(starSize))
                if (fraction > 0f) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = filled,
                        modifier = Modifier.size(starSize).drawWithContent {
                            clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() }
                        },
                    )
                }
            }
        }
    }
}

/** Stars + "4.8 (12)", or "No ratings yet". Matches `UserPublic.ratingAverage / ratingCount`. */
@Composable
fun RatingSummary(average: Double?, count: Int, modifier: Modifier = Modifier, starSize: Dp = 14.dp) {
    // Screen readers hear "Rated 4.8 out of 5, 12 ratings" rather than five unlabeled stars and "4.8 (12)".
    val spoken = if (average == null || count == 0) {
        Modifier
    } else {
        Modifier.clearAndSetSemantics {
            contentDescription = "Rated ${Format.rating(average)} out of 5, $count rating${if (count == 1) "" else "s"}"
        }
    }
    Row(
        modifier.then(spoken),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (average == null || count == 0) {
            Text("No ratings yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            RatingStars(average, starSize = starSize)
            Text(
                "${Format.rating(average)} ($count)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tap-to-rate 1..5 stars (0 = none chosen yet). */
@Composable
fun RatingInput(stars: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, starSize: Dp = 36.dp, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onChange(i)
                },
                enabled = enabled,
                // The chosen star reads as selected, so a screen reader can tell the current rating.
                modifier = Modifier.size(starSize + 12.dp).semantics { selected = i == stars },
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "$i star${if (i > 1) "s" else ""}",
                    tint = if (i <= stars) CampusTheme.colors.star else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(starSize),
                )
            }
        }
    }
}
