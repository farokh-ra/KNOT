package dev.twoperson.messenger.crypto

import dev.twoperson.messenger.util.Base32

/**
 * Invitation handling.
 *
 * - The invitation is a 32-byte secret from a cryptographically secure RNG.
 * - The DHT rendezvous key (topic) is derived from it with HMAC, and only that 20-byte
 *   hash is ever published to the DHT.
 * - Peers must prove knowledge of the secret during the handshake.
 */
object Invite {

    const val PREFIX = "TPM1"
    const val SECRET_BYTES = 32
    const val TOPIC_BYTES = 20

    private const val GROUP_SIZE = 4
    private val TOPIC_LABEL = "tpmsg/v1/topic".toByteArray(Charsets.US_ASCII)
    private val AUTH_LABEL = "tpmsg/v1/auth".toByteArray(Charsets.US_ASCII)

    fun newSecret(): ByteArray = Crypto.randomBytes(SECRET_BYTES)

    fun encode(secret: ByteArray): String {
        require(secret.size == SECRET_BYTES) { "invitation secret must be 32 bytes" }
        val body = Base32.encode(secret).chunked(GROUP_SIZE).joinToString("-")
        return "$PREFIX-$body"
    }

    /** Returns the 32-byte secret, or null for any malformed, truncated or foreign code. */
    fun decode(code: String): ByteArray? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.uppercase().startsWith(PREFIX)) return null
        val decoded = Base32.decode(trimmed.substring(PREFIX.length)) ?: return null
        return if (decoded.size == SECRET_BYTES) decoded else null
    }

    fun isValid(code: String): Boolean = decode(code) != null

    /** HMAC key proven by each side during the handshake. */
    fun authKey(secret: ByteArray): ByteArray = Crypto.hmacSha256(secret, AUTH_LABEL)

    /** 20-byte BitTorrent-DHT infohash used only for rendezvous. */
    fun topic(secret: ByteArray): ByteArray = Crypto.hmacSha256(authKey(secret), TOPIC_LABEL).copyOf(TOPIC_BYTES)
}
