package com.campusmarket.server.seed

import com.campusmarket.core.Limits
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OrderStatus
import com.campusmarket.server.TestEnv
import com.campusmarket.server.photos.PhotoKeys
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class SeedTest {
    @Test
    fun seedIsIdempotentAndConsistent() {
        TestEnv.db
        TestEnv.reset()
        val first = Seed.run(TestEnv.config, TestEnv.db, bcryptCost = 4)
        val second = Seed.run(TestEnv.config, TestEnv.db, bcryptCost = 4)
        assertEquals(first, second)

        assertEquals(emptyList(), first.placeholders, "every listing should use its bundled photos")
        assertEquals(SeedData.users.size, first.users)
        assertEquals(SeedData.listings.size, first.listings)
        assertTrue(first.listings >= 40)
        assertEquals(SeedData.listings.sumOf { it.photos } + SeedData.users.count { it.avatar != null }, first.photos)
        assertEquals(SeedData.favorites.size, first.favorites)
        assertEquals(SeedData.conversations.size, first.conversations)
        assertEquals(SeedData.conversations.sumOf { it.messages.size }, first.messages)
        assertEquals(SeedData.orders.size, first.orders)
        assertEquals(SeedData.ratings.sumOf { if (it.sellerStars != null) 2 else 1 }, first.ratings)
        assertEquals(SeedData.reports.size, first.reports)

        TestEnv.db.txBlocking { c ->
            // every photo row points at a stored file whose size and dimensions it records
            val rows = c.query("SELECT storage_key, size_bytes, width, height, content_type FROM photos") {
                listOf(it.getString(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getString(5))
            }
            for ((key, size, width, height, type) in rows) {
                key as String
                assertNotNull(PhotoKeys.formatOf(key), "not a servable photo key: $key")
                val file = File(TestEnv.config.uploadsDir, key)
                assertEquals(size, file.length(), key)
                val img = SeedImage.fromJpeg(file.readBytes())
                assertEquals(width, img.width, key)
                assertEquals(height, img.height, key)
                assertEquals("image/jpeg", type)
            }

            // every photo has its size variants on disk (a display-sized seed photo is its own display variant)
            val variants = c.query("SELECT storage_key, thumb_key, display_key FROM photos WHERE variants_done") {
                Triple(it.getString(1), it.getString(2), it.getString(3))
            }
            assertEquals(rows.size, variants.size)
            for ((key, thumb, display) in variants) {
                assertNotNull(thumb, key)
                assertNotNull(display, key)
                for (v in listOf(thumb, display)) {
                    val img = SeedImage.fromJpeg(File(TestEnv.config.uploadsDir, v).readBytes())
                    assertTrue(minOf(img.width, img.height) <= 480 || v == display, v)
                    assertTrue(maxOf(img.width, img.height) <= 1280, v)
                }
            }

            // listing photos are the bundled files, in order, cover first
            val ti84 = c.query(
                """SELECT p.storage_key FROM listing_photos lp JOIN photos p ON p.id = lp.photo_id
                   JOIN listings l ON l.id = lp.listing_id WHERE l.title = ? ORDER BY lp.position""",
                SeedData.listing("ti84").title,
            ) { it.getString(1) }
            assertEquals(2, ti84.size)
            ti84.forEachIndexed { i, key ->
                assertContentEquals(SeedPhotos.load("ti84", i + 1)!!.bytes, File(TestEnv.config.uploadsDir, key).readBytes())
            }
            assertEquals(
                SeedData.users.count { it.avatar != null },
                c.queryOne("SELECT count(*) FROM users u JOIN photos p ON p.id = u.avatar_photo_id") { it.getInt(1) },
            )

            // rating aggregates match the ratings table
            val bad = c.queryOne(
                """SELECT count(*) FROM users u WHERE rating_count <> (SELECT count(*) FROM ratings WHERE ratee_id = u.id)
                   OR rating_sum <> COALESCE((SELECT sum(stars) FROM ratings WHERE ratee_id = u.id), 0)""",
            ) { it.getInt(1) }
            assertEquals(0, bad)
            assertEquals(1, c.queryOne("SELECT count(*) FROM users WHERE role = 'ADMIN' AND email = 'admin@state.edu'") { it.getInt(1) })
            assertEquals(1, c.queryOne("SELECT count(*) FROM users WHERE role = 'MODERATOR'") { it.getInt(1) })
            assertEquals(2, c.queryOne("SELECT count(DISTINCT campus_domain) FROM users") { it.getInt(1) })
            val cats = c.queryOne("SELECT count(DISTINCT category) FROM listings") { it.getInt(1) }
            assertEquals(com.campusmarket.core.ListingCategory.entries.size, cats)
            for (email in listOf("liam@state.edu", "maya@state.edu", "moderator@state.edu", "admin@state.edu")) {
                val hash = c.queryOne("SELECT password_hash FROM users WHERE email = ?", email) { it.getString(1) }!!
                assertTrue(at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(SeedData.PASSWORD.toCharArray(), hash).verified, email)
            }
            assertEquals(3, c.queryOne("SELECT count(*) FROM reports WHERE status = 'OPEN'") { it.getInt(1) })

            // orders in every state the app shows, one of them under an open dispute
            val statuses = c.query("SELECT DISTINCT status FROM orders") { it.getString(1) }.toSet()
            assertEquals(OrderStatus.entries.map { it.name }.toSet() - "PENDING_PAYMENT", statuses)
            assertEquals(1, c.queryOne("SELECT count(*) FROM order_disputes WHERE status = 'OPEN'") { it.getInt(1) })
            assertEquals(
                c.queryOne("SELECT count(*) FROM orders WHERE status = 'COMPLETED'") { it.getInt(1) },
                c.queryOne("SELECT count(*) FROM listings WHERE status = 'SOLD'") { it.getInt(1) },
            )

            // listings were posted over weeks, not all at once
            assertEquals(SeedData.listings.size, c.queryOne("SELECT count(DISTINCT created_at) FROM listings") { it.getInt(1) })
            val spanDays = c.queryOne("SELECT extract(epoch FROM max(created_at) - min(created_at)) / 86400 FROM listings") { it.getDouble(1) }!!
            assertTrue(spanDays > 14)

            // a few conversations have unread messages (same rule as ChatService)
            val unread = c.queryOne(
                """SELECT count(*) FROM conversations c WHERE EXISTS (
                     SELECT 1 FROM messages m WHERE m.conversation_id = c.id AND (
                       (m.sender_id = c.seller_id AND m.created_at > COALESCE(c.buyer_last_read_at, '-infinity'::timestamptz)) OR
                       (m.sender_id = c.buyer_id AND m.created_at > COALESCE(c.seller_last_read_at, '-infinity'::timestamptz))))""",
            ) { it.getInt(1) }
            assertEquals(SeedData.conversations.count { it.unread }, unread)
        }
    }

    @Test
    fun bundledPhotosAreCompleteCreditedAndSmall() {
        val credits = assertNotNull(javaClass.classLoader.getResource("seed/photos/CREDITS.md")).readText()
        val dir = File(javaClass.classLoader.getResource("seed/photos/CREDITS.md")!!.toURI()).parentFile
        val expected = SeedData.listings.flatMap { l -> (1..l.photos).map { "${l.key}-$it.jpg" } }.toSet()
        assertEquals(expected, dir.list { _, name -> name.endsWith(".jpg") }!!.toSet(), "photo files vs SeedData")

        var total = 0L
        for (l in SeedData.listings) for (n in 1..l.photos) {
            val name = "${l.key}-$n.jpg"
            val img = assertNotNull(SeedPhotos.load(l.key, n), name)
            assertTrue(maxOf(img.width, img.height) <= 1200, name)
            assertTrue(img.bytes.size in 15_000..160_000, "$name is ${img.bytes.size} bytes")
            assertFalse(hasMetadataSegment(img.bytes), "$name still carries EXIF/XMP/comment metadata")
            assertTrue("| `$name` |" in credits, "$name is not credited")
            total += img.bytes.size
        }
        assertTrue(total < 7_000_000, "photos add up to $total bytes")
    }

    @Test
    fun demoDataTellsAConsistentStory() {
        val users = SeedData.users.associateBy { it.key }
        val listings = SeedData.listings.associateBy { it.key }
        assertEquals(SeedData.users.size, users.size)
        assertEquals(SeedData.listings.size, listings.size)
        for (u in SeedData.users) {
            assertTrue(u.name.length in Limits.DISPLAY_NAME_MIN..Limits.DISPLAY_NAME_MAX && u.bio.length <= Limits.BIO_MAX, u.key)
        }
        for (l in SeedData.listings) {
            val seller = users.getValue(l.seller)
            assertTrue(l.title.length in Limits.TITLE_MIN..Limits.TITLE_MAX, l.key)
            assertTrue(l.desc.length <= Limits.DESCRIPTION_MAX && l.pickup.length <= Limits.PICKUP_MAX, l.key)
            assertTrue(l.cents in Limits.PRICE_MIN_CENTS..Limits.PRICE_MAX_CENTS, l.key)
            assertTrue(l.photos in 1..Limits.PHOTOS_MAX, l.key)
            assertTrue(l.posted < seller.joined, "${l.key} posted before ${l.seller} joined")
        }
        fun sameCampus(user: String, listing: String) = users.getValue(user).domain == users.getValue(listings.getValue(listing).seller).domain

        for (o in SeedData.orders) {
            val l = listings.getValue(o.listing)
            assertTrue(o.buyer != l.seller && sameCampus(o.buyer, o.listing), "${o.listing}: buyer")
            assertTrue(o.placed < l.posted, "${o.listing}: ordered before it was posted")
            when (o.status) {
                // the payments sweep would act on these otherwise
                OrderStatus.PAID, OrderStatus.DISPUTED -> assertTrue(o.placed < 60.hours, "${o.listing}: auto-release is due")
                OrderStatus.COMPLETED -> assertTrue(o.placed > o.handoff + 6.hours, "${o.listing}: completed/rated in the future")
                OrderStatus.REFUNDED -> assertTrue(o.placed > 1.days)
                OrderStatus.PENDING_PAYMENT -> error("${o.listing}: unpaid orders get expired by the payments sweep")
                else -> Unit
            }
            assertEquals(o.status == OrderStatus.DISPUTED, o.dispute != null, o.listing)
            o.dispute?.let { assertTrue(it.afterPayment < o.placed && it.details.length <= Limits.DISPUTE_DETAILS_MAX) }
        }
        for (l in SeedData.listings) {
            val live = SeedData.orders.count { it.listing == l.key && it.status in setOf(OrderStatus.PAID, OrderStatus.DISPUTED, OrderStatus.COMPLETED) }
            assertTrue(live <= 1, "${l.key} has $live live orders")
        }
        for (cv in SeedData.conversations) {
            val l = listings.getValue(cv.listing)
            assertTrue(cv.buyer != l.seller && sameCampus(cv.buyer, cv.listing), cv.listing)
            assertTrue(cv.started < l.posted, "${cv.listing}: chat before the listing existed")
            assertEquals(Duration.ZERO, cv.messages.first().at)
            assertTrue(cv.messages.zipWithNext().all { (a, b) -> a.at < b.at }, "${cv.listing}: messages out of order")
            assertTrue(cv.messages.last().at < cv.started, "${cv.listing}: message in the future")
            assertTrue(cv.messages.all { it.body.length <= Limits.MESSAGE_MAX })
            assertTrue(cv.messages.first().fromBuyer)
        }
        for (r in SeedData.ratings) {
            assertTrue(SeedData.orders.any { it.listing == r.listing && it.status == OrderStatus.COMPLETED }, r.listing)
            assertTrue(r.buyerStars in 1..5 && (r.sellerStars ?: 1) in 1..5)
            assertTrue(r.buyerComment.length <= Limits.RATING_COMMENT_MAX && r.sellerComment.length <= Limits.RATING_COMMENT_MAX)
        }
        assertTrue(SeedData.ratings.map { it.buyerStars }.toSet().size > 1, "ratings should not all be the same")
        for (f in SeedData.favorites) {
            val l = listings.getValue(f.listing)
            assertTrue(f.user != l.seller && sameCampus(f.user, f.listing) && f.ago < l.posted, "${f.user} -> ${f.listing}")
        }
        for (r in SeedData.reports) {
            assertTrue(r.details.length <= Limits.REPORT_DETAILS_MAX)
            if (r.type == "MESSAGE") assertTrue(SeedData.conversations.any { it.listing == r.target && it.messages.any { m -> !m.fromBuyer } })
        }

        // what the client's live screenshot tests look for
        val stateActive = SeedData.listings.filter { users.getValue(it.seller).domain == "state.edu" && SeedData.statusOf(it) == ListingStatus.ACTIVE }
        assertEquals("campbell-bio", stateActive.minBy { it.posted }.key)
        assertTrue(SeedData.listings.any { it.seller == "maya" && "Mini fridge" in it.title && SeedData.statusOf(it) == ListingStatus.SOLD })
        assertTrue(SeedData.conversations.any { it.buyer == "liam" })
        assertTrue(SeedData.orders.any { it.buyer == "sofia" && it.status == OrderStatus.PAID })
    }

    @Test
    fun missingPhotoFallsBackToAPlaceholder() {
        TestEnv.db
        TestEnv.reset()
        val s = Seed.run(TestEnv.config, TestEnv.db, bcryptCost = 4, photos = { key, n -> if (key == "ti84") null else SeedPhotos.load(key, n) })
        assertEquals(listOf("ti84"), s.placeholders)
        TestEnv.db.txBlocking { c ->
            val keys = c.query(
                """SELECT p.storage_key FROM listing_photos lp JOIN photos p ON p.id = lp.photo_id
                   JOIN listings l ON l.id = lp.listing_id WHERE l.title = ?""",
                SeedData.listing("ti84").title,
            ) { it.getString(1) }
            assertEquals(2, keys.size)
            assertTrue(keys.all { File(TestEnv.config.uploadsDir, it).length() > 1000 })
        }
        // once the file is back, a plain re-run swaps the placeholder for it
        assertEquals(emptyList(), Seed.run(TestEnv.config, TestEnv.db, bcryptCost = 4).placeholders)
        val cover = TestEnv.db.txBlocking { c ->
            c.queryOne(
                """SELECT p.storage_key FROM listing_photos lp JOIN photos p ON p.id = lp.photo_id
                   JOIN listings l ON l.id = lp.listing_id WHERE l.title = ? AND lp.position = 0""",
                SeedData.listing("ti84").title,
            ) { it.getString(1) }!!
        }
        assertContentEquals(SeedPhotos.load("ti84", 1)!!.bytes, File(TestEnv.config.uploadsDir, cover).readBytes())
    }

    @Test
    fun resetRebuildsPrunesAndDevModeIsEnforced() {
        TestEnv.db
        TestEnv.reset()
        Seed.run(TestEnv.config, TestEnv.db, bcryptCost = 4)
        TestEnv.db.txBlocking { c -> c.createStatement().use { it.execute("DELETE FROM favorites") } }
        val stale = File(TestEnv.config.uploadsDir, "seed-00000000-0000-0000-0000-000000000000-000000000000.jpg").apply { writeBytes(ByteArray(10)) }
        val upload = File(TestEnv.config.uploadsDir, "0123456789abcdef0123456789abcdef.jpg").apply { writeBytes(ByteArray(10)) }
        val s = Seed.run(TestEnv.config, TestEnv.db, reset = true, bcryptCost = 4)
        assertEquals(SeedData.favorites.size, s.favorites)
        assertFalse(stale.exists(), "unreferenced seed files are removed")
        assertTrue(upload.exists(), "files that aren't the seed's are left alone")
        upload.delete()
        assertFailsWith<IllegalArgumentException> { Seed.run(TestEnv.config.copy(devMode = false), TestEnv.db, bcryptCost = 4) }
    }

    /** True if the JPEG has an APP1 (EXIF/XMP), APP13 (IPTC) or comment segment before the image data. */
    private fun hasMetadataSegment(jpeg: ByteArray): Boolean {
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA) return false
            if (marker == 0xE1 || marker == 0xED || marker == 0xFE) return true
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
        }
        return false
    }
}
