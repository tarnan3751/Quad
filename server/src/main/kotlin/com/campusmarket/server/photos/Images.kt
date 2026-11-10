package com.campusmarket.server.photos

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Image formats accepted by POST /photos. Identified by magic bytes only, never by the client's headers. */
enum class ImageFormat(val contentType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    companion object {
        /** Leading bytes kept from an upload: enough to identify every format and to read PNG / WebP dimensions. */
        const val HEADER_SIZE = 32

        /** Bytes needed before [sniff] can give a definitive answer. */
        const val SNIFF_SIZE = 12

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

        /** The format whose signature starts [h] (first [len] bytes valid), or null if it isn't an accepted image. */
        fun sniff(h: ByteArray, len: Int = h.size): ImageFormat? = when {
            len >= 3 && h.u8(0) == 0xFF && h.u8(1) == 0xD8 && h.u8(2) == 0xFF -> JPEG
            len >= 8 && (0 until 8).all { h[it] == PNG_SIGNATURE[it] } -> PNG
            len >= 12 && h.ascii(0, 4) == "RIFF" && h.ascii(8, 4) == "WEBP" -> WEBP
            else -> null
        }

        fun forExtension(ext: String): ImageFormat? = entries.firstOrNull { it.extension == ext }
    }
}

data class ImageSize(val width: Int, val height: Int)

/** Dimension readers that only look at fixed header fields (no pixel decoding). */
internal object ImageHeaders {

    /** PNG: the IHDR chunk is always first; width/height are big-endian u32 at offsets 16/20. */
    fun pngSize(h: ByteArray, len: Int): ImageSize? {
        if (len < 24 || h.ascii(12, 4) != "IHDR") return null
        return sizeOrNull(h.be32(16), h.be32(20))
    }

    /** WebP: simple lossy (VP8), lossless (VP8L) and extended (VP8X) containers. */
    fun webpSize(h: ByteArray, len: Int): ImageSize? {
        if (len < 30) return null
        return when (h.ascii(12, 4)) {
            "VP8 " -> if (h.u8(23) == 0x9D && h.u8(24) == 0x01 && h.u8(25) == 0x2A) {
                sizeOrNull(h.le16(26) and 0x3FFF, h.le16(28) and 0x3FFF)
            } else null
            "VP8L" -> if (h.u8(20) == 0x2F) {
                val bits = h.le32(21)
                sizeOrNull((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
            } else null
            "VP8X" -> sizeOrNull(h.le24(24) + 1, h.le24(27) + 1)
            else -> null
        }
    }

    fun sizeOrNull(w: Int, h: Int): ImageSize? = if (w > 0 && h > 0) ImageSize(w, h) else null
}

class MalformedImageException(message: String) : Exception(message)

/**
 * Rewrites a JPEG without the metadata segments that commonly carry personal data (EXIF incl. GPS position,
 * XMP, IPTC, comments) and without anything trailing the end-of-image marker (e.g. embedded motion-photo
 * videos). Pixel data is copied byte for byte (lossless). The EXIF orientation is preserved by writing a
 * minimal EXIF segment that contains only that tag, so photos still display upright.
 *
 * Also reports the frame size from the SOF header, already swapped for 90° orientations, i.e. the size
 * the image is displayed at.
 */
internal object JpegSanitizer {

    data class Result(val size: ImageSize?, val orientation: Int)

    private const val MAX_SEGMENTS = 10_000

    fun sanitize(src: Path, dst: Path): Result {
        Files.newInputStream(src).use { raw ->
            Files.newOutputStream(dst, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                .buffered(64 * 1024).use { out ->
                return sanitize(ByteSource(raw), out)
            }
        }
    }

    private fun sanitize(src: ByteSource, out: OutputStream): Result {
        if (src.read() != 0xFF || src.read() != 0xD8) throw MalformedImageException("Not a JPEG")
        val header = ByteArrayOutputStream()
        var frame: ImageSize? = null
        var orientation = 1
        var segments = 0
        try {
            // Phase 1: header segments up to the first start-of-scan.
            while (true) {
                if (++segments > MAX_SEGMENTS) throw MalformedImageException("Too many JPEG segments")
                val marker = nextMarker(src)
                when {
                    // stray standalone markers (repeated SOI, TEM, RSTn) are meaningless before the first scan
                    marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> Unit
                    marker == 0xD9 -> throw MalformedImageException("JPEG ends before any image data")
                    else -> {
                        val payload = src.readSegmentPayload()
                        if (isStartOfFrame(marker) && frame == null && payload.size >= 5) {
                            frame = ImageHeaders.sizeOrNull(payload.be16(3), payload.be16(1))
                        }
                        if (marker == 0xE1 && orientation == 1) exifOrientation(payload)?.let { orientation = it }
                        if (keepSegment(marker)) header.writeSegment(marker, payload)
                        if (marker == 0xDA) break
                    }
                }
            }
            out.write(0xFF); out.write(0xD8)
            if (orientation != 1) out.write(orientationSegment(orientation))
            header.writeTo(out)

            // Phase 2: entropy-coded data and any further scans, until end-of-image. Trailing bytes are dropped.
            while (true) {
                if (!src.copyUntilFF(out)) return result(frame, orientation) // truncated scan: keep what we have
                var m = src.read()
                while (m == 0xFF) m = src.read() // fill bytes
                when {
                    m == -1 -> { out.write(0xFF); return result(frame, orientation) }
                    m == 0x00 || m in 0xD0..0xD7 || m == 0x01 || m == 0xD8 -> { out.write(0xFF); out.write(m) }
                    m == 0xD9 -> { out.write(0xFF); out.write(0xD9); return result(frame, orientation) }
                    else -> {
                        if (++segments > MAX_SEGMENTS) throw MalformedImageException("Too many JPEG segments")
                        val payload = src.readSegmentPayload()
                        if (keepSegment(m)) writeSegment(out, m, payload)
                    }
                }
            }
        } catch (e: EOFException) {
            throw MalformedImageException("Truncated JPEG header")
        }
    }

    private fun result(frame: ImageSize?, orientation: Int): Result {
        val display = if (frame != null && orientation in 5..8) ImageSize(frame.height, frame.width) else frame
        return Result(display, orientation)
    }

    /** Reads up to and including the next marker byte, tolerating junk and fill bytes the way libjpeg does. */
    private fun nextMarker(src: ByteSource): Int {
        while (true) {
            var b = src.readOrThrow()
            while (b != 0xFF) b = src.readOrThrow()
            var m = src.readOrThrow()
            while (m == 0xFF) m = src.readOrThrow()
            if (m != 0x00) return m
        }
    }

    private fun isStartOfFrame(m: Int) = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC

    /** APP1 (EXIF / XMP), APP13 (IPTC / Photoshop) and COM segments are dropped; everything else is kept. */
    private fun keepSegment(m: Int) = m != 0xE1 && m != 0xED && m != 0xFE

    private fun exifOrientation(p: ByteArray): Int? {
        if (p.size < 14 || p.ascii(0, 6) != "Exif\u0000\u0000") return null
        val t = 6 // TIFF header start
        val le = when (p.ascii(t, 2)) { "II" -> true; "MM" -> false; else -> return null }
        fun u16(o: Int) = if (le) p.le16(o) else p.be16(o)
        fun u32(o: Int) = if (le) p.le32(o) else p.be32(o)
        if (u16(t + 2) != 42) return null
        val ifdOffset = u32(t + 4)
        if (ifdOffset < 8 || ifdOffset > p.size - t - 2) return null
        val ifd = t + ifdOffset
        val count = u16(ifd)
        for (i in 0 until count) {
            val e = ifd + 2 + i * 12
            if (e + 12 > p.size) return null
            if (u16(e) == 0x0112) {
                if (u16(e + 2) != 3) return null // SHORT
                return u16(e + 8).takeIf { it in 1..8 }
            }
        }
        return null
    }

    /** A minimal big-endian EXIF APP1 segment holding only the orientation tag. */
    private fun orientationSegment(o: Int): ByteArray = intArrayOf(
        0xFF, 0xE1, 0x00, 0x22,
        'E'.code, 'x'.code, 'i'.code, 'f'.code, 0, 0,
        'M'.code, 'M'.code, 0x00, 0x2A, 0, 0, 0, 8,
        0, 1,
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o, 0, 0,
        0, 0, 0, 0,
    ).let { ints -> ByteArray(ints.size) { ints[it].toByte() } }

    private fun ByteArrayOutputStream.writeSegment(m: Int, payload: ByteArray) = writeSegment(this, m, payload)

    private fun writeSegment(out: OutputStream, m: Int, payload: ByteArray) {
        val len = payload.size + 2
        out.write(0xFF); out.write(m); out.write(len ushr 8); out.write(len and 0xFF)
        out.write(payload)
    }

    private fun ByteSource.readSegmentPayload(): ByteArray {
        val len = (readOrThrow() shl 8) or readOrThrow()
        if (len < 2) throw MalformedImageException("Bad JPEG segment length")
        return readFully(len - 2)
    }
}

/** Minimal unsynchronised buffered reader used by [JpegSanitizer]. */
private class ByteSource(private val input: InputStream) {
    private val buf = ByteArray(64 * 1024)
    private var pos = 0
    private var limit = 0

    private fun fill(): Boolean {
        if (pos < limit) return true
        limit = input.read(buf)
        pos = 0
        if (limit <= 0) { limit = 0; return false }
        return true
    }

    fun read(): Int = if (fill()) buf[pos++].toInt() and 0xFF else -1

    fun readOrThrow(): Int = read().also { if (it == -1) throw EOFException() }

    fun readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            if (!fill()) throw EOFException()
            val k = minOf(n - off, limit - pos)
            System.arraycopy(buf, pos, out, off, k)
            pos += k; off += k
        }
        return out
    }

    /** Copies bytes to [out] up to (not including) the next 0xFF, which is consumed. False at end of input. */
    fun copyUntilFF(out: OutputStream): Boolean {
        while (fill()) {
            var i = pos
            while (i < limit && buf[i] != 0xFF.toByte()) i++
            out.write(buf, pos, i - pos)
            if (i < limit) { pos = i + 1; return true }
            pos = limit
        }
        return false
    }
}

// ---------- byte helpers ----------

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.ascii(from: Int, len: Int): String =
    if (from + len > size) "" else String(this, from, len, Charsets.ISO_8859_1)
internal fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.le16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.le24(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
internal fun ByteArray.be32(i: Int): Int = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
internal fun ByteArray.le32(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
