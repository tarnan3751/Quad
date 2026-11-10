package com.campusmarket.app.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.campusmarket.app.data.SecretStorage
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore. The ciphertext (`iv || ciphertext+tag`, Base64)
 * lives in a dedicated private SharedPreferences file that is excluded from backup / device transfer
 * (see the manifest's `data_extraction_rules` / `backup_rules`).
 *
 * [name] picks the slot ("session": the signed-in session; "outbox": unsent chat messages), each with its own key.
 *
 * Any failure to decrypt (key invalidated, restored prefs without the key, tampering) throws from [read]; the
 * caller clears the session and the user signs in again. A failing [read]/[write] also discards the key so the
 * next write generates a fresh one.
 */
class KeystoreSecretStorage(context: Context, name: String = "session") : SecretStorage {
    // Each secret has its own key: a blob that can't be decrypted discards only its own key, never the session's.
    private val keyAlias = "campusmarket.$name.v1"
    private val prefKey = name
    private val aad = keyAlias.toByteArray()

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override val isSecure: Boolean get() = true

    @Synchronized
    override fun read(): String? {
        val stored = prefs.getString(prefKey, null) ?: return null
        try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            require(blob.size > IV_BYTES) { "blob too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingKey() ?: error("keystore key missing"),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            cipher.updateAAD(aad)
            return String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            discardKey()
            throw e
        }
    }

    @Synchronized
    override fun write(value: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey()) // the Keystore picks a fresh random IV
            cipher.updateAAD(aad)
            val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            // commit(): the token must be on disk before we report success.
            check(prefs.edit().putString(prefKey, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
                "prefs write failed"
            }
        } catch (e: Exception) {
            discardKey()
            throw e
        }
    }

    @Synchronized
    override fun delete() {
        prefs.edit().remove(prefKey).commit()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(keyAlias, null) as? SecretKey

    private fun createKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun discardKey() {
        try {
            val ks = keyStore()
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
        } catch (_: Exception) {
            // nothing more we can do
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val PREFS_FILE = "campusmarket_secure_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
