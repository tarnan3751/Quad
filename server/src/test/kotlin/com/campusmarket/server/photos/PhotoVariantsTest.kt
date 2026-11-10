package com.campusmarket.server.photos

import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoVariantsTest {
    private val dir: Path = Files.createTempDirectory("cm-variants-test")
    private val storage = LocalPhotoStorage(dir)
    private val variants = PhotoVariants(storage)

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun stored(key: String, bytes: ByteArray): String = key.also { Files.write(dir.resolve(it), bytes) }

    private fun make(key: String) = runBlocking { variants.make(key) }

    private fun read(key: String): BufferedImage = ImageIO.read(dir.resolve(key).toFile())

    private fun assertSize(w: Int, h: Int, key: String) {
        val img = read(key)
        assertEquals(w to h, img.width to img.height, key)
    }

    private fun assertColor(expected: Color, img: BufferedImage, x: Int, y: Int) {
        val c = Color(img.getRGB(x, y))
        val close = abs(c.red - expected.red) < 40 && abs(c.green - expected.green) < 40 && abs(c.blue - expected.blue) < 40
        assertTrue(close, "pixel ($x, $y) is $c, expected about $expected")
    }

    /** Variants are plain JFIF: no APP1 (EXIF / XMP), APP13 or comment segment before the image data. */
    private fun assertNoMetadata(key: String) {
        val jpeg = Files.readAllBytes(dir.resolve(key))
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF)
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA) return
            assertFalse(marker == 0xE1 || marker == 0xED || marker == 0xFE, "metadata segment ${marker.toString(16)} in $key")
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
        }
    }

    @Test
    fun landscapeJpegGetsBothSizes() {
        val key = stored("0123456789abcdef0123456789abcdef.jpg", PhotoTestImages.jpeg(3000, 2000))
        val v = make(key)
        assertEquals(VariantKeys(PhotoVariants.thumbKey(key), PhotoVariants.displayKey(key)), v)
        assertSize(1280, 853, v.display!!)
        assertSize(720, 480, v.thumb!!)
        assertNoMetadata(v.display!!)
        assertNoMetadata(v.thumb!!)
        assertTrue(Files.size(dir.resolve(v.thumb!!)) < Files.size(dir.resolve(v.display!!)))
    }

    @Test
    fun hugeOriginalIsSubsampledWhileDecoding() {
        val key = stored("huge.jpg", PhotoTestImages.jpeg(6000, 4000))
        val v = make(key)
        assertSize(1280, 853, v.display!!)
        assertSize(720, 480, v.thumb!!)
        assertColor(Color(240, 180, 20), read(v.thumb!!), 100, 50) // the yellow block is still top-left
    }

    @Test
    fun exifOrientationIsAppliedAndDropped() {
        // stored 2000x1000 with orientation 6 (rotate 90° clockwise): displayed 1000x2000
        val jpeg = PhotoTestImages.withMetadata(PhotoTestImages.jpeg(2000, 1000), listOf(PhotoTestImages.exifSegment(6, "SECRET-GPS")))
        val key = stored("rotated.jpg", jpeg)
        val v = make(key)
        assertSize(640, 1280, v.display!!)
        assertSize(480, 960, v.thumb!!)
        assertNoMetadata(v.display!!)
        assertNoMetadata(v.thumb!!)
        // the stored top-left yellow block ends up top-right once upright
        val display = read(v.display!!)
        assertColor(Color(240, 180, 20), display, 600, 100)
        assertColor(Color(30, 120, 200), display, 100, 100)
        assertColor(Color(30, 120, 200), display, 600, 1000)
        val thumb = read(v.thumb!!)
        assertColor(Color(240, 180, 20), thumb, 450, 75)
        assertColor(Color(30, 120, 200), thumb, 50, 75)
    }

    @Test
    fun transparentPngIsFlattenedOntoWhite() {
        val img = BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color.RED
            g.fillRect(0, 0, 100, 100)
            g.dispose()
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val key = stored("alpha.png", png)
        val v = make(key)
        // already small: one JPEG variant serves as both sizes
        assertEquals(VariantKeys(PhotoVariants.displayKey(key), PhotoVariants.displayKey(key)), v)
        val out = read(v.display!!)
        assertEquals(600 to 400, out.width to out.height)
        assertColor(Color.WHITE, out, 400, 300)
        assertColor(Color.RED, out, 50, 50)
    }

    @Test
    fun smallUprightJpegsReuseTheOriginal() {
        val tiny = stored("tiny.jpg", PhotoTestImages.jpeg(400, 300))
        assertEquals(VariantKeys(tiny, tiny), make(tiny))
        // the seed's photos: display-sized already, only a thumbnail is made
        val seedLike = stored("seed-like.jpg", PhotoTestImages.jpeg(1200, 900))
        assertEquals(VariantKeys(PhotoVariants.thumbKey(seedLike), seedLike), make(seedLike))
        assertSize(640, 480, PhotoVariants.thumbKey(seedLike))
        assertEquals(setOf("tiny.jpg", "seed-like.jpg", "seed-like-t.jpg"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { !it.startsWith(".") }.toList().toSet() })
    }

    @Test
    fun undecodableOrMissingOriginalsGetNoVariants() {
        assertEquals(VariantKeys.NONE, make(stored("lossless.webp", PhotoTestImages.webpLossless(300, 200))))
        assertEquals(VariantKeys.NONE, make("missing.jpg"))
        assertEquals(VariantKeys.NONE, make(stored("junk.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 1, 2, 3))))
        assertEquals(VariantKeys.NONE, make("../escape.jpg"))
    }

    @Test
    fun makingIsIdempotent() {
        val key = stored("again.jpg", PhotoTestImages.jpeg(2000, 1500))
        val first = make(key)
        val written = Files.getLastModifiedTime(dir.resolve(first.thumb!!))
        Thread.sleep(20)
        assertEquals(first, make(key))
        assertEquals(written, Files.getLastModifiedTime(dir.resolve(first.thumb!!)), "existing variants are not rewritten")
    }

    @Test
    fun sizeRules() {
        assertEquals(ImageSize(1280, 960), PhotoVariants.displaySize(4000, 3000))
        assertEquals(ImageSize(640, 480), PhotoVariants.thumbSize(4000, 3000))
        assertEquals(ImageSize(480, 640), PhotoVariants.thumbSize(3000, 4000))
        // a panorama's thumbnail is capped on the long side
        assertEquals(ImageSize(1280, 160), PhotoVariants.thumbSize(8000, 1000))
        // never upscaled
        assertEquals(ImageSize(300, 200), PhotoVariants.thumbSize(300, 200))
        assertEquals(ImageSize(300, 200), PhotoVariants.displaySize(300, 200))
        assertEquals(ImageSize(1, 1280), PhotoVariants.displaySize(1, 20000))
    }

    @Test
    fun orientationIsReadFromStoredJpegs() {
        val plain = PhotoTestImages.jpeg(10, 10)
        assertEquals(1, JpegSanitizer.orientation(ByteArrayInputStream(plain)))
        val rotated = PhotoTestImages.withMetadata(plain, listOf(PhotoTestImages.exifSegment(8, "x")))
        assertEquals(8, JpegSanitizer.orientation(ByteArrayInputStream(rotated)))
    }
}
