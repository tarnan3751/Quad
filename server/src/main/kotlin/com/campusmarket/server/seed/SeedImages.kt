package com.campusmarket.server.seed

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** An encoded JPEG ready to be stored as a seed photo, with the dimensions its `photos` row records. */
class SeedImage(val bytes: ByteArray, val width: Int, val height: Int) {
    val contentType get() = "image/jpeg"

    /** Short content hash; part of the storage key, so changed bytes get a new URL (photo URLs are cached as immutable). */
    val hash: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun encode(img: BufferedImage, quality: Float): SeedImage {
            val out = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(img, null, null), param)
            }
            writer.dispose()
            return SeedImage(out.toByteArray(), img.width, img.height)
        }

        /** Wraps an already encoded JPEG, reading its size from the header (no pixel decoding). */
        fun fromJpeg(bytes: ByteArray): SeedImage {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { iis ->
                val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull { it.formatName.equals("jpeg", true) }
                    ?: throw IllegalArgumentException("Not a JPEG")
                try {
                    reader.input = iis
                    return SeedImage(bytes, reader.getWidth(0), reader.getHeight(0))
                } finally {
                    reader.dispose()
                }
            }
        }
    }
}

/** Product photos bundled with the server as classpath resources (see `seed/photos/CREDITS.md`). */
object SeedPhotos {
    /** Photo [n] (from 1; 1 is the cover) of the listing with [key]. */
    fun path(key: String, n: Int) = "seed/photos/$key-$n.jpg"

    fun load(key: String, n: Int): SeedImage? =
        SeedPhotos::class.java.classLoader.getResourceAsStream(path(key, n))?.use { SeedImage.fromJpeg(it.readBytes()) }
}
