package dev.twoperson.messenger.net

import dev.twoperson.messenger.crypto.Crypto
import dev.twoperson.messenger.crypto.X25519
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Symmetric authenticated key exchange for exactly two peers.
 *
 * Both sides send an initiation carrying a fresh ephemeral X25519 public key, a nonce and
 * their long-term identity key. The initiation MAC is keyed by the invitation secret, so
 * only a device that knows the invitation can take part. The shared secret is an ephemeral
 * X25519 agreement, the transcript is hashed into the key derivation, and a confirmation
 * MAC proves both sides derived the same keys.
 *
 * This is a Noise-XX-style exchange adapted to a two-party channel; no message text is
 * ever sent in the clear.
 */
class Handshake(
    private val authKey: ByteArray,
    private val identityPrivateKey: ByteArray,
    identityPublicKey: ByteArray,
    random: SecureRandom = SecureRandom()
) {

    private class PeerInit(
        val nonce: ByteArray,
        val ephemeralPublic: ByteArray,
        val identityPublic: ByteArray
    )

    private class Keys(
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val confirmKey: ByteArray,
        val transcriptHash: ByteArray
    )

    val ephemeralPrivate: ByteArray = X25519.generatePrivateKey(random)
    val ephemeralPublic: ByteArray = X25519.publicKey(ephemeralPrivate)
    val nonce: ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
    val identityPublicKey: ByteArray = identityPublicKey.copyOf()

    private var peerInit: PeerInit? = null
    private var keys: Keys? = null
    private var confirmed = false

    var peerIdentityPublicKey: ByteArray? = null
        private set

    val hasPeerInitiation: Boolean get() = peerInit != null

    val ready: Boolean get() = confirmed && keys != null

    val initiationPacket: ByteArray

    init {
        val body = ByteBuffer.allocate(NONCE_BYTES + 32 + 32)
            .put(nonce)
            .put(ephemeralPublic)
            .put(this.identityPublicKey)
        val bodyBytes = body.array()
        val mac = Crypto.hmacSha256(authKey, Wire.MAGIC, byteArrayOf(Wire.KIND_INITIATION.toByte(), Wire.VERSION.toByte()), bodyBytes)
        initiationPacket = Wire.header(Wire.KIND_INITIATION, bodyBytes.size + mac.size).also {
            bodyBytes.copyInto(it, Wire.HEADER_BYTES)
            mac.copyInto(it, Wire.HEADER_BYTES + bodyBytes.size)
        }
    }

    /**
     * Processes the peer's initiation. Returns false when the packet is malformed, tampered
     * with, or does not prove knowledge of the invitation secret.
     */
    fun acceptInitiation(packet: ByteArray): Boolean {
        if (packet.size != Wire.HEADER_BYTES + NONCE_BYTES + 32 + 32 + 32) return false
        if (!Wire.isPeerPacket(packet) || Wire.kindOf(packet) != Wire.KIND_INITIATION) return false

        val body = packet.copyOfRange(Wire.HEADER_BYTES, packet.size - 32)
        val mac = packet.copyOfRange(packet.size - 32, packet.size)
        val expected = Crypto.hmacSha256(
            authKey,
            Wire.MAGIC,
            byteArrayOf(Wire.KIND_INITIATION.toByte(), Wire.VERSION.toByte()),
            body
        )
        if (!Crypto.constantTimeEquals(expected, mac)) return false

        val buffer = ByteBuffer.wrap(body)
        val peerNonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
        val peerEphemeral = ByteArray(32).also { buffer.get(it) }
        val peerIdentity = ByteArray(32).also { buffer.get(it) }

        val existing = peerInit
        if (existing != null) {
            // Retransmissions of the same initiation are expected; a different key mid
            // handshake is not.
            val same = existing.ephemeralPublic.contentEquals(peerEphemeral) &&
                existing.nonce.contentEquals(peerNonce) &&
                existing.identityPublic.contentEquals(peerIdentity)
            return same
        }
        if (peerEphemeral.contentEquals(ephemeralPublic)) return false

        val shared = X25519.sharedSecret(ephemeralPrivate, peerEphemeral) ?: return false
        peerInit = PeerInit(peerNonce, peerEphemeral, peerIdentity)
        peerIdentityPublicKey = peerIdentity
        keys = deriveKeys(peerNonce, peerEphemeral, peerIdentity, shared)
        return true
    }

    /**
     * True only for an initiation whose MAC proves knowledge of the invitation secret.
     *
     * [acceptInitiation] also rejects its own retransmissions and same-key reflections; a
     * caller that wants to distinguish "a real peer with a new handshake" from "a spoofed
     * packet" must not treat a bare `acceptInitiation == false` as the former, or anyone who
     * can send a UDP packet could tear down a healthy link.
     */
    fun isAuthenticInitiation(packet: ByteArray): Boolean =
        isAuthenticInitiation(authKey, packet)

    fun confirmationPacket(): ByteArray? {
        val current = keys ?: return null
        val body = current.transcriptHash.copyOf()
        val mac = Crypto.hmacSha256(
            current.confirmKey,
            Wire.MAGIC,
            byteArrayOf(Wire.KIND_CONFIRMATION.toByte(), Wire.VERSION.toByte()),
            body
        )
        return Wire.header(Wire.KIND_CONFIRMATION, body.size + mac.size).also {
            body.copyInto(it, Wire.HEADER_BYTES)
            mac.copyInto(it, Wire.HEADER_BYTES + body.size)
        }
    }

    fun acceptConfirmation(packet: ByteArray): Boolean {
        val current = keys ?: return false
        if (packet.size != Wire.HEADER_BYTES + 32 + 32) return false
        if (!Wire.isPeerPacket(packet) || Wire.kindOf(packet) != Wire.KIND_CONFIRMATION) return false

        val body = packet.copyOfRange(Wire.HEADER_BYTES, Wire.HEADER_BYTES + 32)
        val mac = packet.copyOfRange(Wire.HEADER_BYTES + 32, packet.size)
        val expected = Crypto.hmacSha256(
            current.confirmKey,
            Wire.MAGIC,
            byteArrayOf(Wire.KIND_CONFIRMATION.toByte(), Wire.VERSION.toByte()),
            body
        )
        if (!Crypto.constantTimeEquals(expected, mac)) return false
        if (!Crypto.constantTimeEquals(body, current.transcriptHash)) return false
        confirmed = true
        return true
    }

    fun sendKey(): ByteArray? = keys?.sendKey

    fun receiveKey(): ByteArray? = keys?.receiveKey

    private fun deriveKeys(
        peerNonce: ByteArray,
        peerEphemeral: ByteArray,
        peerIdentity: ByteArray,
        shared: ByteArray
    ): Keys {
        val localFirst = compareUnsigned(ephemeralPublic, peerEphemeral) < 0
        val transcript = if (localFirst) {
            Crypto.sha256(
                TRANSCRIPT_LABEL, ephemeralPublic, nonce, identityPublicKey,
                peerEphemeral, peerNonce, peerIdentity
            )
        } else {
            Crypto.sha256(
                TRANSCRIPT_LABEL, peerEphemeral, peerNonce, peerIdentity,
                ephemeralPublic, nonce, identityPublicKey
            )
        }
        val salt = Crypto.sha256(authKey, transcript)
        val okm = Crypto.hkdf(shared, salt, KEY_LABEL, 64)
        val first = okm.copyOfRange(0, 32)
        val second = okm.copyOfRange(32, 64)
        val confirmKey = Crypto.hkdf(shared, salt, CONFIRM_LABEL, 32)
        return Keys(
            sendKey = if (localFirst) first else second,
            receiveKey = if (localFirst) second else first,
            confirmKey = confirmKey,
            transcriptHash = transcript
        )
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val size = minOf(a.size, b.size)
        for (i in 0 until size) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    companion object {
        private const val NONCE_BYTES = 16
        private const val INITIATION_BYTES = Wire.HEADER_BYTES + NONCE_BYTES + 32 + 32 + 32
        private val TRANSCRIPT_LABEL = "tpmsg/v1/transcript".toByteArray(Charsets.US_ASCII)
        private val KEY_LABEL = "tpmsg/v1/keys".toByteArray(Charsets.US_ASCII)
        private val CONFIRM_LABEL = "tpmsg/v1/confirm".toByteArray(Charsets.US_ASCII)

        /**
         * MAC-only check that [packet] is an initiation proving knowledge of the invitation.
         *
         * Unlike [Handshake.acceptInitiation] this does not generate an ephemeral key or touch
         * any state, so the UDP reader may call it for every packet with no side effects.
         */
        fun isAuthenticInitiation(authKey: ByteArray, packet: ByteArray): Boolean {
            if (packet.size != INITIATION_BYTES) return false
            if (!Wire.isPeerPacket(packet) || Wire.kindOf(packet) != Wire.KIND_INITIATION) return false
            val body = packet.copyOfRange(Wire.HEADER_BYTES, packet.size - 32)
            val mac = packet.copyOfRange(packet.size - 32, packet.size)
            val expected = Crypto.hmacSha256(
                authKey,
                Wire.MAGIC,
                byteArrayOf(Wire.KIND_INITIATION.toByte(), Wire.VERSION.toByte()),
                body
            )
            return Crypto.constantTimeEquals(expected, mac)
        }

        /** The long-term identity key carried by a well-shaped initiation, or null. */
        fun initiationIdentity(packet: ByteArray): ByteArray? {
            if (packet.size != INITIATION_BYTES) return null
            if (!Wire.isPeerPacket(packet) || Wire.kindOf(packet) != Wire.KIND_INITIATION) return null
            val start = Wire.HEADER_BYTES + NONCE_BYTES + 32
            return packet.copyOfRange(start, start + 32)
        }
    }
}
