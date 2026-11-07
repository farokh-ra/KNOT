package dev.twoperson.messenger.net.rendezvous

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal STUN binding client (RFC 5389) bound to the peer UDP socket.
 *
 * Its only job is to learn the public IP/port of the shared socket, so a tracker announce can
 * publish the NAT's translated port instead of the local one. No STUN server ever sees any
 * chat content; the request is a fixed 20-byte binding request.
 */
class StunClient(
    private val socket: DatagramSocket,
    private val servers: List<Pair<String, Int>> = DEFAULT_SERVERS,
    private val log: (String) -> Unit = {}
) {

    private class Wait(
        val address: InetSocketAddress,
        val transaction: ByteArray,
        val result: CompletableDeferred<InetSocketAddress?>
    )

    private val random = SecureRandom()
    private val waits = ConcurrentHashMap<Int, Wait>()

    @Volatile
    private var cached: InetSocketAddress? = null

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var lastFailureAt = 0L

    fun cachedEndpoint(): InetSocketAddress? = cached

    /** Routes a datagram from the shared socket. Returns true when it belonged to STUN. */
    fun onPacket(data: ByteArray, from: InetSocketAddress): Boolean {
        if (data.size < MESSAGE_HEADER_BYTES) return false
        val buffer = ByteBuffer.wrap(data)
        if ((buffer.short.toInt() and 0xFFFF) != BINDING_SUCCESS) return false
        val length = buffer.short.toInt() and 0xFFFF
        if (MESSAGE_HEADER_BYTES + length > data.size) return false
        if (buffer.int != MAGIC_COOKIE) return false
        val transaction = ByteArray(TRANSACTION_BYTES).also { buffer.get(it) }
        val wait = waits[transactionKey(transaction)] ?: return false
        if (wait.address != from) return false
        if (!wait.transaction.contentEquals(transaction)) return false
        val endpoint = parseMappedAddress(data, MESSAGE_HEADER_BYTES, MESSAGE_HEADER_BYTES + length)
        wait.result.complete(endpoint)
        return true
    }

    /**
     * Returns the public endpoint of the shared socket, or null when no STUN server answered.
     * The result is cached; failures are retried with a short backoff.
     */
    suspend fun publicEndpoint(budgetMs: Long = 3_000): InetSocketAddress? {
        val now = System.currentTimeMillis()
        val existing = cached
        if (existing != null && now - cachedAt < CACHE_MS) return existing
        if (now - lastFailureAt < RETRY_AFTER_FAILURE_MS) return null
        val servers = resolveServers()
        if (servers.isEmpty()) {
            lastFailureAt = now
            return null
        }

        val found = coroutineScope {
            val result = CompletableDeferred<InetSocketAddress?>()
            val jobs = servers.take(MAX_SERVERS).map { server ->
                launch {
                    val endpoint = query(server, REQUEST_TIMEOUT_MS)
                    if (endpoint != null && !result.isCompleted) result.complete(endpoint)
                }
            }
            val value = withTimeoutOrNull(budgetMs) { result.await() }
            jobs.forEach { it.cancel() }
            value
        }

        if (found != null) {
            cached = found
            cachedAt = System.currentTimeMillis()
            log("stun: public endpoint learned")
        } else {
            lastFailureAt = System.currentTimeMillis()
        }
        return found
    }

    private suspend fun query(server: InetSocketAddress, timeoutMs: Long): InetSocketAddress? {
        val transaction = ByteArray(TRANSACTION_BYTES).also { random.nextBytes(it) }
        val key = transactionKey(transaction)
        val wait = Wait(server, transaction, CompletableDeferred())
        waits[key] = wait
        val packet = ByteBuffer.allocate(HEADER_BYTES + COOKIE_BYTES + TRANSACTION_BYTES).apply {
            putShort(BINDING_REQUEST.toShort())
            putShort(0)
            putInt(MAGIC_COOKIE)
            put(transaction)
        }.array()
        return try {
            socket.send(DatagramPacket(packet, packet.size, server))
            withTimeoutOrNull(timeoutMs) { wait.result.await() }
        } catch (e: Exception) {
            null
        } finally {
            waits.remove(key)
        }
    }

    private fun parseMappedAddress(
        data: ByteArray,
        start: Int,
        end: Int
    ): InetSocketAddress? {
        var offset = start
        var mapped: InetSocketAddress? = null
        while (offset + 4 <= end) {
            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val length = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            val valueStart = offset + 4
            if (valueStart + length > end) return null
            if (type == ATTR_MAPPED_ADDRESS || type == ATTR_XOR_MAPPED_ADDRESS) {
                if (length >= 8) {
                    val family = data[valueStart + 1].toInt() and 0xFF
                    if (family == FAMILY_IPV4) {
                        val rawPort = ((data[valueStart + 2].toInt() and 0xFF) shl 8) or
                            (data[valueStart + 3].toInt() and 0xFF)
                        val port = if (type == ATTR_XOR_MAPPED_ADDRESS) {
                            rawPort xor (MAGIC_COOKIE ushr 16)
                        } else {
                            rawPort
                        }
                        val address = ByteArray(4) { index ->
                            val value = data[valueStart + 4 + index].toInt() and 0xFF
                            if (type == ATTR_XOR_MAPPED_ADDRESS) {
                                (value xor ((MAGIC_COOKIE ushr (24 - index * 8)) and 0xFF)).toByte()
                            } else {
                                value.toByte()
                            }
                        }
                        if (port > 0) {
                            mapped = try {
                                InetSocketAddress(InetAddress.getByAddress(address), port)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
            }
            offset = valueStart + ((length + 3) and 3.inv())
        }
        return mapped
    }

    private fun resolveServers(): List<InetSocketAddress> {
        val resolved = ArrayList<InetSocketAddress>()
        for ((host, port) in servers) {
            try {
                InetAddress.getAllByName(host)
                    .filterIsInstance<Inet4Address>()
                    .take(2)
                    .forEach { resolved.add(InetSocketAddress(it, port)) }
            } catch (e: Exception) {
                log("stun: cannot resolve $host")
            }
        }
        return resolved
    }

    private fun transactionKey(transaction: ByteArray): Int {
        var value = 0
        for (i in 0 until minOf(4, transaction.size)) {
            value = (value shl 8) or (transaction[i].toInt() and 0xFF)
        }
        return value
    }

    companion object {
        private const val BINDING_REQUEST = 0x0001
        private const val BINDING_SUCCESS = 0x0101
        private const val MAGIC_COOKIE = 0x2112A442
        private const val HEADER_BYTES = 4
        private const val COOKIE_BYTES = 4
        private const val TRANSACTION_BYTES = 12
        private const val MESSAGE_HEADER_BYTES = HEADER_BYTES + COOKIE_BYTES + TRANSACTION_BYTES
        private const val ATTR_MAPPED_ADDRESS = 0x0001
        private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
        private const val FAMILY_IPV4 = 0x01
        private const val CACHE_MS = 5 * 60_000L
        private const val RETRY_AFTER_FAILURE_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 1_500L
        private const val MAX_SERVERS = 3

        /**
         * Public STUN servers. They only reflect this socket's public endpoint; they never
         * carry message content and are not operated by this app.
         */
        private val DEFAULT_SERVERS = listOf(
            "stun.cloudflare.com" to 3478,
            "stun.l.google.com" to 19302,
            "stun.nextcloud.com" to 443,
            "stun.sipgate.net" to 3478
        )
    }
}
