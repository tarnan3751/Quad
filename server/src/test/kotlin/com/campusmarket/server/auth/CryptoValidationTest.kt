package com.campusmarket.server.auth

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoValidationTest {

    @Test
    fun emailNormalisationAndDomains() {
        assertEquals("alice@state.edu", normalizeEmail("  Alice@STATE.Edu\t"))
        assertNull(emailError("first.last+tag@cs.state.edu"))
        assertEquals("state.edu", emailDomain("a@state.edu"))
        assertTrue(isAllowedDomain("state.edu", listOf(".edu")))
        assertTrue(isAllowedDomain("cs.state.edu", listOf("edu")))
        assertFalse(isAllowedDomain("stateedu.com", listOf(".edu")))
        assertFalse(isAllowedDomain("edu.com", listOf(".edu")))
        assertTrue(isAllowedDomain("mit.edu", listOf("MIT.edu")))
        assertFalse(isAllowedDomain("notmit.edu", listOf("mit.edu")))
        assertFalse(isAllowedDomain("state.edu", listOf("", ".")))
    }

    @Test
    fun profileTextNormalisation() {
        assertEquals("Ada Lovelace", normalizeDisplayName("  Ada \n  Lovelace "))
        assertNull(displayNameError("Zoë 🚲"))
        assertTrue(displayNameError("A\u0000B") != null)
        assertEquals("line one\n\nline two", normalizeBio("  line one\r\n\r\n\r\n\r\nline two  "))
        assertNull(bioError("multi\nline"))
        assertTrue(bioError("bad\u0007bell") != null)
        assertTrue(bioError("x".repeat(301)) != null)
    }

    @Test
    fun verificationCodesAndDigests() {
        val codec = SecretCodec("test-secret-test-secret-test-secret")
        val codes = List(500) { codec.newVerificationCode() }
        assertTrue(codes.all { it.length == 6 && it.all(Char::isDigit) })
        assertTrue(codes.toSet().size > 400)

        val user = UUID.randomUUID()
        val digest = codec.verificationDigest(user, "123456")
        assertEquals(digest, codec.verificationDigest(user, "123456"))
        assertNotEquals(digest, codec.verificationDigest(UUID.randomUUID(), "123456"), "digest is bound to the account")
        assertNotEquals(digest, SecretCodec("another-secret-another-secret-xx").verificationDigest(user, "123456"), "digest is keyed")
        assertTrue(SecretCodec.constantTimeEquals(digest, codec.verificationDigest(user, "123456")))
        assertFalse(SecretCodec.constantTimeEquals(digest, codec.verificationDigest(user, "123457")))
    }

    @Test
    fun refreshTokens() {
        val codec = SecretCodec("test-secret-test-secret-test-secret")
        val token = codec.newRefreshToken()
        assertEquals(43, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(token, codec.newRefreshToken())
        assertEquals(64, codec.refreshTokenDigest(token).length)
    }

    @Test
    fun passwordHashing() = runBlocking {
        val hasher = PasswordHasher(cost = 4)
        val hash = hasher.hash("hunter2hunter2")
        assertTrue(hasher.verify("hunter2hunter2", hash).ok)
        assertFalse(hasher.verify("hunter2hunter3", hash).ok)
        assertFalse(hasher.verify("hunter2hunter2", "not-a-bcrypt-hash").ok)
        assertFalse(hasher.verify("hunter2hunter2", null).ok)
        // > 72 bytes: the tail still matters (pre-hashed, not truncated)
        val long = "a".repeat(100)
        val longHash = hasher.hash(long)
        assertTrue(hasher.verify(long, longHash).ok)
        assertFalse(hasher.verify("a".repeat(99) + "b", longHash).ok)
        assertTrue(PasswordHasher(cost = 5).verify("hunter2hunter2", hash).needsRehash)
        assertFalse(hasher.verify("hunter2hunter2", hash).needsRehash)
    }
}
