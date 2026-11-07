package dev.twoperson.messenger.net

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * A single UDP socket shared by DHT rendezvous and peer traffic.
 *
 * Sharing one socket is not an optimisation: the NAT mapping that the other device learns
 * from the DHT is the mapping of this socket's source port, so chat traffic must leave
 * from the same port for hole punching to work.
 */
class UdpTransport(
    private val onDatagram: (ByteArray, InetSocketAddress) -> Unit
) : Closeable {

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    private var reader: Thread? = null

    var localPort: Int = 0
        private set

    /** Binds the socket. Returns false when the platform refuses (no network, no permission). */
    fun start(): Boolean {
        if (running) return true
        return try {
            val created = DatagramSocket(null)
            created.reuseAddress = true
            created.bind(InetSocketAddress(0))
            created.soTimeout = 1000
            try {
                // Large socket buffers matter for bulk transfer: without them a fast Wi-Fi
                // link drops datagrams while the app is writing the next block to disk.
                created.sendBufferSize = 4 * 1024 * 1024
                created.receiveBufferSize = 4 * 1024 * 1024
            } catch (ignored: SocketException) {
                // Buffer sizes are a hint; some devices clamp them.
            }
            socket = created
            localPort = created.localPort
            running = true
            reader = Thread({ readLoop(created) }, "udp-transport").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    fun socketOrNull(): DatagramSocket? = socket

    fun send(data: ByteArray, address: InetSocketAddress): Boolean {
        val target = socket ?: return false
        return try {
            target.send(DatagramPacket(data, data.size, address))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readLoop(active: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                active.receive(packet)
                if (packet.length <= 0) continue
                val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val from = packet.socketAddress as? InetSocketAddress ?: continue
                onDatagram(payload, from)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) {
                    // A single malformed packet or transient error must not kill the loop.
                    continue
                }
            }
        }
    }

    override fun close() {
        running = false
        try {
            socket?.close()
        } catch (ignored: Exception) {
        }
        socket = null
        reader?.interrupt()
        reader = null
        localPort = 0
    }
}
