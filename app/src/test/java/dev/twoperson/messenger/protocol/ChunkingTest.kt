package dev.twoperson.messenger.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkingTest {

    @Test
    fun reassemblesOutOfOrderAndIgnoresDuplicates() {
        val payload = ByteArray(1500) { (it % 251).toByte() }
        val chunks = Chunking.chunk(42, payload)
        assertTrue(chunks.size > 1)
        val reassembler = Reassembler()
        var assembled: ByteArray? = null
        for (chunk in chunks.reversed()) {
            assembled = reassembler.accept(chunk, 1_000) ?: assembled
        }
        assertArrayEquals(payload, assembled)

        // Duplicate chunks after completion must not resurrect the message.
        assertNull(reassembler.accept(chunks.first(), 1_001))
    }

    @Test
    fun rejectsInconsistentChunkCount() {
        val reassembler = Reassembler()
        val good = Chunking.chunk(7, ByteArray(Chunking.MAX_CHUNK_DATA + 100) { 1 })
        assertNull(reassembler.accept(good[0], 1))
        val header = good[0].copyOf()
        header[7] = (good.size + 5).toByte()
        assertNull(reassembler.accept(header, 2))
    }

    @Test
    fun rejectsMalformedChunks() {
        val reassembler = Reassembler()
        assertNull(reassembler.accept(ByteArray(0), 0))
        assertNull(reassembler.accept(ByteArray(Chunking.HEADER_BYTES), 0))
        val tooBig = ByteArray(Chunking.HEADER_BYTES + Chunking.MAX_CHUNK_DATA + 1)
        assertNull(reassembler.accept(tooBig, 0))
    }

    @Test
    fun expiresStaleAssemblies() {
        val reassembler = Reassembler(maxAgeMs = 100)
        val chunks = Chunking.chunk(9, ByteArray(1400) { 3 })
        assertNull(reassembler.accept(chunks[0], 0))
        assertNull(reassembler.accept(chunks[1], 1_000))
        // The first fragment was evicted, so the remaining ones never complete.
        var assembled: ByteArray? = null
        for (index in 2 until chunks.size) {
            assembled = reassembler.accept(chunks[index], 1_001) ?: assembled
        }
        assertNull(assembled)
    }

    @Test
    fun reassemblesPayloadAtMaximumChunkCount() {
        val payload = ByteArray(Chunking.MAX_CHUNK_DATA * (Chunking.MAX_CHUNKS - 1)) {
            (it * 31 % 251).toByte()
        }
        val chunks = Chunking.chunk(11, payload)
        assertTrue(chunks.size in 2..Chunking.MAX_CHUNKS)
        val reassembler = Reassembler(maxMessageBytes = payload.size)
        var assembled: ByteArray? = null
        for (chunk in chunks.reversed()) {
            assembled = reassembler.accept(chunk, 5_000) ?: assembled
        }
        assertArrayEquals(payload, assembled)
    }
}
