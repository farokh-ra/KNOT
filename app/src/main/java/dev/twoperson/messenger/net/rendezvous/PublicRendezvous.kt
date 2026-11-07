package dev.twoperson.messenger.net.rendezvous

import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Serverless rendezvous over public infrastructure: STUN to learn the NAT mapping of the
 * shared peer socket, UDP trackers to exchange public endpoints for the invitation topic.
 *
 * Neither service is operated by this app and neither ever sees the invitation secret or any
 * message content; they only help the two devices find a direct path.
 */
class PublicRendezvous(
    socket: DatagramSocket,
    log: (String) -> Unit = {}
) {

    private val stun = StunClient(socket, log = log)
    private val trackers = TrackerRendezvous(socket, log = log)

    /** Routes a datagram from the shared socket. Returns true when it belonged to rendezvous. */
    fun onPacket(data: ByteArray, from: InetSocketAddress): Boolean {
        if (stun.onPacket(data, from)) return true
        return trackers.onPacket(data, from)
    }

    fun cachedPublicEndpoint(): InetSocketAddress? = stun.cachedEndpoint()

    /**
     * Announces [topic] and returns the peer endpoints. The advertised port is the
     * STUN-learned public port when available so peers behind NAT dial the translated port.
     */
    suspend fun discover(
        topic: ByteArray,
        localPort: Int,
        budgetMs: Long = 6_000
    ): List<InetSocketAddress> {
        val endpoint = stun.publicEndpoint()
        val announcePort = endpoint?.port ?: localPort
        if (announcePort <= 0) return emptyList()
        return trackers.discover(topic, announcePort, budgetMs)
    }
}
