package com.campusmarket.server.photos

import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Where photo bytes live. Keys are opaque, server-generated names (see [PhotoKeys]); the public URL of a
 * photo is `<publicBaseUrl>/uploads/<key>`.
 */
interface PhotoStorage {
    /** Directory for spooling in-flight uploads; ideally on the same file system as the store. */
    val scratchDir: Path

    /** Stores the finished file [source] under [key], taking ownership of it (it is moved or deleted). */
    suspend fun save(key: String, source: Path)

    /** The stored object for [key], or null if there is none (or the key is not a valid photo key). */
    suspend fun open(key: String): StoredPhoto?

    suspend fun delete(key: String)
}

class StoredPhoto(val format: ImageFormat, val size: Long, private val channel: () -> ByteReadChannel) {
    fun readChannel(): ByteReadChannel = channel()
}

/** Storage keys: `<32 hex chars>.<ext>` for new uploads; older/fixture keys may contain dashes. */
object PhotoKeys {
    private val pattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}\\.(jpg|png|webp)$")

    fun newKey(format: ImageFormat): String = UUID.randomUUID().toString().replace("-", "") + "." + format.extension

    /** The format implied by a well-formed key, or null if [key] is not a key we could have issued. */
    fun formatOf(key: String): ImageFormat? =
        if (pattern.matches(key)) ImageFormat.forExtension(key.substringAfterLast('.')) else null
}

/** Stores photos as flat files in [root] (`config.uploadsDir`). */
class LocalPhotoStorage(root: Path) : PhotoStorage {
    private val root: Path = root.toAbsolutePath().normalize()
    override val scratchDir: Path = this.root.resolve(".incoming")

    init {
        Files.createDirectories(this.root)
        Files.createDirectories(scratchDir)
        sweepAbandonedUploads()
    }

    /** Removes spool files left behind by a crash; an hour is far longer than any upload takes. */
    private fun sweepAbandonedUploads() {
        val cutoff = System.currentTimeMillis() - 3_600_000
        runCatching {
            Files.list(scratchDir).use { files ->
                files.filter { runCatching { Files.getLastModifiedTime(it).toMillis() < cutoff }.getOrDefault(false) }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    override suspend fun save(key: String, source: Path) = withContext(Dispatchers.IO) {
        val target = resolve(key) ?: run {
            Files.deleteIfExists(source)
            throw IllegalArgumentException("Invalid photo key")
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
        // temp files are created owner-only; published photos are world-readable (e.g. for a fronting web server)
        runCatching { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--")) }
        Unit
    }

    override suspend fun open(key: String): StoredPhoto? = withContext(Dispatchers.IO) {
        val format = PhotoKeys.formatOf(key) ?: return@withContext null
        val path = resolve(key) ?: return@withContext null
        if (!Files.isRegularFile(path)) return@withContext null
        StoredPhoto(format, Files.size(path)) { path.toFile().readChannel() }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        resolve(key)?.let { Files.deleteIfExists(it) }
        Unit
    }

    /** Maps a key to a file directly inside [root]; anything else (traversal, separators, bad names) is null. */
    private fun resolve(key: String): Path? {
        if (PhotoKeys.formatOf(key) == null) return null
        val path = root.resolve(key).normalize()
        return if (path.parent == root) path else null
    }
}
