package com.campusmarket.app.platform

import coil3.PlatformContext
import coil3.memory.MemoryCache
import okio.Path
import okio.Path.Companion.toOkioPath

internal actual fun imageDiskCacheDirectory(context: PlatformContext): Path = context.cacheDir.toOkioPath() / "image_cache"

// A quarter of the app's heap class (about 64 MB on a typical phone): a few screens of tiles stay decoded.
internal actual fun imageMemoryCache(context: PlatformContext): MemoryCache =
    MemoryCache.Builder().maxSizePercent(context, 0.25).build()
