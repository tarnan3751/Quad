package com.campusmarket.app.platform

import coil3.PlatformContext
import coil3.memory.MemoryCache
import okio.Path

/** Where downloaded photos are kept between launches: the platform's cache directory, which the OS may clear. */
internal expect fun imageDiskCacheDirectory(context: PlatformContext): Path

/** The decoded-image memory cache, sized for the platform. */
internal expect fun imageMemoryCache(context: PlatformContext): MemoryCache
