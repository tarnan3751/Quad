package com.campusmarket.server.photos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Storage keys of a photo's variants; null where there is none (the original can't be decoded, or is missing). */
data class VariantKeys(val thumb: String?, val display: String?) {
    companion object {
        val NONE = VariantKeys(null, null)
    }
}

/**
 * Server-made JPEG sizes of stored photos, so apps don't download full-size originals for small views:
 *  - thumbnail: [THUMB_SHORT_SIDE] px on the short side, at most [MAX_LONG_SIDE] px on the long side;
 *  - display: at most [MAX_LONG_SIDE] px on the long side.
 * Variants are upright (the EXIF orientation is applied), carry no metadata, have transparency flattened onto white,
 * and are never larger than the original. A variant that would be identical to the original (a JPEG without
 * orientation that is already small enough) is the original itself, and a thumbnail as large as the display variant
 * is that variant. Keys derive from the original's (`<stem>-t.jpg`, `<stem>-d.jpg`), so making them is idempotent.
 */
class PhotoVariants(private val storage: PhotoStorage, permits: Int = 2) {
    /** Bounds concurrent decodes (each holds a raster of up to [DECODE_LONG_SIDE]² pixels). */
    private val decodes = Semaphore(permits)

    companion object {
        const val THUMB_SHORT_SIDE = 480
        const val MAX_LONG_SIDE = 1280
        private const val QUALITY = 0.8f

        /** Decoding subsamples big originals down to at most this long side (twice the largest variant). */
        private const val DECODE_LONG_SIDE = 2 * MAX_LONG_SIDE

        private val log = LoggerFactory.getLogger(PhotoVariants::class.java)

        init {
            System.setProperty("java.awt.headless", "true")
        }

        fun thumbKey(original: String) = original.substringBeforeLast('.') + "-t.jpg"
        fun displayKey(original: String) = original.substringBeforeLast('.') + "-d.jpg"

        /** Upright size of the display variant of an upright [w] x [h] original. */
        fun displaySize(w: Int, h: Int): ImageSize = scaled(w, h, MAX_LONG_SIDE.toDouble() / max(w, h))

        /** Upright size of the thumbnail of an upright [w] x [h] original. */
        fun thumbSize(w: Int, h: Int): ImageSize =
            scaled(w, h, min(THUMB_SHORT_SIDE.toDouble() / min(w, h), MAX_LONG_SIDE.toDouble() / max(w, h)))

        private fun scaled(w: Int, h: Int, factor: Double): ImageSize {
            val f = min(1.0, factor)
            return ImageSize(max(1, (w * f).roundToInt()), max(1, (h * f).roundToInt()))
        }
    }

    private class Source(val width: Int, val height: Int, val orientation: Int) {
        val swapped get() = orientation in 5..8
        val upright get() = if (swapped) ImageSize(height, width) else ImageSize(width, height)
    }

    /**
     * Makes (or finds) the variants of the stored original [key]. Returns [VariantKeys.NONE] when the original is
     * missing or can't be decoded (WebP, CMYK JPEG, corrupt data): that is final. Storage failures are thrown.
     */
    suspend fun make(key: String): VariantKeys {
        val format = PhotoKeys.formatOf(key) ?: return VariantKeys.NONE
        if (format == ImageFormat.WEBP) return VariantKeys.NONE // no WebP decoder in the JDK
        val original = storage.open(key) ?: return VariantKeys.NONE
        val source = withContext(Dispatchers.IO) { probe(original, format) } ?: return VariantKeys.NONE
        val upright = source.upright
        val reusable = format == ImageFormat.JPEG && source.orientation == 1
        val displaySize = displaySize(upright.width, upright.height)
        val thumbSize = thumbSize(upright.width, upright.height)
        val display = if (reusable && displaySize == upright) key else displayKey(key)
        val thumb = when {
            reusable && thumbSize == upright -> key
            thumbSize == displaySize -> display
            else -> thumbKey(key)
        }
        val missing = listOf(display, thumb).distinct().filter { it != key && storage.open(it) == null }
        if (missing.isEmpty()) return VariantKeys(thumb, display)

        val made = decodes.withPermit {
            withContext(Dispatchers.IO) { render(original, source, displaySize, thumbSize.takeIf { thumb in missing && thumb != display }) }
        } ?: return VariantKeys.NONE
        if (display in missing) store(made.first, display)
        made.second?.let { store(it, thumb) }
        return VariantKeys(thumb, display)
    }

    /** Header-only look at the original: stored size and orientation; null if it isn't a decodable image. */
    private fun probe(original: StoredPhoto, format: ImageFormat): Source? = try {
        val orientation = if (format == ImageFormat.JPEG) original.inputStream().use { JpegSanitizer.orientation(it) } else 1
        withReader(original) { reader -> Source(reader.getWidth(0), reader.getHeight(0), orientation) }
            ?.takeIf { it.width.toLong() * it.height <= PhotoUploads.MAX_PIXELS }
    } catch (e: Exception) {
        if (!isUndecodable(e)) throw e
        log.debug("Photo can't be probed: {}", e.toString())
        null
    }

    /** The display-sized image and, if [thumbSize] is given, the thumbnail derived from it; null if undecodable. */
    private fun render(original: StoredPhoto, source: Source, displaySize: ImageSize, thumbSize: ImageSize?): Pair<BufferedImage, BufferedImage?>? {
        val decoded = try {
            withReader(original) { reader ->
                val long = max(source.width, source.height)
                val step = (long + DECODE_LONG_SIDE - 1) / DECODE_LONG_SIDE
                val param = reader.defaultReadParam.apply { if (step > 1) setSourceSubsampling(step, step, 0, 0) }
                reader.read(0, param)
            }
        } catch (e: Exception) {
            if (!isUndecodable(e)) throw e
            log.debug("Photo can't be decoded: {}", e.toString())
            null
        } ?: return null
        // scale in stored orientation, then turn upright
        val stored = if (source.swapped) ImageSize(displaySize.height, displaySize.width) else displaySize
        val display = orient(downscale(decoded, stored.width, stored.height), source.orientation)
        val thumb = thumbSize?.let { downscale(display, it.width, it.height) }
        return display to thumb
    }

    private fun <T> withReader(original: StoredPhoto, block: (ImageReader) -> T): T? =
        original.inputStream().use { input ->
            MemoryCacheImageInputStream(input).use { iis ->
                val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return null
                try {
                    reader.setInput(iis, true, true)
                    block(reader)
                } finally {
                    reader.dispose()
                }
            }
        }

    /** ImageIO reports bad or unsupported pixel data in many ways; I/O trouble with the store is not one of them. */
    private fun isUndecodable(e: Exception) =
        e is IIOException || e is MalformedImageException || (e is RuntimeException && e !is CancellationException)

    /** RGB image of [w] x [h] (alpha flattened onto white), halving with bilinear filtering before the last step. */
    private fun downscale(src: BufferedImage, w: Int, h: Int): BufferedImage {
        var current = src
        while (current.width / 2 >= w && current.height / 2 >= h) current = redraw(current, current.width / 2, current.height / 2)
        return if (current === src || current.width != w || current.height != h) redraw(current, w, h) else current
    }

    private fun redraw(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            if (src.colorModel.hasAlpha()) {
                g.color = Color.WHITE
                g.fillRect(0, 0, w, h)
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** Applies EXIF [orientation] (2..8 flip and/or rotate the stored pixels into place). */
    private fun orient(src: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return src
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        // AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12
        val t = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
            else -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
        }
        val swap = orientation >= 5
        val out = BufferedImage(if (swap) src.height else src.width, if (swap) src.width else src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.drawImage(src, t, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** Encodes [img] as a metadata-free JPEG and stores it under [key]. */
    private suspend fun store(img: BufferedImage, key: String) {
        val tmp = withContext(Dispatchers.IO) {
            Files.createTempFile(storage.scratchDir, "variant-", ".part").also { writeJpeg(img, it) }
        }
        try {
            storage.save(key, tmp)
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(tmp) }
        }
    }

    private fun writeJpeg(img: BufferedImage, to: Path) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = (writer.defaultWriteParam as JPEGImageWriteParam).apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = QUALITY
                optimizeHuffmanTables = true
            }
            FileImageOutputStream(to.toFile()).use { out ->
                writer.output = out
                writer.write(null, IIOImage(img, null, null), param)
            }
        } finally {
            writer.dispose()
        }
    }
}
