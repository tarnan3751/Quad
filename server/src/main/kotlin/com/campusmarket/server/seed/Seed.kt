package com.campusmarket.server.seed

import at.favre.lib.crypto.bcrypt.BCrypt
import com.campusmarket.core.OrderStatus
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Dev seed: idempotently populates a realistic demo dataset by direct SQL.
 *
 * Run with `./gradlew :server:seed` (add `--args="--reset"` to truncate first). All ids are derived
 * deterministically from stable keys and every insert is `ON CONFLICT DO NOTHING` (photo rows are upserted, so a
 * replaced photo file takes effect), so running it again is a no-op.
 * Listing photos are the JPEGs bundled under `seed/photos/` on the classpath; a listing whose file is missing
 * gets a generated placeholder instead (and is named in the output).
 */
object Seed {
    data class Summary(
        val users: Int, val listings: Int, val photos: Int, val favorites: Int,
        val conversations: Int, val messages: Int, val orders: Int, val ratings: Int, val reports: Int,
        /** Keys of listings that got a generated placeholder because a bundled photo was missing. */
        val placeholders: List<String> = emptyList(),
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

    /** A seed photo file and its `photos` row. */
    private class SeedPhoto(val id: UUID, val ownerKey: String, val image: SeedImage, val created: Instant) {
        val storageKey = "seed-$id-${image.hash}.jpg"
    }

    /**
     * Seeds [db]. Returns row counts of the seeded dataset (after the run). [bcryptCost] is lowered by tests, which can
     * also swap the bundled [photos].
     */
    fun run(
        config: AppConfig,
        db: Db,
        reset: Boolean = false,
        bcryptCost: Int = 12,
        photos: (key: String, n: Int) -> SeedImage? = SeedPhotos::load,
    ): Summary {
        require(config.devMode) { "Refusing to seed with DEV_MODE=false" }
        System.setProperty("java.awt.headless", "true")
        if (reset) reset(db)
        val uploads = File(config.uploadsDir).apply { mkdirs() }
        val hash = BCrypt.withDefaults().hashToString(bcryptCost, SeedData.PASSWORD.toCharArray())
        val now = Instant.now()
        fun ago(d: Duration): Instant = now.minus(d.toJavaDuration())

        // ---- photo files first, so no row ever points at a missing file
        val placeholders = LinkedHashSet<String>()
        val avatars = SeedData.users.mapNotNull { u ->
            u.avatar?.let { u.key to SeedPhoto(id("avatar:${u.key}"), u.key, AvatarImages.render(it), ago(u.joined)) }
        }.toMap()
        val listingPhotos = SeedData.listings.associate { l ->
            l.key to (1..l.photos).map { n ->
                val image = photos(l.key, n) ?: PlaceholderImages.render(l.title, l.cat, n).also { placeholders += l.key }
                SeedPhoto(id("photo:${l.key}:${n - 1}"), l.seller, image, ago(l.posted))
            }
        }
        if (placeholders.isNotEmpty()) {
            println("Warning: no bundled photo for ${placeholders.size} listing(s), using generated placeholders: ${placeholders.joinToString()}")
        }
        val allPhotos = avatars.values + listingPhotos.values.flatten()
        for (p in allPhotos) {
            val f = File(uploads, p.storageKey)
            if (!f.exists()) f.writeBytes(p.image.bytes)
        }

        val userIds = HashMap<String, UUID>()
        val listingIds = HashMap<String, UUID>()
        val orderIds = HashMap<String, UUID>()
        val firstSellerMessage = HashMap<String, UUID>()

        db.txBlocking { c ->
            // ---- users (+ avatar photos)
            for (u in SeedData.users) {
                val avatar = avatars[u.key]
                c.update(
                    """INSERT INTO users (id, email, password_hash, display_name, bio, avatar_photo_id, campus_domain, role,
                                          email_verified, stripe_account_id, stripe_details_submitted, payouts_enabled, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    id("user:${u.key}"), u.email, hash, u.name, u.bio, avatar?.id, u.domain, u.role,
                    if (u.seller) "acct_fake_${u.key}" else null, u.seller, u.seller, ago(u.joined),
                )
                userIds[u.key] = userKeyToId(c, u.email)
            }
            for (p in allPhotos) {
                // upsert, so a re-run picks up replaced photo files without --reset
                c.update(
                    """INSERT INTO photos (id, owner_id, storage_key, content_type, size_bytes, width, height, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                       ON CONFLICT (id) DO UPDATE SET storage_key = EXCLUDED.storage_key, size_bytes = EXCLUDED.size_bytes,
                                                      width = EXCLUDED.width, height = EXCLUDED.height""",
                    p.id, userIds[p.ownerKey]!!, p.storageKey, p.image.contentType, p.image.bytes.size.toLong(),
                    p.image.width, p.image.height, p.created,
                )
            }

            // ---- listings
            for (l in SeedData.listings) {
                val seller = SeedData.user(l.seller)
                val lid = id("listing:${l.key}")
                val created = ago(l.posted)
                c.update(
                    """INSERT INTO listings (id, seller_id, campus_domain, title, description, category, condition, price_cents,
                                            status, pickup_location, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    lid, userIds[l.seller]!!, seller.domain, l.title, l.desc, l.cat, l.cond, l.cents, SeedData.statusOf(l),
                    l.pickup, created, created,
                )
                listingIds[l.key] = lid
                listingPhotos[l.key]!!.forEachIndexed { position, p ->
                    c.update(
                        "INSERT INTO listing_photos (listing_id, photo_id, position) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                        lid, p.id, position,
                    )
                }
            }

            // ---- favorites
            for (f in SeedData.favorites) {
                c.update(
                    "INSERT INTO favorites (user_id, listing_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    userIds[f.user]!!, listingIds[f.listing]!!, ago(f.ago),
                )
            }

            // ---- conversations + messages
            for (cv in SeedData.conversations) {
                val listing = SeedData.listing(cv.listing)
                val seller = userIds[listing.seller]!!
                val buyer = userIds[cv.buyer]!!
                val cid = id("conv:${cv.listing}:${cv.buyer}")
                val start = ago(cv.started)
                fun at(m: SeedData.M): Instant = start.plus(m.at.toJavaDuration())
                val inserted = c.update(
                    "INSERT INTO conversations (id, listing_id, buyer_id, seller_id, created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    cid, listingIds[cv.listing]!!, buyer, seller, start,
                ) == 1
                cv.messages.forEachIndexed { n, m ->
                    val mid = id("msg:$cid:$n")
                    c.update(
                        "INSERT INTO messages (id, conversation_id, sender_id, body, created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        mid, cid, if (m.fromBuyer) buyer else seller, m.body, at(m),
                    )
                    if (!m.fromBuyer && cv.listing !in firstSellerMessage) firstSellerMessage[cv.listing] = mid
                }
                // Only for a new thread, so a re-run doesn't undo what people did in it since. Everyone has read
                // everything, except an unread conversation's recipient: they last read the thread when they last
                // wrote in it (or never opened it).
                if (!inserted) continue
                val last = cv.messages.last()
                val recipientReadAt = if (cv.unread) cv.messages.lastOrNull { it.fromBuyer != last.fromBuyer }?.let(::at) else at(last)
                c.update(
                    """UPDATE conversations SET last_message_at = ?, last_message_preview = ?,
                              buyer_last_read_at = ?, seller_last_read_at = ?
                       WHERE id = ?""",
                    at(last), last.body.take(80),
                    if (last.fromBuyer) at(last) else recipientReadAt,
                    if (last.fromBuyer) recipientReadAt else at(last),
                    cid,
                )
            }

            // ---- orders
            for (o in SeedData.orders) {
                val l = SeedData.listing(o.listing)
                val oid = id("order:${o.listing}:${o.buyer}")
                val tag = oid.toString().take(8)
                val fee = l.cents * config.platformFeeBps / 10000
                val created = ago(o.placed)
                val paid = if (o.status == OrderStatus.CANCELLED) null else created.plus(4, ChronoUnit.MINUTES)
                val completed = if (o.status == OrderStatus.COMPLETED) paid!!.plus(o.handoff.toJavaDuration()) else null
                val cancelled = when (o.status) {
                    OrderStatus.CANCELLED -> created.plus(30.minutes.toJavaDuration())
                    OrderStatus.REFUNDED -> paid!!.plus(20.hours.toJavaDuration())
                    else -> null
                }
                c.update(
                    """INSERT INTO orders (id, listing_id, buyer_id, seller_id, amount_cents, fee_cents, status,
                                           stripe_checkout_session_id, stripe_payment_intent_id, stripe_transfer_id, stripe_refund_id,
                                           created_at, paid_at, completed_at, cancelled_at, release_clock_from)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    oid, listingIds[o.listing]!!, userIds[o.buyer]!!, userIds[l.seller]!!, l.cents, fee, o.status.name,
                    "cs_fake_$tag", if (paid != null) "pi_fake_$tag" else null,
                    if (completed != null) "tr_fake_$tag" else null,
                    if (o.status == OrderStatus.REFUNDED) "re_fake_$tag" else null,
                    created, paid, completed, cancelled,
                    if (o.status == OrderStatus.PAID || o.status == OrderStatus.DISPUTED) paid else null,
                )
                o.dispute?.let { d ->
                    c.update(
                        """INSERT INTO order_disputes (id, order_id, opened_by, reason, details, opened_at)
                           VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                        id("dispute:${o.listing}"), oid, userIds[o.buyer]!!, d.reason.name, d.details,
                        paid!!.plus(d.afterPayment.toJavaDuration()),
                    )
                }
                if (o.status == OrderStatus.COMPLETED) orderIds[o.listing] = oid
            }

            // ---- ratings, then recompute users.rating_sum/count from the ratings table (keeps it consistent)
            for (r in SeedData.ratings) {
                val o = SeedData.orders.first { it.listing == r.listing && it.status == OrderStatus.COMPLETED }
                val oid = orderIds[r.listing]!!
                val buyer = userIds[o.buyer]!!
                val seller = userIds[SeedData.listing(r.listing).seller]!!
                val at = ago(o.placed).plus((o.handoff + 3.hours).toJavaDuration())
                c.update(
                    "INSERT INTO ratings (id, order_id, rater_id, ratee_id, stars, comment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    id("rating:$oid:buyer"), oid, buyer, seller, r.buyerStars, r.buyerComment, at,
                )
                if (r.sellerStars != null) c.update(
                    "INSERT INTO ratings (id, order_id, rater_id, ratee_id, stars, comment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    id("rating:$oid:seller"), oid, seller, buyer, r.sellerStars, r.sellerComment, at.plus(2, ChronoUnit.HOURS),
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
                        val l = SeedData.listing(rep.target)
                        Triple(listingIds[rep.target]!!, userIds[l.seller]!!, l.title)
                    }
                    "USER" -> Triple(userIds[rep.target]!!, userIds[rep.target]!!, SeedData.user(rep.target).name)
                    else -> {
                        val mid = firstSellerMessage[rep.target]!!
                        val l = SeedData.listing(rep.target)
                        Triple(mid, userIds[l.seller]!!, c.queryOne("SELECT body FROM messages WHERE id = ?", mid) { it.getString(1) } ?: "")
                    }
                }
                c.update(
                    """INSERT INTO reports (id, reporter_id, target_type, target_id, target_user_id, reason, details, target_summary, status, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?) ON CONFLICT DO NOTHING""",
                    id("report:${rep.reporter}:${rep.type}:${rep.target}"), reporter, rep.type, targetId, targetUser,
                    rep.reason, rep.details, summary, ago(rep.ago),
                )
            }
        }

        pruneSeedFiles(db, uploads)

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
                placeholders = placeholders.toList(),
            )
        }
    }

    /** Deletes seed photo files (`seed-*`) that no `photos` row references any more, e.g. after --reset or a photo change. */
    private fun pruneSeedFiles(db: Db, uploads: File) {
        val live = db.txBlocking { c -> c.query("SELECT storage_key FROM photos WHERE storage_key LIKE 'seed-%'") { it.getString(1) } }.toSet()
        uploads.listFiles { f -> f.isFile && f.name.startsWith("seed-") && f.name !in live }?.forEach { it.delete() }
    }

    /** Truncates every table except schema_migrations. */
    fun reset(db: Db) = db.txBlocking { c ->
        val tables = c.query("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'schema_migrations'") { it.getString(1) }
        if (tables.isNotEmpty()) c.createStatement().use { it.execute("TRUNCATE ${tables.joinToString()} CASCADE") }
    }
}
