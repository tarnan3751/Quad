package com.campusmarket.server.seed

import com.campusmarket.server.TestEnv
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeedTest {
    @Test
    fun seedIsIdempotentAndConsistent() {
        TestEnv.db
        TestEnv.reset()
        val first = Seed.run(TestEnv.config, TestEnv.db, bcryptCost = 4)
        val second = Seed.run(TestEnv.config, TestEnv.db, bcryptCost = 4)
        assertEquals(first, second)

        assertEquals(SeedData.users.size, first.users)
        assertEquals(SeedData.listings.size, first.listings)
        assertTrue(first.listings >= 40)
        assertEquals(SeedData.listings.sumOf { it.photos }, first.photos)
        assertEquals(SeedData.conversations.size, first.conversations)
        assertEquals(SeedData.conversations.sumOf { it.lines.size }, first.messages)
        assertEquals(SeedData.listings.count { it.buyer != null }, first.orders)
        assertEquals(SeedData.ratings.sumOf { if (it.sellerStars != null) 2 else 1 }, first.ratings)
        assertEquals(SeedData.reports.size, first.reports)

        // photo files exist for every row
        val keys = TestEnv.db.txBlocking { c -> c.query("SELECT storage_key FROM photos") { it.getString(1) } }
        assertTrue(keys.all { File(TestEnv.config.uploadsDir, it).length() > 1000 })

        TestEnv.db.txBlocking { c ->
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
            val hash = c.queryOne("SELECT password_hash FROM users WHERE email = 'maya@state.edu'") { it.getString(1) }!!
            assertTrue(at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(SeedData.PASSWORD.toCharArray(), hash).verified)
            assertEquals(3, c.queryOne("SELECT count(*) FROM reports WHERE status = 'OPEN'") { it.getInt(1) })
        }
    }

    @Test
    fun resetRebuildsAndDevModeIsEnforced() {
        TestEnv.db
        TestEnv.reset()
        Seed.run(TestEnv.config, TestEnv.db, bcryptCost = 4)
        TestEnv.db.txBlocking { c -> c.createStatement().use { it.execute("DELETE FROM favorites") } }
        val s = Seed.run(TestEnv.config, TestEnv.db, reset = true, bcryptCost = 4)
        assertEquals(SeedData.favorites.size, s.favorites)
        assertFailsWith<IllegalArgumentException> { Seed.run(TestEnv.config.copy(devMode = false), TestEnv.db, bcryptCost = 4) }
    }
}
