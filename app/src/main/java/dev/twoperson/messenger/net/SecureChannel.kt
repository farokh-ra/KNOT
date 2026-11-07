package dev.twoperson.messenger.net

import dev.twoperson.messenger.crypto.Crypto
import java.nio.ByteBuffer

data class Frame(val kind: Int, val counter: Long, val payload: ByteArray)

sealed interface OpenResult {
    data class Ok(val frame: Frame) : OpenResult
    /** Authentic but already seen; callers re-acknowledge instead of re-delivering. */
    data class Duplicate(val kind: Int, val counter: Long) : OpenResult
    object Invalid : OpenResult
}

/**
 * Encrypted frame channel: AES-256-GCM with a per-direction monotonic counter, a replay
 * window of 128 frames and the header bound as associated data.
 */
class SecureChannel(
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray
) {

    private var sendCounter = 0L
    private var highestReceived = -1L
    private val window = LongArray(2)

    fun seal(kind: Int, payload: ByteArray): ByteArray {
        val counter = sendCounter++
        val nonce = nonce(counter)
        val aad = aad(kind, counter)
        val ciphertext = Crypto.aesGcmEncrypt(sendKey, nonce, payload, aad)
        return Wire.header(kind, 8 + 2 + ciphertext.size).also {
            ByteBuffer.wrap(it, Wire.HEADER_BYTES, 10).putLong(counter).putShort(ciphertext.size.toShort())
            ciphertext.copyInto(it, Wire.DATA_PREFIX_BYTES)
        }
    }

    fun open(packet: ByteArray): OpenResult {
        if (packet.size < Wire.DATA_PREFIX_BYTES + Crypto.TAG_BYTES) return OpenResult.Invalid
        if (!Wire.isPeerPacket(packet)) return OpenResult.Invalid
        val kind = Wire.kindOf(packet)
        val header = ByteBuffer.wrap(packet, Wire.HEADER_BYTES, 10)
        val counter = header.long
        val length = header.short.toInt() and 0xFFFF
        if (counter < 0) return OpenResult.Invalid
        if (length != packet.size - Wire.DATA_PREFIX_BYTES) return OpenResult.Invalid
        if (packet.size > Wire.MAX_FRAME_BYTES) return OpenResult.Invalid

        val ciphertext = packet.copyOfRange(Wire.DATA_PREFIX_BYTES, packet.size)
        val payload = Crypto.aesGcmDecrypt(receiveKey, nonce(counter), ciphertext, aad(kind, counter))
            ?: return OpenResult.Invalid

        if (highestReceived >= 0 && counter <= highestReceived - WINDOW_BITS) return OpenResult.Invalid
        if (isSeen(counter)) return OpenResult.Duplicate(kind, counter)
        markSeen(counter)
        return OpenResult.Ok(Frame(kind, counter, payload))
    }

    private fun nonce(counter: Long): ByteArray =
        ByteBuffer.allocate(Crypto.NONCE_BYTES).putInt(0).putLong(counter).array()

    private fun aad(kind: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(Wire.HEADER_BYTES + 8)
            .put(Wire.MAGIC)
            .put(kind.toByte())
            .put(Wire.VERSION.toByte())
            .putLong(counter)
            .array()

    private fun isSeen(counter: Long): Boolean {
        val delta = highestReceived - counter
        if (delta < 0) return false
        if (delta >= WINDOW_BITS) return true
        val index = delta.toInt()
        return (window[index / 64] shr (index % 64)) and 1L == 1L
    }

    private fun markSeen(counter: Long) {
        if (counter > highestReceived) {
            val shift = counter - highestReceived
            if (shift >= WINDOW_BITS) {
                window[0] = 0
                window[1] = 0
            } else {
                shiftLeft(shift.toInt())
            }
            highestReceived = counter
            // Bit 0 always represents the highest counter seen so far.
            window[0] = window[0] or 1L
        } else {
            val delta = (highestReceived - counter).toInt()
            if (delta in 0 until WINDOW_BITS) {
                window[delta / 64] = window[delta / 64] or (1L shl (delta % 64))
            }
        }
    }

    private fun shiftLeft(bits: Int) {
        if (bits <= 0) return
        if (bits >= WINDOW_BITS) {
            window[0] = 0
            window[1] = 0
            return
        }
        if (bits >= 64) {
            window[1] = window[0]
            window[0] = 0
            shiftLeft(bits - 64)
            return
        }
        val low = window[0] shl bits
        val high = (window[1] shl bits) or (window[0] ushr (64 - bits))
        window[0] = low
        window[1] = high
    }

    companion object {
        private const val WINDOW_BITS = 128
    }
}
