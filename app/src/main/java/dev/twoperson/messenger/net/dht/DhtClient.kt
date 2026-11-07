package dev.twoperson.messenger.net.dht

import dev.twoperson.messenger.crypto.Crypto
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal BitTorrent mainline DHT client (BEP 5) used purely for rendezvous: peers announce
 * their UDP endpoint under a topic derived from the invitation secret and look each other up.
 *
 * No chat bytes ever pass through the DHT. The DHT only learns "this IP:port is interested
 * in topic X", which is unavoidable for serverless discovery.
 */
class DhtClient(
    private val socket: DatagramSocket,
    private val nodeId: ByteArray,
    private val log: (String) -> Unit = {}
) {

    private class Node(val id: ByteArray, val address: InetSocketAddress)

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<BValue.BDict>>()
    private val txCounter = AtomicInteger(1)
    private val routing = ArrayList<Node>()
    private val routingLock = Any()
    private val bootstrapped = AtomicBoolean(false)

    @Volatile
    private var lastBootstrapAttemptAt = 0L

    fun onPacket(data: ByteArray, from: InetSocketAddress): Boolean {
        val message = Bencode.decode(data)?.asDict() ?: return false
        val type = message.entries["y"]?.asString() ?: return false
        val tx = (message.entries["t"] as? BValue.BStr)?.bytes ?: return false
        if (tx.size != 2) return true
        val key = ((tx[0].toInt() and 0xFF) shl 8) or (tx[1].toInt() and 0xFF)
        val deferred = pending.remove(key) ?: return true
        when (type) {
            "r" -> {
                val response = message.entries["r"]?.asDict() ?: BValue.BDict(emptyMap())
                val responderId = (response.entries["id"] as? BValue.BStr)?.bytes
                if (responderId != null) addNode(Node(responderId, from))
                (response.entries["nodes"] as? BValue.BStr)?.let { parseCompactNodes(it.bytes) }
                deferred.complete(response)
            }

            "e" -> deferred.complete(BValue.BDict(emptyMap()))
            else -> Unit
        }
        return true
    }

    /** Seeds the routing table from the public bootstrap nodes. */
    suspend fun bootstrap(timeoutMs: Long = 4_000): Boolean {
        if (bootstrapped.get()) return true
        if (nodesSnapshot().size >= 8) {
            bootstrapped.set(true)
            return true
        }
        // Networks that block the DHT ports answer nothing; retrying every discovery cycle
        // would stall startup. Back off between attempts instead.
        val now = System.currentTimeMillis()
        if (now - lastBootstrapAttemptAt < BOOTSTRAP_RETRY_MS) return false
        lastBootstrapAttemptAt = now
        val seeds = resolveSeeds()
        if (seeds.isEmpty()) {
            log("dht: no bootstrap node resolvable")
            return false
        }
        val target = Crypto.randomBytes(20)
        val responses = coroutineScope {
            seeds.map { seed ->
                async {
                    query(
                        seed,
                        "find_node",
                        mapOf("id" to BValue.BStr(nodeId), "target" to BValue.BStr(target)),
                        timeoutMs
                    )
                }
            }.awaitAll()
        }
        val reachable = responses.count { it != null }
        if (reachable > 0) {
            bootstrapped.set(true)
            log("dht: bootstrap answered by $reachable node(s), ${nodesSnapshot().size} known")
        } else {
            log("dht: bootstrap unreachable")
        }
        return reachable > 0
    }

    /**
     * Looks up peers for [topic] and announces [listenPort] so the other device can find us.
     * Returns any peer endpoints found.
     */
    suspend fun discover(topic: ByteArray, listenPort: Int, budgetMs: Long = 8_000): List<InetSocketAddress> {
        if (topic.size != 20) return emptyList()
        if (nodesSnapshot().isEmpty()) bootstrap()
        val peers = LinkedHashSet<InetSocketAddress>()
        val tokens = HashMap<InetSocketAddress, ByteArray>()
        val queried = HashSet<InetSocketAddress>()
        val started = System.currentTimeMillis()

        for (round in 0 until MAX_ROUNDS) {
            if (System.currentTimeMillis() - started > budgetMs) break
            val batch = nodesSnapshot()
                .filter { !queried.contains(it.address) }
                .sortedWith { a, b -> distance(a.id, b.id, topic) }
                .take(BATCH)
            if (batch.isEmpty()) break
            batch.forEach { queried.add(it.address) }

            val responses = coroutineScope {
                batch.map { node ->
                    async {
                        node.address to query(
                            node.address,
                            "get_peers",
                            mapOf("id" to BValue.BStr(nodeId), "info_hash" to BValue.BStr(topic)),
                            2_500
                        )
                    }
                }.awaitAll()
            }

            for ((address, response) in responses) {
                if (response == null) continue
                response.entries["values"]?.asList()?.items?.forEach { value ->
                    val compact = (value as? BValue.BStr)?.bytes ?: return@forEach
                    if (compact.size == 6) {
                        parseCompactAddress(compact, 0)?.let { peers.add(it) }
                    }
                }
                (response.entries["token"] as? BValue.BStr)?.bytes?.let { tokens[address] = it }
                (response.entries["nodes"] as? BValue.BStr)?.let { parseCompactNodes(it.bytes) }
            }
        }

        announce(topic, listenPort, tokens)
        return peers.toList()
    }

    private suspend fun announce(
        topic: ByteArray,
        listenPort: Int,
        tokens: Map<InetSocketAddress, ByteArray>
    ) {
        if (tokens.isEmpty() || listenPort <= 0) return
        val targets = tokens.entries.take(8)
        coroutineScope {
            targets.map { (address, token) ->
                async {
                    query(
                        address,
                        "announce_peer",
                        mapOf(
                            "id" to BValue.BStr(nodeId),
                            "info_hash" to BValue.BStr(topic),
                            "port" to BValue.BInt(listenPort.toLong()),
                            "implied_port" to BValue.BInt(1L),
                            "token" to BValue.BStr(token)
                        ),
                        2_500
                    )
                }
            }.awaitAll()
        }
        log("dht: announced on topic via ${targets.size} node(s)")
    }

    private fun nodesSnapshot(): List<Node> = synchronized(routingLock) { ArrayList(routing) }

    private fun addNode(node: Node) {
        if (node.id.size != 20 || node.address.port == 0) return
        if (node.address.isUnresolved) return
        if (node.address.address !is Inet4Address) return
        synchronized(routingLock) {
            if (routing.any { it.address == node.address }) return
            routing.add(node)
            if (routing.size > MAX_ROUTING) routing.removeAt(0)
        }
    }

    private fun parseCompactNodes(bytes: ByteArray) {
        if (bytes.size % 26 != 0) return
        var offset = 0
        while (offset + 26 <= bytes.size) {
            val id = bytes.copyOfRange(offset, offset + 20)
            parseCompactAddress(bytes, offset + 20)?.let { addNode(Node(id, it)) }
            offset += 26
        }
    }

    private fun parseCompactAddress(bytes: ByteArray, offset: Int): InetSocketAddress? {
        if (offset + 6 > bytes.size) return null
        val ip = byteArrayOf(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3])
        val port = ((bytes[offset + 4].toInt() and 0xFF) shl 8) or (bytes[offset + 5].toInt() and 0xFF)
        if (port == 0) return null
        return try {
            InetSocketAddress(InetAddress.getByAddress(ip), port)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun query(
        address: InetSocketAddress,
        method: String,
        arguments: Map<String, BValue>,
        timeoutMs: Long
    ): BValue.BDict? {
        if (address.port == 0) return null
        val id = txCounter.incrementAndGet() and 0xFFFF
        val transaction = byteArrayOf((id ushr 8).toByte(), id.toByte())
        val deferred = CompletableDeferred<BValue.BDict>()
        pending[id] = deferred
        val message = BValue.BDict(
            mapOf(
                "t" to BValue.BStr(transaction),
                "y" to Bencode.str("q"),
                "q" to Bencode.str(method),
                "a" to BValue.BDict(arguments)
            )
        )
        val encoded = Bencode.encode(message)
        try {
            socket.send(DatagramPacket(encoded, encoded.size, address))
        } catch (e: Exception) {
            pending.remove(id)
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            null
        } finally {
            pending.remove(id)
        }
    }

    private fun resolveSeeds(): List<InetSocketAddress> {
        val addresses = ArrayList<InetSocketAddress>()
        for ((host, port) in SEEDS) {
            try {
                InetAddress.getAllByName(host)
                    .filterIsInstance<Inet4Address>()
                    .take(2)
                    .forEach { addresses.add(InetSocketAddress(it, port)) }
            } catch (e: Exception) {
                log("dht: cannot resolve $host")
            }
        }
        return addresses
    }

    private fun distance(a: ByteArray, b: ByteArray, target: ByteArray): Int {
        val size = minOf(a.size, b.size, target.size)
        for (i in 0 until size) {
            val da = (a[i].toInt() xor target[i].toInt()) and 0xFF
            val db = (b[i].toInt() xor target[i].toInt()) and 0xFF
            if (da != db) return da - db
        }
        return 0
    }

    companion object {
        private const val BATCH = 8
        private const val MAX_ROUNDS = 4
        private const val MAX_ROUTING = 128

        /** A failed bootstrap is retried at this interval, not on every discovery cycle. */
        private const val BOOTSTRAP_RETRY_MS = 30_000L

        /** Public DHT bootstrap nodes. Discovery only; they never carry chat content. */
        private val SEEDS = listOf(
            "router.bittorrent.com" to 6881,
            "dht.transmissionbt.com" to 6881,
            "router.utorrent.com" to 6881,
            "dht.libtorrent.org" to 25401,
            "dht.aelitis.com" to 6881,
            "dht.ftorrent.com" to 6881,
            "router.sectorrent.com" to 6881
        )
    }
}
