package com.campusmarket.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * bcrypt password hashing (`at.favre.lib:bcrypt`). Passwords longer than bcrypt's 72-byte input
 * limit are pre-hashed with SHA-512 instead of being silently truncated or rejected.
 * Hashing is CPU-bound, so both operations run on [Dispatchers.Default].
 */
class PasswordHasher(private val cost: Int) {
    private val version = BCrypt.Version.VERSION_2A
    private val longPasswords = LongPasswordStrategies.hashSha512(version)
    private val hasher = BCrypt.with(version, SecureRandom(), longPasswords)
    private val verifier = BCrypt.verifyer(version, longPasswords)

    /** Hash of a random password, verified against when the account doesn't exist so timing doesn't reveal it. */
    private val dummyHash: String by lazy { hashBlocking(UUID.randomUUID().toString()) }

    data class Check(val ok: Boolean, val needsRehash: Boolean)

    suspend fun hash(password: String): String = withContext(Dispatchers.Default) { hashBlocking(password) }

    /** Constant-time verification (the library compares digests in constant time). Malformed stored hashes never match. */
    suspend fun verify(password: String, storedHash: String?): Check = withContext(Dispatchers.Default) {
        if (storedHash == null) {
            verifier.verify(password.toCharArray(), dummyHash)
            return@withContext Check(ok = false, needsRehash = false)
        }
        val result = runCatching { verifier.verify(password.toCharArray(), storedHash) }.getOrNull()
        if (result == null || !result.validFormat || !result.verified) {
            if (result?.validFormat != true) verifier.verify(password.toCharArray(), dummyHash) // keep timing uniform
            Check(ok = false, needsRehash = false)
        } else {
            Check(ok = true, needsRehash = result.details.cost < cost)
        }
    }

    private fun hashBlocking(password: String): String = hasher.hashToString(cost, password.toCharArray())
}

/** Random secrets and their at-rest digests. */
class SecretCodec(jwtSecret: String) {
    private val random = SecureRandom()

    /**
     * Key for verification-code digests, derived from the server secret. A 6-digit code has only 10^6
     * values, so a plain hash would be trivially reversible from a database dump; a keyed MAC is not.
     */
    private val codeKey: ByteArray = hmac(jwtSecret.toByteArray(), "campus-market/verification-code/v1".toByteArray())

    /** Uniformly random 6-digit code (leading zeros kept). */
    fun newVerificationCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    /** Digest of [code] bound to [userId], so a digest can't be replayed onto another account. */
    fun verificationDigest(userId: UUID, code: String): String =
        HEX.formatHex(hmac(codeKey, "$userId:$code".toByteArray()))

    /** 256-bit opaque refresh token, URL-safe base64 without padding (43 chars). */
    fun newRefreshToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Refresh tokens are high-entropy, so an unkeyed SHA-256 is a sufficient at-rest digest (and indexable). */
    fun refreshTokenDigest(token: String): String =
        HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    companion object {
        private val HEX: HexFormat = HexFormat.of()

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        /** Constant-time string equality (length is not secret here: digests have a fixed length). */
        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
