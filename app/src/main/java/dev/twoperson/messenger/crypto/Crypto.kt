package dev.twoperson.messenger.crypto

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric primitives: SHA-256, HMAC-SHA256, HKDF (RFC 5869) and AES-256-GCM.
 * Pure JCA so the same code runs on Android and in JVM unit tests.
 */
object Crypto {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val AES_GCM = "AES/GCM/NoPadding"

    const val KEY_BYTES = 32
    const val TAG_BYTES = 16
    const val NONCE_BYTES = 12

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keyBytes = if (key.isEmpty()) ByteArray(KEY_BYTES) else key
        mac.init(SecretKeySpec(keyBytes, HMAC_ALGORITHM))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    /** HKDF-SHA256 extract-then-expand. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "length must be positive" }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(KEY_BYTES) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, take)
            offset += take
            counter++
        }
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Returns null when authentication fails; a tampered packet must never throw to callers. */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            null
        }
}
