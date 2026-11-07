package dev.twoperson.messenger.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileDigestTest {

    @Test
    fun matchesKnownFnv1aVectors() {
        assertEquals("cbf29ce484222325", FileDigest.hex(FileDigest.of(ByteArray(0))))
        assertEquals("af63dc4c8601ec8c", FileDigest.hex(FileDigest.of("a".toByteArray())))
        assertEquals("85944171f73967e8", FileDigest.hex(FileDigest.of("foobar".toByteArray())))
    }

    @Test
    fun chunkedUpdateEqualsOneShotHash() {
        val bytes = ByteArray(64 * 1024) { (it * 37 % 251).toByte() }
        val oneShot = FileDigest.of(bytes)

        var chunked = FileDigest.OFFSET_BASIS
        var offset = 0
        for (step in intArrayOf(1, 7, 1000, 4096, 30_000)) {
            val length = minOf(step, bytes.size - offset)
            if (length <= 0) break
            chunked = FileDigest.update(chunked, bytes, offset, length)
            offset += length
        }
        if (offset < bytes.size) {
            chunked = FileDigest.update(chunked, bytes, offset, bytes.size - offset)
        }

        assertEquals(oneShot, chunked)
        assertEquals(FileDigest.hex(oneShot), FileDigest.hex(chunked))
    }

    @Test
    fun offsetAndLengthAreRespected() {
        val bytes = "prefix-payload-suffix".toByteArray()
        val payload = "payload".toByteArray()
        val fromSlice = FileDigest.update(
            FileDigest.OFFSET_BASIS,
            bytes,
            "prefix-".length,
            payload.size
        )
        assertEquals(FileDigest.of(payload), fromSlice)
    }

    @Test
    fun differentContentProducesDifferentDigest() {
        assertNotEquals(FileDigest.of("abc".toByteArray()), FileDigest.of("abd".toByteArray()))
        assertNotEquals(FileDigest.of(byteArrayOf(0)), FileDigest.of(byteArrayOf(1)))
    }
}
