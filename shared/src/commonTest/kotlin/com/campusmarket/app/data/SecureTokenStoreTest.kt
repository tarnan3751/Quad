package com.campusmarket.app.data

import com.campusmarket.core.ApiJson
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** String-only in-memory [Settings] (the settings-test artifact is not a dependency). */
private class FakeSettings : Settings {
    private val map = mutableMapOf<String, String>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun remove(key: String) { map.remove(key) }
    override fun hasKey(key: String) = key in map
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String, defaultValue: String) = map[key] ?: defaultValue
    override fun getStringOrNull(key: String) = map[key]
    override fun putInt(key: String, value: Int) = TODO()
    override fun getInt(key: String, defaultValue: Int) = TODO()
    override fun getIntOrNull(key: String) = TODO()
    override fun putLong(key: String, value: Long) = TODO()
    override fun getLong(key: String, defaultValue: Long) = TODO()
    override fun getLongOrNull(key: String) = TODO()
    override fun putFloat(key: String, value: Float) = TODO()
    override fun getFloat(key: String, defaultValue: Float) = TODO()
    override fun getFloatOrNull(key: String) = TODO()
    override fun putDouble(key: String, value: Double) = TODO()
    override fun getDouble(key: String, defaultValue: Double) = TODO()
    override fun getDoubleOrNull(key: String) = TODO()
    override fun putBoolean(key: String, value: Boolean) = TODO()
    override fun getBoolean(key: String, defaultValue: Boolean) = TODO()
    override fun getBooleanOrNull(key: String) = TODO()
}

private class FakeSecrets(var value: String? = null, override val isSecure: Boolean = true) : SecretStorage {
    var failReads = false
    var failWrites = false
    override fun read(): String? {
        if (failReads) throw IllegalStateException("key invalidated")
        return value
    }
    override fun write(value: String) {
        if (failWrites) throw IllegalStateException("keystore unavailable")
        this.value = value
    }
    override fun delete() { value = null }
}

class SecureTokenStoreTest {
    private val stored = StoredSession("access", "refresh", 123L, testUser)

    private fun json(s: StoredSession) = ApiJson.encodeToString(StoredSession.serializer(), s)

    @Test
    fun saveThenReloadRoundTripsAndClearDeletes() {
        val secrets = FakeSecrets()
        val a = SecureTokenStore(secrets)
        assertNull(a.current())
        a.save(stored)
        assertEquals(stored, SecureTokenStore(secrets).current())
        a.clear()
        assertNull(a.current())
        assertNull(secrets.value)
        assertNull(SecureTokenStore(secrets).current())
    }

    @Test
    fun migratesLegacyPlainSessionAndDeletesPlainCopy() {
        val legacy = FakeSettings()
        legacy.putString(SecureTokenStore.LEGACY_KEY, json(stored))
        val secrets = FakeSecrets()

        val store = SecureTokenStore(secrets, legacy)

        assertEquals(stored, store.current())
        assertFalse(legacy.hasKey(SecureTokenStore.LEGACY_KEY))
        assertEquals(stored, SecureTokenStore(secrets).current())
    }

    @Test
    fun migrationKeepsNewerSecureSession() {
        val legacy = FakeSettings()
        legacy.putString(SecureTokenStore.LEGACY_KEY, json(stored))
        val newer = stored.copy(accessToken = "newer")
        val store = SecureTokenStore(FakeSecrets(json(newer)), legacy)
        assertEquals(newer, store.current())
        assertFalse(legacy.hasKey(SecureTokenStore.LEGACY_KEY))
    }

    @Test
    fun failedMigrationStillRemovesPlainTokens() {
        val legacy = FakeSettings()
        legacy.putString(SecureTokenStore.LEGACY_KEY, json(stored))
        val errors = mutableListOf<String>()
        val store = SecureTokenStore(FakeSecrets().apply { failWrites = true }, legacy) { m, _ -> errors += m }
        assertNull(store.current())
        assertFalse(legacy.hasKey(SecureTokenStore.LEGACY_KEY))
        assertEquals(1, errors.size)
    }

    @Test
    fun corruptLegacyIsDroppedNotMigrated() {
        val legacy = FakeSettings()
        legacy.putString(SecureTokenStore.LEGACY_KEY, "{not json")
        val secrets = FakeSecrets()
        assertNull(SecureTokenStore(secrets, legacy).current())
        assertFalse(legacy.hasKey(SecureTokenStore.LEGACY_KEY))
        assertNull(secrets.value)
    }

    @Test
    fun unreadableSecretClearsSessionInsteadOfCrashing() {
        val secrets = FakeSecrets(json(stored)).apply { failReads = true }
        val store = SecureTokenStore(secrets)
        assertNull(store.current())
        assertNull(secrets.value) // wiped so the next launch is clean
    }

    @Test
    fun corruptSecretClearsSession() {
        val secrets = FakeSecrets("garbage")
        assertNull(SecureTokenStore(secrets).current())
        assertNull(secrets.value)
    }

    @Test
    fun failedWriteKeepsSessionInMemoryOnly() {
        val secrets = FakeSecrets().apply { failWrites = true }
        val store = SecureTokenStore(secrets)
        store.save(stored)
        assertEquals(stored, store.current())
        assertNull(secrets.value)
    }

    @Test
    fun unreadableSecretMeansSessionManagerStartsSignedOut() = kotlinx.coroutines.test.runTest {
        val secrets = FakeSecrets(json(stored)).apply { failReads = true }
        val h = Harness { error("no request") }
        val sm = SessionManager(h.api, SecureTokenStore(secrets), backgroundScope)
        assertEquals(SessionState.SignedOut, sm.state.value)
    }

    @Test
    fun plainSettingsSecretStorageIsInsecureAndRoundTrips() {
        val s = SettingsSecretStorage(FakeSettings())
        assertFalse(s.isSecure)
        assertNull(s.read())
        s.write("x")
        assertEquals("x", s.read())
        s.delete()
        assertNull(s.read())
    }
}
