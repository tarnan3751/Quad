package com.campusmarket.app.data

import com.campusmarket.core.ApiJson
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.UserPrivate
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** Everything persisted about a signed-in session. [user] is the last known profile (refreshed on launch). */
@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
    val user: UserPrivate,
)

fun AuthResponse.toStoredSession() = StoredSession(accessToken, refreshToken, accessTokenExpiresAt, user)

/**
 * Persists the session. The in-memory [session] flow is the single source of truth for "am I signed in":
 * [SessionManager.state] is derived from it, so clearing the store signs the user out everywhere.
 */
interface TokenStore {
    val session: StateFlow<StoredSession?>
    fun current(): StoredSession? = session.value
    fun save(session: StoredSession)
    fun clear()
}

/**
 * Platform-secure storage for one small secret string (the serialized session).
 * Implementations: iOS Keychain, Android Keystore-encrypted prefs, and (desktop dev harness only) plain settings.
 * Reads and writes are synchronous and cheap; they throw on any failure (undecryptable data, keystore problems).
 */
interface SecretStorage {
    /** True when the secret is protected by the OS (Keychain / Keystore). False only for the desktop dev harness. */
    val isSecure: Boolean
    /** Returns null when nothing is stored. Throws when something is stored but cannot be read. */
    fun read(): String?
    fun write(value: String)
    fun delete()
}

/**
 * [TokenStore] backed by a [SecretStorage].
 *
 * - Migration: if [legacy] settings still hold a session from the old plain-text store, it is moved into [secrets]
 *   and the plain copy is deleted. The plain copy is removed even when it cannot be migrated (corrupt, or the secure
 *   write failed) so tokens never linger unprotected.
 * - Failure: if the stored secret cannot be read or decoded (e.g. the Keystore key was invalidated), the stored
 *   session is cleared and the user starts signed out; it never crashes.
 * - If persisting a session fails, the session stays in memory for this run only.
 */
class SecureTokenStore(
    private val secrets: SecretStorage,
    legacy: Settings? = null,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : TokenStore {
    private val _session = MutableStateFlow(load(legacy))
    override val session: StateFlow<StoredSession?> = _session.asStateFlow()

    private fun load(legacy: Settings?): StoredSession? {
        migrateLegacy(legacy)
        val raw = try {
            secrets.read() ?: return null
        } catch (e: Exception) {
            onError("secure session unreadable; clearing", e)
            wipe()
            return null
        }
        return try {
            ApiJson.decodeFromString(StoredSession.serializer(), raw)
        } catch (e: Exception) {
            onError("stored session corrupt; clearing", e)
            wipe()
            null
        }
    }

    private fun migrateLegacy(legacy: Settings?) {
        if (legacy == null) return
        val raw = legacy.getStringOrNull(LEGACY_KEY) ?: return
        try {
            ApiJson.decodeFromString(StoredSession.serializer(), raw) // only move something valid
            // A session already in the secure store is newer (e.g. a crash after a previous partial migration).
            if (runCatching { secrets.read() }.getOrNull() == null) secrets.write(raw)
        } catch (e: Exception) {
            onError("legacy session migration failed", e)
        } finally {
            legacy.remove(LEGACY_KEY)
        }
    }

    private fun wipe() {
        try { secrets.delete() } catch (e: Exception) { onError("secure delete failed", e) }
    }

    override fun save(session: StoredSession) {
        try {
            secrets.write(ApiJson.encodeToString(StoredSession.serializer(), session))
        } catch (e: Exception) {
            onError("could not persist session", e)
        }
        _session.value = session
    }

    override fun clear() {
        wipe()
        _session.value = null
    }

    companion object {
        /** Key of the old plain-text store; also the key used by [SettingsSecretStorage]. */
        const val LEGACY_KEY = "session.v1"
    }
}

/**
 * NOT SECURE. [SecretStorage] over plain multiplatform-settings (java.util.prefs on desktop). Only for the
 * desktop dev harness; never use it for a shipping platform.
 */
class SettingsSecretStorage(private val settings: Settings) : SecretStorage {
    override val isSecure: Boolean get() = false
    override fun read(): String? = settings.getStringOrNull(SecureTokenStore.LEGACY_KEY)
    override fun write(value: String) = settings.putString(SecureTokenStore.LEGACY_KEY, value)
    override fun delete() = settings.remove(SecureTokenStore.LEGACY_KEY)
}

/** Non-persistent store for tests and previews. */
class InMemoryTokenStore(initial: StoredSession? = null) : TokenStore {
    private val _session = MutableStateFlow(initial)
    override val session: StateFlow<StoredSession?> = _session.asStateFlow()
    override fun save(session: StoredSession) { _session.value = session }
    override fun clear() { _session.value = null }
}
