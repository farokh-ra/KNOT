package dev.twoperson.messenger.net

import dev.twoperson.messenger.crypto.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureChannelTest {

    private class Pair2(val sender: SecureChannel, val receiver: SecureChannel)

    private fun mirroredPair(): Pair2 {
        val aToB = Crypto.randomBytes(32)
        val bToA = Crypto.randomBytes(32)
        return Pair2(SecureChannel(aToB, bToA), SecureChannel(bToA, aToB))
    }

    @Test
    fun framesRoundTripInOrder() {
        val pair = mirroredPair()
        val sender = pair.sender
        val receiver = pair.receiver
        val payload = "hello".toByteArray()
        val result = receiver.open(sender.seal(Wire.KIND_DATA, payload))
        assertTrue(result is OpenResult.Ok)
        assertArrayEquals(payload, (result as OpenResult.Ok).frame.payload)
        assertEquals(Wire.KIND_DATA, result.frame.kind)
        assertEquals(0L, result.frame.counter)
    }

    @Test
    fun replayedFrameIsReportedAsDuplicate() {
        val pair = mirroredPair()
        val packet = pair.sender.seal(Wire.KIND_DATA, "x".toByteArray())
        assertTrue(pair.receiver.open(packet) is OpenResult.Ok)
        assertTrue(pair.receiver.open(packet) is OpenResult.Duplicate)
    }

    @Test
    fun tamperedFrameIsRejected() {
        val pair = mirroredPair()
        val packet = pair.sender.seal(Wire.KIND_DATA, "payload".toByteArray())
        val tampered = packet.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertTrue(pair.receiver.open(tampered) is OpenResult.Invalid)
    }

    @Test
    fun wrongKeyIsRejected() {
        val sender = SecureChannel(Crypto.randomBytes(32), Crypto.randomBytes(32))
        val receiver = SecureChannel(Crypto.randomBytes(32), Crypto.randomBytes(32))
        val packet = sender.seal(Wire.KIND_DATA, "payload".toByteArray())
        assertTrue(receiver.open(packet) is OpenResult.Invalid)
    }

    @Test
    fun truncatedFrameIsRejected() {
        val pair = mirroredPair()
        val packet = pair.sender.seal(Wire.KIND_ACK, ByteArray(12))
        for (cut in 1..8) {
            assertTrue(pair.receiver.open(packet.copyOfRange(0, packet.size - cut)) is OpenResult.Invalid)
        }
    }
}
