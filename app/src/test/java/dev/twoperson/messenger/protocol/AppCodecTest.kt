package dev.twoperson.messenger.protocol

import dev.twoperson.messenger.crypto.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCodecTest {

    @Test
    fun helloRoundTrips() {
        val hello = AppMessage.Hello(Protocol.WIRE_VERSION, Crypto.randomBytes(16), Crypto.randomBytes(32), "Pixel 7")
        val decoded = AppCodec.decode(AppCodec.encode(hello))
        assertEquals(hello, decoded)
    }

    @Test
    fun textRoundTripsWithUnicode() {
        val text = AppMessage.Text(
            Crypto.randomBytes(16),
            7,
            1_700_000_000_000,
            "Héllo 🌍 — direct only"
        )
        val decoded = AppCodec.decode(AppCodec.encode(text))
        assertEquals(text, decoded)
    }

    @Test
    fun ackRoundTrips() {
        val ack = AppMessage.Ack(Crypto.randomBytes(16))
        assertEquals(ack, AppCodec.decode(AppCodec.encode(ack)))
    }

    @Test
    fun rejectsUnknownType() {
        assertNull(AppCodec.decode(byteArrayOf(99, 1, 2, 3)))
    }

    @Test
    fun rejectsTruncatedTextPayload() {
        val encoded = AppCodec.encode(AppMessage.Text(Crypto.randomBytes(16), 1, 1_700_000_000_000, "hello"))
        for (cut in 1..10) {
            assertNull(AppCodec.decode(encoded.copyOfRange(0, encoded.size - cut)))
        }
    }

    @Test
    fun rejectsOversizedText() {
        val big = AppMessage.Text(Crypto.randomBytes(16), 1, 1_700_000_000_000, "x".repeat(2001))
        try {
            AppCodec.encode(big)
        } catch (e: IllegalArgumentException) {
            // Encoding is allowed to reject; decoding must too.
        }
        val headerOnly = ByteArray(1 + 16 + 8 + 8 + 2)
        headerOnly[0] = Protocol.TYPE_TEXT.toByte()
        // Claimed body length 0 but a huge blob appended is rejected by the exact-length check.
        val oversized = ByteArray(Protocol.MAX_APP_MESSAGE_BYTES + 1) { 1 }
        assertNull(AppCodec.decode(oversized))
        assertNull(AppCodec.decode(headerOnly))
    }

    @Test
    fun rejectsInvalidUtf8() {
        val body = byteArrayOf(0xC3.toByte(), 0x28)
        val buffer = java.nio.ByteBuffer.allocate(1 + 16 + 8 + 8 + 2 + body.size)
            .put(Protocol.TYPE_TEXT.toByte())
            .put(Crypto.randomBytes(16))
            .putLong(1)
            .putLong(1_700_000_000_000)
            .putShort(body.size.toShort())
            .put(body)
            .array()
        assertNull(AppCodec.decode(buffer))
    }

    @Test
    fun rejectsAbsurdTimestamps() {
        val text = AppMessage.Text(Crypto.randomBytes(16), 1, 42, "hello")
        val encoded = AppCodec.encode(text)
        assertNull(AppCodec.decode(encoded))
    }

    @Test
    fun rejectsWrongHelloVersion() {
        val hello = AppMessage.Hello(Protocol.WIRE_VERSION + 1, Crypto.randomBytes(16), Crypto.randomBytes(32), "x")
        assertNull(AppCodec.decode(AppCodec.encode(hello)))
    }

    @Test
    fun emptyTextIsRejectedOnDecode() {
        val encoded = ByteArray(1 + 16 + 8 + 8 + 2).also { buffer ->
            val wrapped = java.nio.ByteBuffer.wrap(buffer)
            wrapped.put(Protocol.TYPE_TEXT.toByte())
            wrapped.put(Crypto.randomBytes(16))
            wrapped.putLong(2)
            wrapped.putLong(1_700_000_000_000)
            wrapped.putShort(0)
        }
        assertNull(AppCodec.decode(encoded))
    }

    @Test
    fun encodedTextFitsChunking() {
        val encoded = AppCodec.encode(
            AppMessage.Text(Crypto.randomBytes(16), 1, 1_700_000_000_000, "y".repeat(2000))
        )
        val chunks = Chunking.chunk(1, encoded)
        assertTrue(chunks.size in 2 until Chunking.MAX_CHUNKS)
        assertNotNull(AppCodec.decode(encoded))
    }
}
