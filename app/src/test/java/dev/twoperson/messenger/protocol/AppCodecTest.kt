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
    fun routeHintRoundTripsEveryKnownRoute() {
        for (route in Protocol.ROUTE_LAN..Protocol.ROUTE_LAST_ENDPOINT) {
            assertEquals(
                AppMessage.RouteHint(route),
                AppCodec.decode(AppCodec.encode(AppMessage.RouteHint(route)))
            )
        }
    }

    @Test
    fun rejectsUnknownOrMalformedRouteHint() {
        assertNull(AppCodec.decode(byteArrayOf(Protocol.TYPE_ROUTE_HINT.toByte())))
        assertNull(AppCodec.decode(byteArrayOf(Protocol.TYPE_ROUTE_HINT.toByte(), 0)))
        assertNull(
            AppCodec.decode(
                byteArrayOf(
                    Protocol.TYPE_ROUTE_HINT.toByte(),
                    (Protocol.ROUTE_LAST_ENDPOINT + 1).toByte()
                )
            )
        )
        assertNull(AppCodec.decode(byteArrayOf(Protocol.TYPE_ROUTE_HINT.toByte(), 1, 2)))
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

    @Test
    fun fileOfferRoundTripsWithUnicodeName() {
        val offer = AppMessage.FileOffer(
            transferId = Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES),
            messageId = Crypto.randomBytes(Protocol.MESSAGE_ID_BYTES),
            sequence = 42,
            createdAt = 1_700_000_000_000,
            totalBytes = 12_345_678_901L,
            fileName = "résumé 2026 – final.pdf",
            mimeType = "application/pdf"
        )
        assertEquals(offer, AppCodec.decode(AppCodec.encode(offer)))
    }

    @Test
    fun fileDataRoundTripsAtMaximumBlockSize() {
        val block = Crypto.randomBytes(Protocol.MAX_FILE_DATA_BYTES)
        val data = AppMessage.FileData(
            Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES),
            9_876_543_210L,
            block
        )
        val decoded = AppCodec.decode(AppCodec.encode(data)) as? AppMessage.FileData
        assertNotNull(decoded)
        assertEquals(data.offset, decoded!!.offset)
        assertTrue(data.transferId.contentEquals(decoded.transferId))
        assertTrue(block.contentEquals(decoded.data))
    }

    @Test
    fun encodedMaxFileBlockFitsChunking() {
        val encoded = AppCodec.encode(
            AppMessage.FileData(
                Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES),
                0,
                Crypto.randomBytes(Protocol.MAX_FILE_DATA_BYTES)
            )
        )
        val chunks = Chunking.chunk(2, encoded)
        assertTrue(chunks.size <= Chunking.MAX_CHUNKS)
        val reassembler = Reassembler()
        var assembled: ByteArray? = null
        for (chunk in chunks.reversed()) {
            assembled = reassembler.accept(chunk, 1_000) ?: assembled
        }
        assertTrue(encoded.contentEquals(assembled))
    }

    @Test
    fun fileAckAndDoneRoundTrip() {
        val transferId = Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES)
        val ack = AppMessage.FileAck(transferId, 987_654_321L)
        assertEquals(ack, AppCodec.decode(AppCodec.encode(ack)))
        assertEquals(
            AppMessage.FileDone(transferId, true),
            AppCodec.decode(AppCodec.encode(AppMessage.FileDone(transferId, true)))
        )
        assertEquals(
            AppMessage.FileDone(transferId, false),
            AppCodec.decode(AppCodec.encode(AppMessage.FileDone(transferId, false)))
        )
        assertEquals(
            AppMessage.FileEnd(transferId),
            AppCodec.decode(AppCodec.encode(AppMessage.FileEnd(transferId)))
        )
    }

    @Test
    fun fileDigestFieldsRoundTrip() {
        val transferId = Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES)
        val digest = FileDigest.of("some file bytes".toByteArray())

        val ack = AppMessage.FileAck(transferId, 42_000, digest)
        assertEquals(ack, AppCodec.decode(AppCodec.encode(ack)))

        val end = AppMessage.FileEnd(transferId, digest)
        assertEquals(end, AppCodec.decode(AppCodec.encode(end)))

        val rejected = AppMessage.FileDone(transferId, false, Protocol.FILE_DONE_REJECTED)
        assertEquals(rejected, AppCodec.decode(AppCodec.encode(rejected)))

        val integrity = AppMessage.FileDone(transferId, false, Protocol.FILE_DONE_INTEGRITY)
        assertEquals(integrity, AppCodec.decode(AppCodec.encode(integrity)))
    }

    @Test
    fun rejectsUnknownFileDoneReason() {
        val transferId = Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES)
        val raw = java.nio.ByteBuffer.allocate(1 + Protocol.TRANSFER_ID_BYTES + 2)
            .put(Protocol.TYPE_FILE_DONE.toByte())
            .put(transferId)
            .put(0.toByte())
            .put((Protocol.FILE_DONE_INTEGRITY + 1).toByte())
            .array()
        assertNull(AppCodec.decode(raw))
    }

    @Test
    fun rejectsInvalidFileData() {
        val transferId = Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES)
        assertNull(AppCodec.decode(rawFileData(transferId, -1, ByteArray(1))))
        assertNull(AppCodec.decode(rawFileData(transferId, 0, ByteArray(0))))
        assertNull(
            AppCodec.decode(
                rawFileData(transferId, 0, ByteArray(Protocol.MAX_FILE_DATA_BYTES + 1))
            )
        )
        try {
            AppCodec.encode(AppMessage.FileData(transferId, 0, ByteArray(0)))
            throw AssertionError("empty block must not encode")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun rejectsInvalidFileOfferFields() {
        assertNull(
            AppCodec.decode(
                rawFileOffer(
                    totalBytes = Protocol.MAX_FILE_BYTES + 1,
                    name = "x.bin"
                )
            )
        )
        assertNull(AppCodec.decode(rawFileOffer(totalBytes = 10, name = "")))
        try {
            AppCodec.encode(
                AppMessage.FileOffer(
                    Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES),
                    Crypto.randomBytes(Protocol.MESSAGE_ID_BYTES),
                    1,
                    1_700_000_000_000,
                    10,
                    "x".repeat(Protocol.MAX_FILE_NAME_BYTES + 1),
                    "application/octet-stream"
                )
            )
            throw AssertionError("oversized file name must not encode")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun sanitizeFileNameRemovesTraversalAndLimitsBytes() {
        val cleaned = AppCodec.sanitizeFileName("../private\\..\\secret\u0000.txt")
        assertNotNull(cleaned)
        val name = cleaned!!
        assertTrue(name.isNotBlank())
        assertTrue(!name.contains('/') && !name.contains('\\'))
        assertTrue(!name.contains('\u0000'))

        val limited = AppCodec.sanitizeFileName("é".repeat(300))
        assertNotNull(limited)
        assertTrue(limited!!.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_FILE_NAME_BYTES)
    }

    private fun rawFileData(transferId: ByteArray, offset: Long, data: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(1 + Protocol.TRANSFER_ID_BYTES + 8 + 4 + data.size)
            .put(Protocol.TYPE_FILE_DATA.toByte())
            .put(transferId)
            .putLong(offset)
            .putInt(data.size)
            .put(data)
            .array()

    private fun rawFileOffer(totalBytes: Long, name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val mimeBytes = "application/octet-stream".toByteArray(Charsets.UTF_8)
        return java.nio.ByteBuffer.allocate(
            1 + Protocol.TRANSFER_ID_BYTES + Protocol.MESSAGE_ID_BYTES + 8 + 8 + 8 + 1 +
                nameBytes.size + 1 + mimeBytes.size
        )
            .put(Protocol.TYPE_FILE_OFFER.toByte())
            .put(Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES))
            .put(Crypto.randomBytes(Protocol.MESSAGE_ID_BYTES))
            .putLong(1)
            .putLong(1_700_000_000_000)
            .putLong(totalBytes)
            .put(nameBytes.size.toByte())
            .put(nameBytes)
            .put(mimeBytes.size.toByte())
            .put(mimeBytes)
            .array()
    }
}
