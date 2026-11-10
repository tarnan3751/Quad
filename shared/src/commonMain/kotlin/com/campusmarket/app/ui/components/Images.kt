package com.campusmarket.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.campusmarket.app.ui.theme.GoldenHour

/**
 * Turns server URLs into URLs this device can load (see `ServerConfig.resolve`). Provided at the app root;
 * [RemoteImage] applies it for you.
 */
val LocalUrlResolver = staticCompositionLocalOf<(String?) -> String?> { { it } }

/**
 * Network image (Coil 3) with a neutral placeholder while loading, on error, or when [url] is null.
 * Pass sizes / aspect ratio / clipping via [modifier].
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderIcon: ImageVector? = Icons.Default.ShoppingCart,
) {
    val resolved = LocalUrlResolver.current(url)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (resolved == null) {
            if (placeholderIcon != null) {
                Icon(
                    placeholderIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            AsyncImage(
                model = resolved,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Round avatar; falls back to initials on a Golden Hour colour picked from the name (so a person keeps their colour
 * everywhere). Every pairing is ≥ 4.5:1.
 */
@Composable
fun Avatar(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val shapeModifier = modifier.size(size).clip(CircleShape)
    if (url != null) {
        RemoteImage(url, contentDescription = name, modifier = shapeModifier, placeholderIcon = null)
    } else {
        val (container, content) = AvatarPalette[avatarIndex(name)]
        Box(shapeModifier.background(container), contentAlignment = Alignment.Center) {
            Text(
                initials(name),
                color = content,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.38f).sp,
                letterSpacing = (-0.02).em,
            )
        }
    }
}

/** Fallback avatar colours: amber, dusk pink, berry, deep berry, coral (container to initials). Same in both themes. */
private val AvatarPalette = listOf(
    GoldenHour.Amber to GoldenHour.Ink,
    GoldenHour.DuskPink to GoldenHour.Ink,
    GoldenHour.Berry to Color.White,
    GoldenHour.DeepBerry to Color.White,
    Color(0xFFFF9166) to GoldenHour.Ink,
)

private fun avatarIndex(name: String): Int {
    var h = 0
    for (ch in name.trim().lowercase()) h = 31 * h + ch.code
    return (h and Int.MAX_VALUE) % AvatarPalette.size
}

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}
