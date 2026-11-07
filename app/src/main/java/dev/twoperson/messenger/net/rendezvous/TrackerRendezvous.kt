package dev.twoperson.messenger.net.rendezvous

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * UDP-tracker rendezvous (BEP 15).
 *
 * The two devices announce the 20-byte topic derived from the invitation under a random
 * sentinel infohash and read each other's public UDP endpoint back. This is a rendezvous
 * fallback for networks where the DHT bootstrap ports (mostly 6881) are blocked by the
 * carrier, while HTTP/HTTPS ports are not.
 *
 * Only the topic hash, an opaque peer id and the public endpoint are ever sent. The
 * invitation secret, the identity key and all chat traffic stay off the tracker: once the
 * endpoint is known the two devices connect directly and end-to-end encrypted.
 */
class TrackerRendezvous(
    private val socket: DatagramSocket,
    trackers: List<InetSocketAddress> = defaultTrackers(),
    private val log: (String) -> Unit = {}
) {

    private class Pending(
        val address: InetSocketAddress,
        val action: Int,
        val result: CompletableDeferred<ByteArray>
    )

    private class TrackerState(
        var connectionId: ByteArray? = null,
        var connectionAt: Long = 0L,
        var hasAnnounced: Boolean = false,
        var failures: Int = 0,
        var nextAllowedAt: Long = 0L
    )

    private class AnnounceResult(val peers: List<InetSocketAddress>, val intervalSeconds: Long)

    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val states = ConcurrentHashMap<InetSocketAddress, TrackerState>()
    private val txCounter = AtomicInteger(random.nextInt())
    private val peerId: ByteArray = ByteArray(PEER_ID_BYTES).also { random.nextBytes(it) }
    private val announceKey: Int = random.nextInt()
    private val trackerList: List<InetSocketAddress> = trackers

    /** Routes a datagram from the shared socket. Returns true when it was a tracker reply. */
    fun onPacket(data: ByteArray, from: InetSocketAddress): Boolean {
        if (data.size < RESPONSE_HEADER_BYTES) return false
        val buffer = ByteBuffer.wrap(data)
        val action = buffer.int
        val transactionId = buffer.int
        if (action != ACTION_CONNECT && action != ACTION_ANNOUNCE && action != ACTION_ERROR) {
            return false
        }
        val waiting = pending[transactionId] ?: return false
        if (waiting.address != from) return false
        if (action == ACTION_ERROR) {
            waiting.result.complete(ByteArray(0))
            return true
        }
        if (action != waiting.action) return false
        val payload = when (action) {
            ACTION_CONNECT ->
                if (data.size >= CONNECT_RESPONSE_BYTES) {
                    data.copyOfRange(RESPONSE_HEADER_BYTES, CONNECT_RESPONSE_BYTES)
                } else {
                    ByteArray(0)
                }

            else -> data
        }
        waiting.result.complete(payload)
        return true
    }

    /**
     * Announces [topic] on every tracker that is due and returns the peers they report.
     * [announcePort] must be the port peers should dial: the STUN-mapped public port when
     * known, otherwise the local socket port (same LAN / no NAT).
     */
    suspend fun discover(
        topic: ByteArray,
        announcePort: Int,
        budgetMs: Long = 6_000
    ): List<InetSocketAddress> {
        if (topic.size != TOPIC_BYTES || announcePort <= 0) return emptyList()
        val now = System.currentTimeMillis()
        val due = trackerList.filter { (states[it]?.nextAllowedAt ?: 0L) <= now }
        if (due.isEmpty()) return emptyList()

        val found = ArrayList<InetSocketAddress>()
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                due.map { tracker ->
                    async {
                        val peers = announceTo(tracker, topic, announcePort)
                        synchronized(found) { found.addAll(peers) }
                    }
                }.awaitAll()
            }
        }
        return synchronized(found) { found.distinct() }
    }

    private suspend fun announceTo(
        tracker: InetSocketAddress,
        topic: ByteArray,
        announcePort: Int
    ): List<InetSocketAddress> {
        val state = states.getOrPut(tracker) { TrackerState() }
        return try {
            val connectionId = connectionId(tracker, state) ?: throw IllegalStateException("no connection id")
            val result = announce(tracker, state, connectionId, topic, announcePort)
            state.hasAnnounced = true
            state.failures = 0
            val gap = result.intervalSeconds.coerceIn(
                MIN_ANNOUNCE_GAP_MS / 1000,
                MAX_ANNOUNCE_GAP_MS / 1000
            )
            state.nextAllowedAt = System.currentTimeMillis() + gap * 1000
            log("tracker: ${tracker.hostString} answered with ${result.peers.size} peer(s)")
            result.peers
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.connectionId = null
            state.failures++
            val backoff = minOf(MAX_RETRY_MS, BASE_RETRY_MS shl minOf(state.failures, MAX_BACKOFF_SHIFT))
            state.nextAllowedAt = System.currentTimeMillis() + backoff
            log("tracker: ${tracker.hostString} failed (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    private suspend fun connectionId(tracker: InetSocketAddress, state: TrackerState): ByteArray? {
        val cached = state.connectionId
        if (cached != null && System.currentTimeMillis() - state.connectionAt < CONNECTION_TTL_MS) {
            return cached
        }
        val transactionId = nextTransactionId()
        val request = ByteBuffer.allocate(CONNECT_REQUEST_BYTES)
            .putLong(PROTOCOL_ID)
            .putInt(ACTION_CONNECT)
            .putInt(transactionId)
            .array()
        val response = exchange(tracker, transactionId, ACTION_CONNECT, request, CONNECT_TIMEOUT_MS)
        if (response == null || response.size != CONNECTION_ID_BYTES) return null
        state.connectionId = response
        state.connectionAt = System.currentTimeMillis()
        return response
    }

    private suspend fun announce(
        tracker: InetSocketAddress,
        state: TrackerState,
        connectionId: ByteArray,
        topic: ByteArray,
        announcePort: Int
    ): AnnounceResult {
        val transactionId = nextTransactionId()
        val event = if (state.hasAnnounced) EVENT_NONE else EVENT_STARTED
        val request = ByteBuffer.allocate(ANNOUNCE_REQUEST_BYTES)
            .put(connectionId)
            .putInt(ACTION_ANNOUNCE)
            .putInt(transactionId)
            .put(topic)
            .put(peerId)
            .putLong(0)
            .putLong(0)
            .putLong(0)
            .putInt(event)
            .putInt(0)
            .putInt(announceKey)
            .putInt(-1)
            .putShort(announcePort.toShort())
            .array()
        val response = exchange(tracker, transactionId, ACTION_ANNOUNCE, request, ANNOUNCE_TIMEOUT_MS)
            ?: throw IllegalStateException("announce timed out")
        if (response.size < ANNOUNCE_RESPONSE_HEADER_BYTES) {
            throw IllegalStateException("short announce response")
        }
        val buffer = ByteBuffer.wrap(response)
        buffer.int
        buffer.int
        val interval = buffer.int.toLong() and 0xFFFFFFFFL
        buffer.int
        buffer.int
        val peers = ArrayList<InetSocketAddress>()
        while (buffer.remaining() >= COMPACT_PEER_BYTES) {
            val address = ByteArray(4).also { buffer.get(it) }
            val port = buffer.short.toInt() and 0xFFFF
            if (port <= 0) continue
            try {
                peers.add(InetSocketAddress(InetAddress.getByAddress(address), port))
            } catch (e: Exception) {
                // A malformed peer entry must not discard the rest of the response.
            }
        }
        return AnnounceResult(peers, interval)
    }

    private suspend fun exchange(
        tracker: InetSocketAddress,
        transactionId: Int,
        action: Int,
        request: ByteArray,
        timeoutMs: Long
    ): ByteArray? {
        val waiting = Pending(tracker, action, CompletableDeferred())
        pending[transactionId] = waiting
        return try {
            socket.send(DatagramPacket(request, request.size, tracker))
            withTimeoutOrNull(timeoutMs) { waiting.result.await() }?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            pending.remove(transactionId)
        }
    }

    private fun nextTransactionId(): Int {
        while (true) {
            val candidate = txCounter.incrementAndGet()
            if (!pending.containsKey(candidate)) return candidate
        }
    }

    companion object {
        private const val PROTOCOL_ID = 0x41727101980L
        private const val ACTION_CONNECT = 0
        private const val ACTION_ANNOUNCE = 1
        private const val ACTION_ERROR = 3
        private const val EVENT_NONE = 0
        private const val EVENT_STARTED = 2
        private const val CONNECT_REQUEST_BYTES = 16
        private const val CONNECT_RESPONSE_BYTES = 16
        private const val CONNECTION_ID_BYTES = 8
        private const val ANNOUNCE_REQUEST_BYTES = 98
        private const val ANNOUNCE_RESPONSE_HEADER_BYTES = 20
        private const val RESPONSE_HEADER_BYTES = 8
        private const val COMPACT_PEER_BYTES = 6
        private const val TOPIC_BYTES = 20
        private const val PEER_ID_BYTES = 20
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val ANNOUNCE_TIMEOUT_MS = 2_500L
        private const val CONNECTION_TTL_MS = 50_000L
        private const val BASE_RETRY_MS = 15_000L
        private const val MAX_RETRY_MS = 120_000L
        private const val MAX_BACKOFF_SHIFT = 3
        private const val MIN_ANNOUNCE_GAP_MS = 10_000L
        private const val MAX_ANNOUNCE_GAP_MS = 300_000L

        /**
         * Public UDP trackers reachable on ports that carriers do not block. They only
         * exchange the topic hash and public endpoints; they never see message content.
         */
        fun defaultTrackers(): List<InetSocketAddress> = listOf(
            "open.stealth.si" to 80,
            "tracker.torrents.observer" to 80,
            "open.ftorrent.com" to 443
        ).mapNotNull { (host, port) ->
            try {
                InetAddress.getAllByName(host)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
                    ?.let { InetSocketAddress(it, port) }
            } catch (e: Exception) {
                null
            }
        }
    }
}
