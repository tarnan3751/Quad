package com.campusmarket.server.photos

import com.campusmarket.server.Db
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import com.campusmarket.server.uuid
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID

/** Tunables of the photos feature. Tests (or an embedding application) can override them via [PhotoSettingsKey]. */
data class PhotoSettings(
    /** Make the missing variants of earlier photos in the background at startup (`PHOTO_VARIANT_BACKFILL`, default true). */
    val backfillVariants: Boolean = true,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) =
            PhotoSettings(backfillVariants = env["PHOTO_VARIANT_BACKFILL"]?.trim()?.toBooleanStrictOrNull() ?: true)
    }
}

/** Application attribute that overrides [PhotoSettings.fromEnv]; must be set before routing is built. */
val PhotoSettingsKey = AttributeKey<PhotoSettings>("campusmarket.PhotoSettings")

/**
 * Makes the variants of photos stored before variants existed (`photos.variants_done = false`), oldest first, one at a
 * time. A photo whose variants fail for a reason other than its content (storage trouble) is skipped for this run and
 * retried by the next one (every startup).
 */
internal class VariantBackfill(private val db: Db, private val variants: PhotoVariants, private val batchSize: Int = 100) {
    private val log = LoggerFactory.getLogger(VariantBackfill::class.java)

    /** Processes every pending photo; returns how many were completed. */
    suspend fun run(): Int {
        val pending = db.tx { c -> c.queryOne("SELECT count(*) FROM photos WHERE NOT variants_done") { it.getInt(1) } ?: 0 }
        if (pending == 0) return 0
        log.info("Making size variants of {} earlier photo(s)", pending)
        val failed = HashSet<UUID>()
        var done = 0
        while (true) {
            val batch = db.tx { c ->
                c.query(
                    """SELECT id, storage_key FROM photos WHERE NOT variants_done AND NOT (id = ANY(?::uuid[]))
                       ORDER BY created_at, id LIMIT ?""",
                    failed.toList(), batchSize,
                ) { it.uuid("id") to it.getString("storage_key") }
            }
            if (batch.isEmpty()) break
            for ((id, key) in batch) {
                val keys = try {
                    variants.make(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Couldn't make the variants of photo {} ({}); will retry at the next start", id, key, e)
                    failed += id
                    continue
                }
                db.tx { c ->
                    c.update(
                        "UPDATE photos SET thumb_key = ?, display_key = ?, variants_done = true WHERE id = ? AND storage_key = ?",
                        keys.thumb, keys.display, id, key,
                    )
                }
                done++
            }
        }
        log.info("Photo variants: {} done, {} failed", done, failed.size)
        return done
    }
}
