package dev.twoperson.messenger.net

import dev.twoperson.messenger.crypto.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureChannelTest {

    private fun keys(): Pair<SecureChannel, SecureChannel> {
        val key = Crypto.randomBytes(32)
        return SecureChannel(key, key) to SecureChannel(key, key)
    }

    @Test
    fun acceptsLargeOutOfOrderBurstWithinReplayWindow() {
        val (sender, receiver) = keys()
        val packets = (0 until 300).map {
            sender.seal(Wire.KIND_DATA, byteArrayOf(it.toByte()))
        }

        // A wider reliable send window means hundreds of frames can arrive out of order. The
        // replay window must not reject a legitimate frame as "too old".
        for (index in packets.indices.reversed()) {
            val result = receiver.open(packets[index])
            assertTrue("frame $index should be accepted", result is OpenResult.Ok)
            assertEquals(index.toLong(), (result as OpenResult.Ok).frame.counter)
        }
    }

    @Test
    fun duplicateFrameIsReportedWithoutRedelivery() {
        val (sender, receiver) = keys()
        val packet = sender.seal(Wire.KIND_DATA, byteArrayOf(1, 2, 3))
        assertTrue(receiver.open(packet) is OpenResult.Ok)
        assertTrue(receiver.open(packet) is OpenResult.Duplicate)
    }

    @Test
    fun frameOlderThanWindowIsRejected() {
        val (sender, receiver) = keys()
        val first = sender.seal(Wire.KIND_DATA, byteArrayOf(0))
        var last = first
        // One more than the replay window: opening only the newest frame must make the first
        // one fall out of the accepted range.
        repeat(4096) { index ->
            last = sender.seal(Wire.KIND_DATA, byteArrayOf((index % 251).toByte()))
        }
        assertTrue(receiver.open(last) is OpenResult.Ok)
        assertTrue(receiver.open(first) is OpenResult.Invalid)
    }

    @Test
    fun tamperedFrameIsRejected() {
        val (sender, receiver) = keys()
        val packet = sender.seal(Wire.KIND_DATA, byteArrayOf(9, 8, 7))
        val tampered = packet.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertTrue(receiver.open(tampered) is OpenResult.Invalid)
    }

    @Test
    fun frameSealedWithAnotherKeyIsRejected() {
        val (sender, _) = keys()
        val (_, otherReceiver) = keys()
        val packet = sender.seal(Wire.KIND_DATA, byteArrayOf(4, 5, 6))
        assertTrue(otherReceiver.open(packet) is OpenResult.Invalid)
    }
}
