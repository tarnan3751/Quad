package com.campusmarket.app.platform

import coil3.PlatformContext
import coil3.memory.MemoryCache
import okio.FileSystem
import okio.Path

internal actual fun imageDiskCacheDirectory(context: PlatformContext): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coil3_disk_cache"

internal actual fun imageMemoryCache(context: PlatformContext): MemoryCache =
    MemoryCache.Builder().maxSizeBytes(96L * 1024 * 1024).build()
