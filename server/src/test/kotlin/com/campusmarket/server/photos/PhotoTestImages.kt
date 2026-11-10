package com.campusmarket.server.photos

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Builders for real (decodable) test images and for hand-crafted headers. */
internal object PhotoTestImages {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    fun image(w: Int, h: Int): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
        val g = createGraphics()
        g.color = Color(30, 120, 200)
        g.fillRect(0, 0, w, h)
        g.color = Color(240, 180, 20)
        g.fillRect(0, 0, w / 2, h / 3)
        g.dispose()
    }

    fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image(w, h), "png", it) }.toByteArray()

    fun jpeg(w: Int, h: Int, progressive: Boolean = false): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam
            if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
            writer.write(null, IIOImage(image(w, h), null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** EXIF APP1 (big-endian) with an orientation tag followed by a recognisable "private" payload. */
    fun exifSegment(orientation: Int, secret: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("Exif".toByteArray()); body.write(0); body.write(0)
        body.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8))
        body.write(byteArrayOf(0, 1)) // one entry
        body.write(byteArrayOf(0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0))
        body.write(byteArrayOf(0, 0, 0, 0)) // no next IFD
        body.write(secret.toByteArray())
        return segment(0xE1, body.toByteArray())
    }

    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len ushr 8).toByte(), len.toByte()) + payload
    }

    /** Inserts [segments] right after SOI and appends [trailer] after EOI. */
    fun withMetadata(jpeg: ByteArray, segments: List<ByteArray>, trailer: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        segments.forEach { out.write(it) }
        out.write(jpeg, 2, jpeg.size - 2)
        out.write(trailer)
        return out.toByteArray()
    }

    /** A lossless WebP header (VP8L) declaring [w] x [h]. Not decodable, but has valid magic and size fields. */
    fun webpLossless(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        val chunk = byteArrayOf(0x2F, bits.toByte(), (bits ushr 8).toByte(), (bits ushr 16).toByte(), (bits ushr 24).toByte()) + ByteArray(15)
        return riff("VP8L", chunk)
    }

    /** An extended WebP header (VP8X) declaring a [w] x [h] canvas. */
    fun webpExtended(w: Int, h: Int): ByteArray {
        val cw = w - 1
        val ch = h - 1
        val chunk = byteArrayOf(0, 0, 0, 0, cw.toByte(), (cw ushr 8).toByte(), (cw ushr 16).toByte(), ch.toByte(), (ch ushr 8).toByte(), (ch ushr 16).toByte())
        return riff("VP8X", chunk)
    }

    /** A lossy WebP header (VP8) with a key frame of [w] x [h]. */
    fun webpLossy(w: Int, h: Int): ByteArray {
        val chunk = byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A, w.toByte(), (w ushr 8).toByte(), h.toByte(), (h ushr 8).toByte()) + ByteArray(10)
        return riff("VP8 ", chunk)
    }

    private fun riff(fourCC: String, chunk: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(le32(4 + 8 + chunk.size))
        out.write("WEBP".toByteArray())
        out.write(fourCC.toByteArray())
        out.write(le32(chunk.size))
        out.write(chunk)
        return out.toByteArray()
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
