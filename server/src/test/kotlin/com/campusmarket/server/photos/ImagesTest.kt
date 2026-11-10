package com.campusmarket.server.photos

import com.campusmarket.server.photos.PhotoTestImages.indexOf
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImagesTest {
    private val dir: Path = Files.createTempDirectory("cm-images-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun sanitize(bytes: ByteArray): Pair<JpegSanitizer.Result, ByteArray> {
        val src = Files.write(dir.resolve("in-${System.nanoTime()}.jpg"), bytes)
        val dst = dir.resolve("out-${System.nanoTime()}.jpg")
        val result = JpegSanitizer.sanitize(src, dst)
        return result to Files.readAllBytes(dst)
    }

    private fun pixels(bytes: ByteArray): IntArray {
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    }

    @Test
    fun sniffsOnlyAcceptedFormats() {
        assertEquals(ImageFormat.JPEG, ImageFormat.sniff(PhotoTestImages.jpeg(2, 2)))
        assertEquals(ImageFormat.PNG, ImageFormat.sniff(PhotoTestImages.png(2, 2)))
        assertEquals(ImageFormat.WEBP, ImageFormat.sniff(PhotoTestImages.webpLossy(2, 2)))
        assertNull(ImageFormat.sniff("GIF89a......".toByteArray()))
        assertNull(ImageFormat.sniff("RIFF....WAVE".toByteArray())) // RIFF but not WebP
        assertNull(ImageFormat.sniff(ByteArray(0)))
    }

    @Test
    fun readsWebpDimensionsFromAllContainerKinds() {
        fun size(b: ByteArray) = ImageHeaders.webpSize(b, b.size)
        assertEquals(ImageSize(640, 480), size(PhotoTestImages.webpLossy(640, 480)))
        assertEquals(ImageSize(16383, 1), size(PhotoTestImages.webpLossless(16383, 1)))
        assertEquals(ImageSize(5000, 7000), size(PhotoTestImages.webpExtended(5000, 7000)))
        val png = PhotoTestImages.png(123, 45)
        assertEquals(ImageSize(123, 45), ImageHeaders.pngSize(png, png.size))
    }

    @Test
    fun baselineJpegKeepsPixelsAndDropsMetadata() {
        val original = PhotoTestImages.jpeg(33, 17)
        val dirty = PhotoTestImages.withMetadata(
            original,
            listOf(PhotoTestImages.exifSegment(1, "SECRET"), PhotoTestImages.segment(0xED, "Photoshop 3.0 SECRET".toByteArray())),
            trailer = "SECRET".toByteArray(),
        )
        val (result, clean) = sanitize(dirty)
        assertEquals(ImageSize(33, 17), result.size)
        assertEquals(1, result.orientation)
        assertEquals(-1, clean.indexOf("SECRET".toByteArray()))
        assertEquals(-1, clean.indexOf("Exif".toByteArray()), "orientation 1 needs no EXIF at all")
        assertContentEquals(original, clean, "ImageIO output has no metadata to strip, so the result is byte-identical")
    }

    @Test
    fun progressiveJpegSurvivesMultiScanRewrite() {
        val original = PhotoTestImages.jpeg(64, 48, progressive = true)
        val dirty = PhotoTestImages.withMetadata(original, listOf(PhotoTestImages.exifSegment(8, "SECRET")), trailer = ByteArray(1000) { 0xFF.toByte() })
        val (result, clean) = sanitize(dirty)
        assertEquals(8, result.orientation)
        assertEquals(ImageSize(48, 64), result.size) // 90° orientation swaps the displayed size
        assertEquals(-1, clean.indexOf("SECRET".toByteArray()))
        assertContentEquals(pixels(original), pixels(clean))
    }

    @Test
    fun littleEndianExifOrientationIsRead() {
        val original = PhotoTestImages.jpeg(10, 20)
        val exif = PhotoTestImages.segment(
            0xE1,
            "Exif\u0000\u0000".toByteArray() +
                byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0),
        )
        val (result, clean) = sanitize(PhotoTestImages.withMetadata(original, listOf(exif)))
        assertEquals(5, result.orientation)
        assertEquals(ImageSize(20, 10), result.size)
        assertTrue(clean.indexOf("Exif".toByteArray()) > 0)
        assertContentEquals(pixels(original), pixels(clean))
    }

    @Test
    fun structurallyBrokenJpegsAreRejected() {
        val ok = PhotoTestImages.jpeg(8, 8)
        assertFailsWith<MalformedImageException> { sanitize(ok.copyOf(20)) } // cut inside the headers
        assertFailsWith<MalformedImageException> { sanitize(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())) }
        assertFailsWith<MalformedImageException> { sanitize("not a jpeg".toByteArray()) }
    }
}
