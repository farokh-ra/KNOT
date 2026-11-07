package dev.twoperson.messenger.protocol

import java.nio.ByteBuffer

/**
 * Splits one serialised application message into transport-sized chunks and reassembles
 * them. Chunk layout:
 *
 *   0..3   chunk id (unsigned 32 bit)
 *   4..5   chunk index
 *   6..7   chunk count
 *   8..    payload bytes
 */
object Chunking {

    const val HEADER_BYTES = 8

    /**
     * Sized so one chunk plus the encrypted-frame overhead stays inside one Ethernet-sized
     * datagram. Larger chunks cut per-packet overhead and let a 16 KiB file block travel in
     * roughly fifteen frames instead of thirty-five.
     */
    const val MAX_CHUNK_DATA = 1100
    const val MAX_CHUNKS = 96

    fun chunk(chunkId: Int, payload: ByteArray): List<ByteArray> {
        require(payload.isNotEmpty()) { "empty payload" }
        val count = (payload.size + MAX_CHUNK_DATA - 1) / MAX_CHUNK_DATA
        require(count <= MAX_CHUNKS) { "payload too large to chunk" }
        val chunks = ArrayList<ByteArray>(count)
        for (index in 0 until count) {
            val from = index * MAX_CHUNK_DATA
            val to = minOf(payload.size, from + MAX_CHUNK_DATA)
            val data = to - from
            val packet = ByteArray(HEADER_BYTES + data)
            val buffer = ByteBuffer.wrap(packet)
            buffer.putInt(chunkId)
            buffer.putShort(index.toShort())
            buffer.putShort(count.toShort())
            buffer.put(payload, from, data)
            chunks.add(packet)
        }
        return chunks
    }
}

/**
 * Reassembles chunks into a single application message. Bounded in size, entry count and
 * age so a hostile or broken peer cannot exhaust memory.
 */
class Reassembler(
    private val maxMessageBytes: Int = Protocol.MAX_APP_MESSAGE_BYTES,
    private val maxAgeMs: Long = 60_000L,
    // The reliable link can have 4096 frames in flight (~270 maximum-size file blocks).
    // Keeping more assemblies pending than that prevents a busy file transfer from evicting
    // chunks that are still legitimately in transit.
    private val maxPending: Int = 512
) {

    private class Pending(val count: Int, var updatedAt: Long) {
        val parts: Array<ByteArray?> = arrayOfNulls(count)
        var received: Int = 0
    }

    private val pending = LinkedHashMap<Int, Pending>()

    /** Returns the complete payload once all chunks arrived, otherwise null. */
    @Synchronized
    fun accept(chunk: ByteArray, nowMs: Long): ByteArray? {
        if (chunk.size < Chunking.HEADER_BYTES + 1) return null
        if (chunk.size > Chunking.HEADER_BYTES + Chunking.MAX_CHUNK_DATA) return null
        val buffer = ByteBuffer.wrap(chunk)
        val chunkId = buffer.int
        val index = buffer.short.toInt() and 0xFFFF
        val count = buffer.short.toInt() and 0xFFFF
        if (count <= 0 || count > Chunking.MAX_CHUNKS || index >= count) return null
        if (count * Chunking.MAX_CHUNK_DATA > maxMessageBytes) return null

        evictExpired(nowMs)

        val existing = pending[chunkId]
        if (existing != null && existing.count != count) {
            pending.remove(chunkId)
            return null
        }
        val entry = existing ?: run {
            if (pending.size >= maxPending) {
                val oldest = pending.entries.minByOrNull { it.value.updatedAt }?.key
                if (oldest != null) pending.remove(oldest)
            }
            Pending(count, nowMs).also { pending[chunkId] = it }
        }
        entry.updatedAt = nowMs

        val dataLength = chunk.size - Chunking.HEADER_BYTES
        val previous = entry.parts[index]
        if (previous == null || previous.size != dataLength) {
            entry.parts[index] = chunk.copyOfRange(Chunking.HEADER_BYTES, chunk.size)
            if (previous == null) entry.received++
        }
        if (entry.received != entry.count) return null

        val total = entry.parts.sumOf { it?.size ?: 0 }
        if (total <= 0 || total > maxMessageBytes) {
            pending.remove(chunkId)
            return null
        }
        val assembled = ByteArray(total)
        var offset = 0
        for (part in entry.parts) {
            if (part == null) return null
            part.copyInto(assembled, offset)
            offset += part.size
        }
        pending.remove(chunkId)
        return assembled
    }

    @Synchronized
    fun clear() = pending.clear()

    private fun evictExpired(nowMs: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.updatedAt > maxAgeMs) iterator.remove()
        }
    }
}
