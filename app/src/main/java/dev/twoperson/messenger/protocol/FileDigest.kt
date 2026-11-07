package dev.twoperson.messenger.protocol

/**
 * 64-bit FNV-1a hash used as an end-to-end consistency check for file transfers.
 *
 * Why not SHA-256?
 *  - every frame already travels inside AES-256-GCM, so the network cannot alter bytes;
 *  - this check exists to catch application-level bugs (wrong offset, skipped block, a source
 *    file that changed between attempts) and to prove that the receiver assembled exactly the
 *    bytes the sender streamed;
 *  - unlike a cryptographic digest, its entire state is a single [Long], so it can be saved in
 *    SQLite and resumed after a disconnect without re-reading the whole file.
 *
 * FNV-1a is not a cryptographic hash and must not be used for authentication. The transport
 * authentication and AES-GCM tags are the security boundary.
 */
object FileDigest {

    /** FNV-1a 64-bit offset basis (0xcbf29ce484222325). */
    const val OFFSET_BASIS: Long = -3750763034362895579L

    /** FNV-1a 64-bit prime (0x100000001b3). */
    const val PRIME: Long = 1099511628211L

    fun update(hash: Long, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        var value = hash
        val end = offset + length
        var index = offset
        while (index < end) {
            value = value xor (bytes[index].toLong() and 0xFFL)
            value *= PRIME
            index++
        }
        return value
    }

    fun of(bytes: ByteArray): Long = update(OFFSET_BASIS, bytes)

    fun hex(hash: Long): String = java.lang.Long.toHexString(hash).padStart(16, '0')
}
