package com.campusmarket.server.seed

import at.favre.lib.crypto.bcrypt.BCrypt
import com.campusmarket.core.ListingStatus
import com.campusmarket.server.AppConfig
import com.campusmarket.server.Db
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Dev seed: idempotently populates a realistic demo dataset by direct SQL.
 *
 * Run with `./gradlew :server:seed` (add `--args="--reset"` to truncate first). All ids are derived
 * deterministically from stable keys and every insert is `ON CONFLICT DO NOTHING`, so running it again is a no-op.
 */
object Seed {
    data class Summary(
        val users: Int, val listings: Int, val photos: Int, val favorites: Int,
        val conversations: Int, val messages: Int, val orders: Int, val ratings: Int, val reports: Int,
    )

    private fun id(key: String): UUID = UUID.nameUUIDFromBytes("campus-market-seed:$key".toByteArray())
    private fun userKeyToId(c: Connection, email: String): UUID =
        c.queryOne("SELECT id FROM users WHERE lower(email) = lower(?)", email) { it.getObject(1, UUID::class.java) }!!

    @JvmStatic
    fun main(args: Array<String>) {
        val config = AppConfig.fromEnv()
        if (!config.devMode) {
            System.err.println("Refusing to seed: DEV_MODE=false. The seed tool is for local development only.")
            exitProcess(1)
        }
        val reset = "--reset" in args
        val db = Db.connect(config.dbUrl, config.dbUser, config.dbPassword, poolSize = 2)
        try {
            Db.migrate(db)
            val s = run(config, db, reset)
            println()
            println("Seed complete: $s")
            println()
            println("Demo logins (password for all: ${SeedData.PASSWORD})")
            SeedData.users.forEach { u ->
                val tag = when {
                    u.role.name != "USER" -> " [${u.role}]"
                    u.seller -> " [seller, payouts enabled]"
                    else -> ""
                }
                println("  %-24s %s%s".format(u.email, u.name, tag))
            }
        } finally {
            db.close()
        }
    }

    /** Seeds [db]. Returns row counts of the seeded dataset (after the run). [bcryptCost] is lowered by tests. */
    fun run(config: AppConfig, db: Db, reset: Boolean = false, bcryptCost: Int = 12): Summary {
        require(config.devMode) { "Refusing to seed with DEV_MODE=false" }
        if (reset) reset(db)
        val uploads = File(config.uploadsDir).apply { mkdirs() }
        val hash = BCrypt.withDefaults().hashToString(bcryptCost, SeedData.PASSWORD.toCharArray())
        val now = Instant.now()

        val userIds = HashMap<String, UUID>()
        val listingIds = HashMap<String, UUID>()
        val orderIds = HashMap<String, UUID>()
        val firstSellerMessage = HashMap<String, UUID>()
        val photoRows = ArrayList<Triple<UUID, UUID, Pair<String, Int>>>() // photo id, owner, (title,variant) — rendered after commit

        db.txBlocking { c ->
            // ---- users
            for ((i, u) in SeedData.users.withIndex()) {
                val created = now.minus((60 - i).toLong(), ChronoUnit.DAYS)
                c.update(
                    """INSERT INTO users (id, email, password_hash, display_name, bio, campus_domain, role, email_verified,
                                          stripe_account_id, stripe_details_submitted, payouts_enabled, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    id("user:${u.key}"), u.email, hash, u.name, u.bio, u.domain, u.role,
                    if (u.seller) "acct_fake_${u.key}" else null, u.seller, u.seller, created,
                )
                userIds[u.key] = userKeyToId(c, u.email)
            }

            // ---- listings, photos
            for ((i, l) in SeedData.listings.withIndex()) {
                val seller = SeedData.users.first { it.key == l.seller }
                val lid = id("listing:${l.key}")
                val created = now.minus((i * 7L + 3), ChronoUnit.HOURS)
                c.update(
                    """INSERT INTO listings (id, seller_id, campus_domain, title, description, category, condition, price_cents,
                                            status, pickup_location, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    lid, userIds[l.seller]!!, seller.domain, l.title, l.desc, l.cat, l.cond, l.cents, l.status, l.pickup, created, created,
                )
                listingIds[l.key] = lid
                for (p in 0 until l.photos) {
                    val pid = id("photo:${l.key}:$p")
                    c.update(
                        """INSERT INTO photos (id, owner_id, storage_key, content_type, size_bytes, width, height, created_at)
                           VALUES (?, ?, ?, 'image/jpeg', 0, ?, ?, ?) ON CONFLICT DO NOTHING""",
                        pid, userIds[l.seller]!!, "seed-$pid.jpg", PlaceholderImages.SIZE, PlaceholderImages.SIZE, created,
                    )
                    c.update("INSERT INTO listing_photos (listing_id, photo_id, position) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", lid, pid, p)
                    photoRows += Triple(pid, userIds[l.seller]!!, l.title to (p + i))
                }
            }

            // ---- favorites
            for ((i, f) in SeedData.favorites.withIndex()) {
                c.update(
                    "INSERT INTO favorites (user_id, listing_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    userIds[f.first]!!, listingIds[f.second]!!, now.minus(i * 3L + 1, ChronoUnit.HOURS),
                )
            }

            // ---- conversations + messages
            for ((i, cv) in SeedData.conversations.withIndex()) {
                val listing = SeedData.listings.first { it.key == cv.listing }
                val seller = userIds[listing.seller]!!
                val buyer = userIds[cv.buyer]!!
                val cid = id("conv:${cv.listing}:${cv.buyer}")
                val start = now.minus(2L + i, ChronoUnit.DAYS)
                c.update(
                    "INSERT INTO conversations (id, listing_id, buyer_id, seller_id, created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    cid, listingIds[cv.listing]!!, buyer, seller, start,
                )
                var last = start
                cv.lines.forEachIndexed { n, body ->
                    val mid = id("msg:$cid:$n")
                    last = start.plus(n * 17L, ChronoUnit.MINUTES)
                    c.update(
                        "INSERT INTO messages (id, conversation_id, sender_id, body, created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        mid, cid, if (n % 2 == 0) buyer else seller, body, last,
                    )
                    if (n == 1) firstSellerMessage[cv.listing] = mid
                }
                // the most recent message is read by its recipient only for older conversations
                c.update(
                    """UPDATE conversations SET last_message_at = ?, last_message_preview = ?,
                              buyer_last_read_at = ?, seller_last_read_at = ?
                       WHERE id = ?""",
                    last, cv.lines.last().take(80), if (i % 2 == 0) last else start, last, cid,
                )
            }

            // ---- orders (SOLD -> COMPLETED; RESERVED -> PAID a few hours ago, so the 72 h auto-release is still ahead,
            //      except the last RESERVED one, which the buyer has disputed: it shows up in the moderators' queue)
            val disputedKey = SeedData.listings.lastOrNull { it.status == ListingStatus.RESERVED && it.buyer != null }?.key
            for ((i, l) in SeedData.listings.withIndex()) {
                val buyerKey = l.buyer ?: continue
                val oid = id("order:${l.key}")
                val fee = l.cents * config.platformFeeBps / 10000
                val completed = l.status == ListingStatus.SOLD
                val disputed = l.key == disputedKey
                val created = if (completed) now.minus(5L + i, ChronoUnit.DAYS) else now.minus(3L + i % 5, ChronoUnit.HOURS)
                val paid = created.plus(10, ChronoUnit.MINUTES)
                c.update(
                    """INSERT INTO orders (id, listing_id, buyer_id, seller_id, amount_cents, fee_cents, status,
                                           stripe_checkout_session_id, stripe_payment_intent_id, stripe_transfer_id,
                                           created_at, paid_at, completed_at, release_clock_from)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    oid, listingIds[l.key]!!, userIds[buyerKey]!!, userIds[l.seller]!!, l.cents, fee,
                    when {
                        completed -> "COMPLETED"
                        disputed -> "DISPUTED"
                        else -> "PAID"
                    },
                    "cs_fake_${oid.toString().take(8)}", "pi_fake_${oid.toString().take(8)}",
                    if (completed) "tr_fake_${oid.toString().take(8)}" else null,
                    created, paid, if (completed) created.plus(2, ChronoUnit.DAYS) else null, if (completed) null else paid,
                )
                if (disputed) {
                    c.update(
                        """INSERT INTO order_disputes (id, order_id, opened_by, reason, details, opened_at)
                           VALUES (?, ?, ?, 'SELLER_NO_SHOW', ?, ?) ON CONFLICT DO NOTHING""",
                        id("dispute:${l.key}"), oid, userIds[buyerKey]!!,
                        "We agreed to meet at the pickup spot at 4pm. I waited 30 minutes and the seller never came or replied.",
                        paid.plus(2, ChronoUnit.HOURS),
                    )
                }
                orderIds[l.key] = oid
            }

            // ---- ratings, then recompute users.rating_sum/count from the ratings table (keeps it consistent)
            for (r in SeedData.ratings) {
                val listing = SeedData.listings.first { it.key == r.listing }
                val oid = orderIds[r.listing]!!
                val buyer = userIds[listing.buyer!!]!!
                val seller = userIds[listing.seller]!!
                val at = now.minus(3, ChronoUnit.DAYS)
                c.update(
                    "INSERT INTO ratings (id, order_id, rater_id, ratee_id, stars, comment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    id("rating:$oid:buyer"), oid, buyer, seller, r.buyerStars, r.buyerComment, at,
                )
                if (r.sellerStars != null) c.update(
                    "INSERT INTO ratings (id, order_id, rater_id, ratee_id, stars, comment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    id("rating:$oid:seller"), oid, seller, buyer, r.sellerStars, r.sellerComment, at,
                )
            }
            c.update(
                """UPDATE users u SET rating_sum = COALESCE(r.s, 0), rating_count = COALESCE(r.n, 0)
                   FROM (SELECT u2.id, (SELECT sum(stars) FROM ratings WHERE ratee_id = u2.id) AS s,
                                      (SELECT count(*) FROM ratings WHERE ratee_id = u2.id) AS n FROM users u2) r
                   WHERE u.id = r.id AND (u.rating_sum <> COALESCE(r.s, 0) OR u.rating_count <> COALESCE(r.n, 0))""",
            )

            // ---- open reports
            for (rep in SeedData.reports) {
                val reporter = userIds[rep.reporter]!!
                val (targetId, targetUser, summary) = when (rep.type) {
                    "LISTING" -> {
                        val l = SeedData.listings.first { it.key == rep.target }
                        Triple(listingIds[rep.target]!!, userIds[l.seller]!!, l.title)
                    }
                    "USER" -> Triple(userIds[rep.target]!!, userIds[rep.target]!!, SeedData.users.first { it.key == rep.target }.name)
                    else -> {
                        val mid = firstSellerMessage[rep.target]!!
                        val l = SeedData.listings.first { it.key == rep.target }
                        Triple(mid, userIds[l.seller]!!, c.queryOne("SELECT body FROM messages WHERE id = ?", mid) { it.getString(1) } ?: "")
                    }
                }
                c.update(
                    """INSERT INTO reports (id, reporter_id, target_type, target_id, target_user_id, reason, details, target_summary, status, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?) ON CONFLICT DO NOTHING""",
                    id("report:${rep.reporter}:${rep.type}:${rep.target}"), reporter, rep.type, targetId, targetUser,
                    rep.reason, rep.details, summary, now.minus(6, ChronoUnit.HOURS),
                )
            }
        }

        // ---- photo files (outside the transaction; skipped when already present)
        var rendered = 0
        for ((pid, _, tv) in photoRows) {
            val f = File(uploads, "seed-$pid.jpg")
            if (f.exists()) continue
            val listing = SeedData.listings.first { it.title == tv.first }
            val img = PlaceholderImages.render(listing.title, listing.cat, tv.second)
            f.writeBytes(img.bytes)
            db.txBlocking { c -> c.update("UPDATE photos SET size_bytes = ? WHERE id = ?", img.bytes.size.toLong(), pid) }
            rendered++
        }
        if (rendered > 0) println("Generated $rendered placeholder photos in ${uploads.absolutePath}")

        return db.txBlocking { c ->
            fun n(sql: String) = c.queryOne(sql) { it.getInt(1) }!!
            Summary(
                users = n("SELECT count(*) FROM users"),
                listings = n("SELECT count(*) FROM listings"),
                photos = n("SELECT count(*) FROM photos"),
                favorites = n("SELECT count(*) FROM favorites"),
                conversations = n("SELECT count(*) FROM conversations"),
                messages = n("SELECT count(*) FROM messages"),
                orders = n("SELECT count(*) FROM orders"),
                ratings = n("SELECT count(*) FROM ratings"),
                reports = n("SELECT count(*) FROM reports"),
            )
        }
    }

    /** Truncates every table except schema_migrations. */
    fun reset(db: Db) = db.txBlocking { c ->
        val tables = c.query("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'schema_migrations'") { it.getString(1) }
        if (tables.isNotEmpty()) c.createStatement().use { it.execute("TRUNCATE ${tables.joinToString()} CASCADE") }
    }
}
