package dev.twoperson.messenger.net

/**
 * On-the-wire framing shared by the handshake and the encrypted channel.
 *
 *   0..3   magic "TPM" + wire major version byte
 *   4      kind
 *   5      protocol version
 *   6..    kind-specific body
 *
 * Version 2 details:
 *  - DATA frames carry their own reliable 8-byte sequence inside the encrypted payload
 *    (`[seq][chunk]`), so ordering and acknowledgements never depend on the AEAD counter;
 *  - the AEAD counter is a pure nonce (with the replay window), and ACK/CONTROL frames do
 *    not occupy the data sequence space at all. That separation is what keeps the stream
 *    alive when an acknowledgement or a heartbeat is sent or lost between data frames.
 */
object Wire {

    const val VERSION = 2

    const val KIND_INITIATION = 1
    const val KIND_CONFIRMATION = 2
    const val KIND_DATA = 3
    const val KIND_ACK = 4
    const val KIND_CONTROL = 5

    const val HEADER_BYTES = 6
    const val DATA_PREFIX_BYTES = HEADER_BYTES + 8 + 2
    const val DATA_SEQ_BYTES = 8
    const val MAX_FRAME_BYTES = 1400

    val MAGIC = byteArrayOf('T'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), VERSION.toByte())

    fun isPeerPacket(data: ByteArray): Boolean {
        if (data.size < HEADER_BYTES) return false
        return data[0] == MAGIC[0] && data[1] == MAGIC[1] && data[2] == MAGIC[2] && data[3] == MAGIC[3]
    }

    fun kindOf(data: ByteArray): Int = if (data.size < HEADER_BYTES) -1 else data[4].toInt() and 0xFF

    fun header(kind: Int, bodySize: Int): ByteArray =
        ByteArray(HEADER_BYTES + bodySize).also {
            it[0] = MAGIC[0]
            it[1] = MAGIC[1]
            it[2] = MAGIC[2]
            it[3] = MAGIC[3]
            it[4] = kind.toByte()
            it[5] = VERSION.toByte()
        }
}
