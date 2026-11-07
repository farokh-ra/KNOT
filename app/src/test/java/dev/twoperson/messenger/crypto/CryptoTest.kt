package dev.twoperson.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun hkdfMatchesRfc5869TestCaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expected, hex(Crypto.hkdf(ikm, salt, info, 42)))
    }

    @Test
    fun aesGcmRoundTrip() {
        val key = Crypto.randomBytes(32)
        val nonce = Crypto.randomBytes(12)
        val plaintext = "A short message with unicode ✓".toByteArray()
        val aad = byteArrayOf(1, 2, 3)
        val ciphertext = Crypto.aesGcmEncrypt(key, nonce, plaintext, aad)
        assertArrayEquals(plaintext, Crypto.aesGcmDecrypt(key, nonce, ciphertext, aad))
    }

    @Test
    fun aesGcmRejectsTamperedCiphertext() {
        val key = Crypto.randomBytes(32)
        val nonce = Crypto.randomBytes(12)
        val ciphertext = Crypto.aesGcmEncrypt(key, nonce, "hello".toByteArray(), byteArrayOf())
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        assertNull(Crypto.aesGcmDecrypt(key, nonce, ciphertext, byteArrayOf()))
    }

    @Test
    fun aesGcmRejectsWrongAad() {
        val key = Crypto.randomBytes(32)
        val nonce = Crypto.randomBytes(12)
        val ciphertext = Crypto.aesGcmEncrypt(key, nonce, "hello".toByteArray(), byteArrayOf(9))
        assertNull(Crypto.aesGcmDecrypt(key, nonce, ciphertext, byteArrayOf(8)))
    }

    @Test
    fun hmacIsDeterministicAndKeyDependent() {
        val data = "message".toByteArray()
        assertTrue(Crypto.constantTimeEquals(Crypto.hmacSha256(byteArrayOf(1), data), Crypto.hmacSha256(byteArrayOf(1), data)))
        assertTrue(!Crypto.constantTimeEquals(Crypto.hmacSha256(byteArrayOf(1), data), Crypto.hmacSha256(byteArrayOf(2), data)))
    }
}
