package com.campusmarket.app.platform

import coil3.PlatformContext
import coil3.memory.MemoryCache
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// Library/Caches (not tmp/, which iOS empties far more eagerly).
internal actual fun imageDiskCacheDirectory(context: PlatformContext): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
    return (caches?.toPath() ?: FileSystem.SYSTEM_TEMPORARY_DIRECTORY) / "image_cache"
}

// A fixed budget: a percentage of an iPhone's physical memory would be far more than a foreground app should hold.
internal actual fun imageMemoryCache(context: PlatformContext): MemoryCache =
    MemoryCache.Builder().maxSizeBytes(64L * 1024 * 1024).build()
