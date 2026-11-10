package com.campusmarket.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.campusmarket.app.platform.imageDiskCacheDirectory
import com.campusmarket.app.platform.imageMemoryCache
import com.campusmarket.app.ui.theme.GoldenHour
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Photo
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic

/**
 * Turns server URLs into URLs this device can load (see `ServerConfig.resolve`). Provided at the app root;
 * [RemoteImage] applies it for you.
 */
val LocalUrlResolver = staticCompositionLocalOf<(String?) -> String?> { { it } }

/*
 * Which size of a photo to load (docs/SERVER_NOTES.md "Photos"). The server's resized variants are optional, so each
 * falls back to the original. A grid tile or row needs a few hundred pixels: the original is up to 9x the bytes.
 */

/** Grid tiles and list rows (inbox, orders, moderation): the small variant of the listing's first photo. */
val ListingSummary.listImageUrl: String? get() = thumbnailSmallUrl ?: thumbnailUrl

/** Avatars (never shown larger than ~112 dp). */
val UserPublic.avatarImageUrl: String? get() = avatarThumbnailUrl ?: avatarUrl

/** Avatars (never shown larger than ~112 dp). */
val UserPrivate.avatarImageUrl: String? get() = avatarThumbnailUrl ?: avatarUrl

/** A photo filling the screen width (the listing pager). The zoomable full-screen viewer keeps [Photo.url]. */
val Photo.screenImageUrl: String get() = displayUrl ?: url

/** Small previews (the listing editor's photo strip). */
val Photo.smallImageUrl: String get() = thumbnailUrl ?: url

/**
 * The app's one Coil [ImageLoader] (installed by `App()`): decoded images in a platform-sized memory cache, downloads
 * in a disk cache under the platform's cache directory (photo URLs are immutable, so a cached file is never stale),
 * a short crossfade for images that come from disk or network. Requests are sized to the composable, decoded off the
 * main thread and keyed by URL, so scrolling back to a tile reuses its bitmap.
 */
fun newImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        .memoryCache { imageMemoryCache(context) }
        .diskCache {
            DiskCache.Builder()
                .directory(imageDiskCacheDirectory(context))
                .maxSizeBytes(128L * 1024 * 1024)
                .build()
        }
        .crossfade(CROSSFADE_MS)
        .build()

private const val CROSSFADE_MS = 150

private enum class ImageLoad { Loading, Loaded, Failed }

/**
 * Network image (Coil 3). While it loads the area shimmers; if [url] is null or the image can't be loaded it shows
 * [placeholderIcon] on a neutral fill (pass null for a plain fill). Size / aspect ratio / clipping via [modifier].
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderIcon: ImageVector? = QuadIcons.Image,
) {
    val resolved = LocalUrlResolver.current(url)
    var load by remember(resolved) { mutableStateOf(if (resolved == null) ImageLoad.Failed else ImageLoad.Loading) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        when (load) {
            ImageLoad.Loading -> SkeletonBox(Modifier.matchParentSize(), shape = RectangleShape)
            ImageLoad.Failed -> if (placeholderIcon != null) {
                Icon(
                    placeholderIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(28.dp),
                )
            }
            ImageLoad.Loaded -> Unit
        }
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    load = when (state) {
                        is AsyncImagePainter.State.Success -> ImageLoad.Loaded
                        is AsyncImagePainter.State.Error -> ImageLoad.Failed
                        else -> ImageLoad.Loading
                    }
                },
            )
        }
    }
}

/**
 * Round avatar: the photo, or initials on a Golden Hour colour picked from the name (so a person keeps their colour
 * everywhere). The initials also show while the photo loads and if it fails. Every pairing is ≥ 4.5:1.
 * [contentDescription] defaults to the name; pass null when the name is already shown next to it.
 */
@Composable
fun Avatar(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = name,
) {
    val resolved = LocalUrlResolver.current(url)
    val (container, content) = AvatarPalette[avatarIndex(name)]
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            color = content,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.38f).sp,
            letterSpacing = (-0.02).em,
            maxLines = 1,
        )
        if (resolved != null) {
            AsyncImage(model = resolved, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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

internal fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}
