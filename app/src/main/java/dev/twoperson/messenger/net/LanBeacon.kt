package dev.twoperson.messenger.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

/**
 * Optional same-subnet discovery on a fixed beacon port. Improves the very common
 * "both phones on the same Wi-Fi" case, where carrier-style hole punching through the
 * router's public mapping often fails (no NAT hairpinning).
 *
 * The beacon carries only the topic hash derived from the invitation and the UDP port of
 * the primary socket; no message content and no secret.
 */
class LanBeacon(
    private val topic: ByteArray,
    private val primaryPortProvider: () -> Int,
    private val onPeer: (InetAddress, Int) -> Unit,
    private val log: (String) -> Unit = {}
) {

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    private var receiver: Thread? = null
    private var announcer: Thread? = null

    fun start() {
        if (running) return
        try {
            val created = DatagramSocket(null)
            created.reuseAddress = true
            created.broadcast = true
            created.bind(InetSocketAddress(BEACON_PORT))
            created.soTimeout = 1000
            socket = created
            running = true
            receiver = Thread({ receiveLoop(created) }, "lan-beacon-rx").apply {
                isDaemon = true
                start()
            }
            announcer = Thread({ announceLoop(created) }, "lan-beacon-tx").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            log("lan: beacon port unavailable (${e.javaClass.simpleName})")
            stop()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (ignored: Exception) {
        }
        socket = null
        receiver?.interrupt()
        announcer?.interrupt()
        receiver = null
        announcer = null
    }

    private fun receiveLoop(active: DatagramSocket) {
        val buffer = ByteArray(64)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                active.receive(packet)
                val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                if (!matches(data)) continue
                val remotePort = ByteBuffer.wrap(data, MAGIC.size + 1 + 20, 2).short.toInt() and 0xFFFF
                val address = (packet.socketAddress as? InetSocketAddress)?.address ?: continue
                if (address.isLoopbackAddress) continue
                if (remotePort <= 0 || remotePort > 65535) continue
                if (remotePort == primaryPortProvider()) continue
                onPeer(address, remotePort)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    private fun announceLoop(active: DatagramSocket) {
        while (running) {
            try {
                val port = primaryPortProvider()
                if (port > 0) {
                    val packet = packetFor(port)
                    for (target in broadcastAddresses()) {
                        try {
                            active.send(DatagramPacket(packet, packet.size, target, BEACON_PORT))
                        } catch (ignored: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) return
            }
            try {
                Thread.sleep(ANNOUNCE_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun packetFor(port: Int): ByteArray =
        ByteArray(MAGIC.size + 1 + 20 + 2).also { out ->
            MAGIC.copyInto(out, 0)
            out[MAGIC.size] = VERSION
            topic.copyInto(out, MAGIC.size + 1, 0, minOf(20, topic.size))
            out[out.size - 2] = (port ushr 8).toByte()
            out[out.size - 1] = port.toByte()
        }

    private fun matches(data: ByteArray): Boolean {
        if (data.size != MAGIC.size + 1 + 20 + 2) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        if (data[MAGIC.size] != VERSION) return false
        for (i in 0 until 20) {
            if (data[MAGIC.size + 1 + i] != topic[i]) return false
        }
        return true
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val targets = ArrayList<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (ignored: Exception) {
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets
            for (networkInterface in interfaces.toList()) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.interfaceAddresses) {
                    address.broadcast?.let { targets.add(it) }
                }
            }
        } catch (ignored: Exception) {
        }
        return targets.distinct()
    }

    companion object {
        const val BEACON_PORT = 41999
        private const val ANNOUNCE_INTERVAL_MS = 1_000L
        private const val VERSION = 1.toByte()
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'L'.code.toByte())
    }
}
