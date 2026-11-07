package dev.twoperson.messenger.net.rendezvous

import dev.twoperson.messenger.crypto.Crypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the real BEP 15 wire format against a local fake tracker: connect, announce with
 * the advertised port, compact peer parsing and the per-tracker announce throttle.
 */
class TrackerRendezvousTest {

    @Test
    fun announceReturnsCompactPeerAndAdvertisesStartedOnce() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val trackerSocket = DatagramSocket(0, loopback)
        val clientSocket = DatagramSocket(0, loopback)
        val trackerAddress = InetSocketAddress(loopback, trackerSocket.localPort)
        val peerAddress = InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 9))
        val peerPort = 5555
        val announcedPort = AtomicInteger(0)
        val firstEvent = AtomicInteger(-1)
        val announceCount = AtomicInteger(0)
        val receivedTopic = AtomicReference<ByteArray>()

        val trackerThread = Thread {
            try {
                val buffer = ByteArray(512)
                while (!trackerSocket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    trackerSocket.receive(packet)
                    val data = packet.data.copyOfRange(
                        packet.offset,
                        packet.offset + packet.length
                    )
                    if (data.size < 16) continue
                    // BEP 15: the connect request starts with the 8-byte protocol id, so
                    // action/transaction live at 8 and 12 for every request too.
                    val action = ByteBuffer.wrap(data, 8, 4).int
                    val transaction = ByteBuffer.wrap(data, 12, 4).int
                    when (action) {
                        0 -> {
                            val response = ByteBuffer.allocate(16)
                                .putInt(0)
                                .putInt(transaction)
                                .put(CONNECTION_ID)
                                .array()
                            trackerSocket.send(
                                DatagramPacket(response, response.size, packet.socketAddress)
                            )
                        }

                        1 -> {
                            if (data.size < 98) continue
                            if (!data.copyOfRange(0, 8).contentEquals(CONNECTION_ID)) continue
                            val topic = data.copyOfRange(16, 36)
                            receivedTopic.set(topic)
                            val event = ByteBuffer.wrap(data, 80, 4).int
                            val port = ((data[96].toInt() and 0xFF) shl 8) or
                                (data[97].toInt() and 0xFF)
                            if (announceCount.getAndIncrement() == 0) firstEvent.set(event)
                            announcedPort.set(port)
                            val compactPeer = ByteBuffer.allocate(6)
                                .put(peerAddress.address)
                                .putShort(peerPort.toShort())
                                .array()
                            val response = ByteBuffer.allocate(26)
                                .putInt(1)
                                .putInt(transaction)
                                .putInt(60)
                                .putInt(0)
                                .putInt(1)
                                .put(compactPeer)
                                .array()
                            trackerSocket.send(
                                DatagramPacket(response, response.size, packet.socketAddress)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        val rendezvous = TrackerRendezvous(
            clientSocket,
            trackers = listOf(trackerAddress)
        )
        val pump = Thread {
            val buffer = ByteArray(1500)
            while (!clientSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    clientSocket.receive(packet)
                    rendezvous.onPacket(
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                        packet.socketAddress as InetSocketAddress
                    )
                } catch (e: Exception) {
                    if (clientSocket.isClosed) return@Thread
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val topic = Crypto.randomBytes(20)
            val peers = rendezvous.discover(topic, announcePort = 43210, budgetMs = 4_000)

            assertEquals(1, peers.size)
            assertEquals(peerPort, peers[0].port)
            assertEquals(peerAddress, peers[0].address)
            assertEquals(43210, announcedPort.get())
            assertEquals(2, firstEvent.get())
            assertArrayEquals(topic, receivedTopic.get())

            // The tracker asked for a 60 s interval; an immediate second lookup must not
            // announce again.
            val throttled = rendezvous.discover(topic, announcePort = 43210, budgetMs = 1_000)
            assertTrue(throttled.isEmpty())
            assertEquals(1, announceCount.get())
        } finally {
            clientSocket.close()
            trackerSocket.close()
            trackerThread.interrupt()
            pump.interrupt()
        }
    }

    companion object {
        private val CONNECTION_ID = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    }
}
