package dev.twoperson.messenger.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import dev.twoperson.messenger.crypto.Crypto
import dev.twoperson.messenger.crypto.Invite
import dev.twoperson.messenger.data.Message
import dev.twoperson.messenger.data.MessageRepository
import dev.twoperson.messenger.data.MessageState
import dev.twoperson.messenger.data.SecureStore
import dev.twoperson.messenger.net.Handshake
import dev.twoperson.messenger.net.LanBeacon
import dev.twoperson.messenger.net.PeerLink
import dev.twoperson.messenger.net.UdpTransport
import dev.twoperson.messenger.net.Wire
import dev.twoperson.messenger.net.dht.DhtClient
import dev.twoperson.messenger.net.rendezvous.PublicRendezvous
import dev.twoperson.messenger.protocol.AppMessage
import dev.twoperson.messenger.protocol.Protocol
import dev.twoperson.messenger.util.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException

enum class ConnectionState { NOT_PAIRED, IDLE, DISCOVERING, CONNECTING, CONNECTED, DISCONNECTED, UNABLE_TO_CONNECT }

enum class DirectKind { LOCAL_NETWORK, INTERNET }

data class ConnectionStatus(
    val state: ConnectionState = ConnectionState.IDLE,
    val direct: DirectKind? = null,
    val detail: String? = null,
    val peerName: String? = null
)

sealed interface EngineEvent {
    data object PairingCompleted : EngineEvent
    data object PeerIdentityMismatch : EngineEvent
    data object PeerOffline : EngineEvent
}

/**
 * Owns discovery, the direct connection and the local outbox.
 *
 * Rules this engine enforces:
 *  - discovery uses public DHT bootstrap nodes and a LAN beacon; the invitation secret never
 *    leaves the device, only its derived topic hash does;
 *  - chat traffic only ever goes to the one authenticated peer endpoint. There is no relay,
 *    TURN or fallback path anywhere in this class;
 *  - a message is stored before it is sent and remains queued until the peer acknowledges it;
 *  - an established link is kept: network changes do not tear the socket down, the peer
 *    endpoint follows the peer's authenticated packets, and reconnects go straight to the
 *    last known endpoint instead of waiting for a fresh lookup;
 *  - losing the connection never deletes or silently drops a message.
 *
 * Lifecycle: the engine runs while something holds it open. `ConnectionService` holds it for
 * as long as the app is paired, which is what keeps the connection alive with the screen off.
 */
class ConnectionEngine(
    context: Context,
    private val secureStore: SecureStore,
    private val repository: MessageRepository
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(ConnectionStatus())
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val restartMutex = Mutex()

    private val transportLock = Any()
    private var transport: UdpTransport? = null

    @Volatile
    private var dht: DhtClient? = null

    /** The socket the cached [dht] client talks through; a new socket needs a new client. */
    private var dhtSocket: DatagramSocket? = null

    @Volatile
    private var rendezvous: PublicRendezvous? = null

    /** Same socket rule as [dhtSocket]. */
    private var rendezvousSocket: DatagramSocket? = null

    /** Needed on the UDP reader thread to validate an incoming initiation without I/O. */
    @Volatile
    private var routingAuthKey: ByteArray? = null

    @Volatile
    private var routingSocket: DatagramSocket? = null

    /**
     * Cached local identity and pinned peer key. The UDP reader thread uses these to reject
     * self-reflections and wrong identities without touching the Keystore or SQLite.
     */
    @Volatile
    private var ownIdentity: ByteArray? = null

    @Volatile
    private var localIdentityPrivateKey: ByteArray? = null

    @Volatile
    private var pinnedPeerHex: String? = null

    private val incomingLock = Any()

    /** A responder link built from a peer initiation, promoted to [activeLink] once ready. */
    @Volatile
    private var incomingLink: PeerLink? = null

    @Volatile
    private var incomingFrom: InetSocketAddress? = null

    @Volatile
    private var lanBeacon: LanBeacon? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var activeLink: PeerLink? = null

    @Volatile
    private var candidateSink: ((InetSocketAddress) -> Unit)? = null

    /** Last endpoint that carried an authenticated frame; used for instant reconnects. */
    @Volatile
    private var lastPeerEndpoint: InetSocketAddress? = null

    private var loopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var foregroundCount = 0
    private var lastRestartAt = 0L

    private val deviceName: String = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .trim()
        .ifEmpty { "Android device" }
        .take(40)

    fun isConfigured(): Boolean = secureStore.inviteSecret() != null

    fun currentInvitationCode(): String? = secureStore.inviteSecret()?.let { Invite.encode(it) }

    fun onForeground() {
        synchronized(this) {
            foregroundCount++
            if (foregroundCount == 1) start()
        }
    }

    fun onBackground() {
        synchronized(this) {
            foregroundCount = maxOf(0, foregroundCount - 1)
            if (foregroundCount == 0) {
                stopResources()
                unregisterNetworkCallback()
                _status.value = ConnectionStatus(
                    if (isConfigured()) ConnectionState.IDLE else ConnectionState.NOT_PAIRED
                )
            }
        }
    }

    /** Creates a fresh invitation. Any previous pairing is dropped. */
    fun createInvitation(): String {
        val secret = Invite.newSecret()
        secureStore.saveInviteSecret(secret)
        scope.launch {
            repository.clearPeer()
            pinnedPeerHex = null
            repository.resetFailedToPending()
            lastPeerEndpoint = null
            restartRuntime()
        }
        return Invite.encode(secret)
    }

    /** Accepts the other device's invitation code. */
    fun joinInvitation(code: String): Boolean {
        val secret = Invite.decode(code) ?: return false
        secureStore.saveInviteSecret(secret)
        scope.launch {
            repository.clearPeer()
            pinnedPeerHex = null
            repository.resetFailedToPending()
            lastPeerEndpoint = null
            restartRuntime()
        }
        return true
    }

    fun forgetPartner() {
        secureStore.saveInviteSecret(null)
        scope.launch {
            repository.clearPeer()
            pinnedPeerHex = null
            lastPeerEndpoint = null
            stopResources()
            _status.value = ConnectionStatus(ConnectionState.NOT_PAIRED)
        }
    }

    /**
     * Stores the message locally first, then tries to send it. Returns false only when the
     * text itself is unacceptable; being offline is not a failure, it stays queued.
     */
    suspend fun sendText(raw: String): Boolean {
        val body = raw.trim()
        if (body.isEmpty()) return false
        if (body.codePointCount(0, body.length) > Protocol.MAX_TEXT_CODE_POINTS) return false
        if (!isConfigured()) return false

        val id = Hex.encode(Crypto.randomBytes(Protocol.MESSAGE_ID_BYTES))
        val sequence = repository.nextSequence()
        val message = repository.insertOutgoing(id, body, System.currentTimeMillis(), sequence)
        repository.refresh()

        val link = activeLink
        if (link != null && link.isReady && !deliver(link, message)) {
            // Send window was full; the outbox flusher retries as acknowledgements arrive.
            scope.launch { flushOutbox(link) }
        }
        return true
    }

    private fun start() {
        if (loopJob?.isActive == true) return
        registerNetworkCallback()
        loopJob = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        var backoff = INITIAL_BACKOFF_MS
        while (isCoroutineActive()) {
            try {
                val secret = secureStore.inviteSecret()
                if (secret == null) {
                    routingAuthKey = null
                    routingSocket = null
                    _status.value = ConnectionStatus(ConnectionState.NOT_PAIRED)
                    return
                }
                val authKey = Invite.authKey(secret)
                val topic = Invite.topic(secret)

                ensureTransport()
                val currentTransport = transport
                val socket = currentTransport?.socketOrNull()
                if (currentTransport == null || socket == null) {
                    _status.value = ConnectionStatus(ConnectionState.UNABLE_TO_CONNECT)
                    delay(backoff)
                    backoff = nextBackoff(backoff)
                    continue
                }

                // Populate the reader-thread caches before routing is enabled.
                localIdentityPrivateKey = secureStore.identityPrivateKey()
                ownIdentity = secureStore.identityPublicKey()
                pinnedPeerHex = repository.peer()?.publicKeyHex
                routingAuthKey = authKey
                routingSocket = socket
                startLanBeacon(topic)
                val dhtClient = dhtClientFor(socket)
                val rendezvousClient = rendezvousFor(socket)
                _status.value = ConnectionStatus(ConnectionState.DISCOVERING)

                val candidates = Channel<InetSocketAddress>(Channel.UNLIMITED)
                candidateSink = { address -> candidates.trySend(address) }
                val discoveryJob = scope.launch {
                    coroutineScope {
                        launch { dhtDiscoveryLoop(topic, currentTransport, dhtClient, candidates) }
                        launch {
                            rendezvousDiscoveryLoop(topic, currentTransport, rendezvousClient, candidates)
                        }
                    }
                }

                var connectedLink: PeerLink? = null
                val tried = HashMap<InetSocketAddress, Long>()

                // Reconnect fast path: the endpoint that last carried authenticated traffic is
                // almost always still correct, and after a peer restart its initiation already
                // told us the new address. Waiting for a fresh lookup instead costs seconds.
                lastPeerEndpoint?.let { hint ->
                    _status.value = ConnectionStatus(ConnectionState.CONNECTING)
                    tried[hint] = System.currentTimeMillis()
                    connectedLink = attemptConnection(hint, authKey, socket, QUICK_HANDSHAKE_MS)
                }

                var phaseStart = System.currentTimeMillis()
                while (isCoroutineActive() && connectedLink == null) {
                    val adopted = activeLink
                    if (adopted != null && adopted.isReady) {
                        connectedLink = adopted
                        break
                    }
                    val candidate = withTimeoutOrNull(CANDIDATE_POLL_MS) { candidates.receive() }
                    if (candidate == null) {
                        if (System.currentTimeMillis() - phaseStart > UNABLE_AFTER_MS &&
                            _status.value.state != ConnectionState.UNABLE_TO_CONNECT
                        ) {
                            _status.value = ConnectionStatus(ConnectionState.UNABLE_TO_CONNECT)
                        }
                        continue
                    }
                    if (!isConfigured()) break
                    if (!isDialable(candidate, socket, rendezvousClient, tried)) continue
                    tried[candidate] = System.currentTimeMillis()
                    _status.value = ConnectionStatus(ConnectionState.CONNECTING)
                    connectedLink = attemptConnection(candidate, authKey, socket, HANDSHAKE_BUDGET_MS)
                    phaseStart = System.currentTimeMillis()
                }

                discoveryJob.cancel()
                candidateSink = null
                candidates.close()

                val connected = connectedLink
                if (connected == null) {
                    _status.value = ConnectionStatus(ConnectionState.UNABLE_TO_CONNECT)
                    delay(backoff)
                    backoff = nextBackoff(backoff)
                    continue
                }

                backoff = INITIAL_BACKOFF_MS
                val endpoint = connected.remoteAddress
                _status.value = ConnectionStatus(
                    ConnectionState.CONNECTED,
                    direct = if (isLocalAddress(endpoint.address)) DirectKind.LOCAL_NETWORK else DirectKind.INTERNET
                )
                repository.resetFailedToPending()
                flushOutbox(connected)
                val reason = connected.awaitClosed()
                if (activeLink === connected) activeLink = null
                // Keep the peer endpoint the link last used: after a NAT rebind or a peer
                // restart this is the address that actually works.
                lastPeerEndpoint = connected.remoteAddress
                // The peer acknowledged the handshake but then stopped acknowledging data for
                // ~45s: report the honest failed state instead of pretending it is in transit.
                // Nothing is deleted, and the next direct connection retries the same IDs.
                if (reason == "delivery_failed") {
                    repository.markUndeliveredAsFailed()
                }
                _status.value = ConnectionStatus(ConnectionState.DISCONNECTED, detail = reason)
                _events.tryEmit(EngineEvent.PeerOffline)
                delay(RECONNECT_PAUSE_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unexpected failure must not kill the engine for the process lifetime.
                _status.value = ConnectionStatus(ConnectionState.UNABLE_TO_CONNECT)
                delay(backoff)
                backoff = nextBackoff(backoff)
            }
        }
    }

    private suspend fun dhtDiscoveryLoop(
        topic: ByteArray,
        currentTransport: UdpTransport,
        client: DhtClient,
        candidates: Channel<InetSocketAddress>
    ) {
        while (isCoroutineActive()) {
            val socket = currentTransport.socketOrNull() ?: return
            try {
                if (client.bootstrap()) {
                    client.discover(topic, socket.localPort, DISCOVERY_BUDGET_MS)
                        .forEach { candidates.trySend(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Discovery errors are transient; keep trying with the next cycle.
            }
            delay(DISCOVERY_INTERVAL_MS)
        }
    }

    private suspend fun rendezvousDiscoveryLoop(
        topic: ByteArray,
        currentTransport: UdpTransport,
        client: PublicRendezvous,
        candidates: Channel<InetSocketAddress>
    ) {
        while (isCoroutineActive()) {
            val socket = currentTransport.socketOrNull() ?: return
            try {
                client.discover(topic, socket.localPort, RENDEZVOUS_BUDGET_MS)
                    .forEach { candidates.trySend(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // STUN and trackers are best-effort; the LAN beacon and DHT keep working.
            }
            delay(RENDEZVOUS_INTERVAL_MS)
        }
    }

    private suspend fun attemptConnection(
        candidate: InetSocketAddress,
        authKey: ByteArray,
        socket: DatagramSocket,
        budgetMs: Long
    ): PeerLink? {
        if (!candidate.address.isReachableAddress() || candidate.port <= 0) return null
        activeLink?.let { existing ->
            if (existing.isReady) return existing
            existing.close("superseded")
        }
        var linkRef: PeerLink? = null

        val listener = object : PeerLink.Listener {
            override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit

            override fun onApplicationMessage(message: AppMessage) {
                handleMessage(linkRef, message)
            }

            override fun onClosed(reason: String) {
                if (activeLink === linkRef) activeLink = null
            }
        }

        val handshake = Handshake(authKey, secureStore.identityPrivateKey(), secureStore.identityPublicKey())
        val link = PeerLink(socket, candidate, handshake, scope, listener)
        linkRef = link
        activeLink = link
        val identity = link.awaitReadyOrSuperseded(budgetMs)
        if (identity == null || activeLink !== link) {
            if (activeLink === link) activeLink = null
            link.close("handshake_failed")
            return null
        }
        if (!finalizeLink(link, identity)) {
            if (activeLink === link) activeLink = null
            return null
        }
        return link
    }

    /**
     * Waits for readiness but aborts as soon as an incoming handshake wins the race, so a
     * simultaneous open does not keep dialing a candidate that is already obsolete.
     */
    private suspend fun PeerLink.awaitReadyOrSuperseded(budgetMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (isCoroutineActive()) {
            val current = activeLink
            if (current != null && current !== this && current.isReady) return null
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val identity = awaitReady(minOf(READY_POLL_MS, remaining))
            if (identity != null) return identity
            if (isClosed) return null
        }
        return null
    }

    /** Pins the peer on first contact, rejects a different identity, then sends Hello. */
    private suspend fun finalizeLink(link: PeerLink, identity: ByteArray): Boolean {
        val identityHex = Hex.encode(identity)
        val pinned = repository.peer()
        if (pinned?.publicKeyHex != null && pinned.publicKeyHex != identityHex) {
            link.close("identity_mismatch")
            _events.tryEmit(EngineEvent.PeerIdentityMismatch)
            return false
        }
        if (pinned?.publicKeyHex == null) {
            repository.savePeer(null, identityHex, null)
            pinnedPeerHex = identityHex
            _events.tryEmit(EngineEvent.PairingCompleted)
        }
        link.sendApplicationMessage(
            AppMessage.Hello(
                Protocol.WIRE_VERSION,
                secureStore.deviceId(),
                secureStore.identityPublicKey(),
                deviceName
            )
        )
        return true
    }

    private fun handleMessage(link: PeerLink?, message: AppMessage) {
        scope.launch {
            when (message) {
                is AppMessage.Text -> {
                    val id = Hex.encode(message.messageId)
                    val sender = repository.peer()?.deviceId ?: "peer"
                    // Store before acknowledging, as the protocol requires.
                    repository.insertIncoming(id, message.body, message.createdAt, message.sequence, sender)
                    repository.refresh()
                    (link ?: activeLink)?.sendApplicationMessage(AppMessage.Ack(message.messageId))
                }

                is AppMessage.Ack -> {
                    repository.setState(Hex.encode(message.messageId), MessageState.DELIVERED)
                }

                is AppMessage.Hello -> {
                    val handshakeIdentity = link?.peerIdentity
                    if (message.version != Protocol.WIRE_VERSION ||
                        (handshakeIdentity != null && !message.identityPublicKey.contentEquals(handshakeIdentity))
                    ) {
                        (link ?: activeLink)?.close("identity_mismatch")
                        _events.tryEmit(EngineEvent.PeerIdentityMismatch)
                        return@launch
                    }
                    val deviceId = Hex.encode(message.deviceId)
                    val pinned = repository.peer()
                    if (pinned?.deviceId != null && pinned.deviceId != deviceId) {
                        (link ?: activeLink)?.close("identity_mismatch")
                        _events.tryEmit(EngineEvent.PeerIdentityMismatch)
                        return@launch
                    }
                    repository.savePeer(deviceId, Hex.encode(message.identityPublicKey), message.displayName)
                    _status.update { it.copy(peerName = message.displayName) }
                    _events.tryEmit(EngineEvent.PairingCompleted)
                }
            }
        }
    }

    /**
     * Delivers everything still queued. The link has a bounded send window, so this waits for
     * acknowledgements instead of dropping the tail of the outbox.
     */
    private suspend fun flushOutbox(link: PeerLink) {
        var rounds = 0
        while (link.isReady && rounds < MAX_FLUSH_ROUNDS) {
            val pending = repository.pendingOutgoing()
            if (pending.isEmpty()) return
            var blocked = false
            for (message in pending) {
                if (!link.isReady) return
                if (!deliver(link, message)) blocked = true
            }
            if (!blocked) return
            delay(FLUSH_RETRY_MS)
            rounds++
        }
    }

    private fun deliver(link: PeerLink, message: Message): Boolean {
        val idBytes = Hex.decode(message.id) ?: return true
        val payload = AppMessage.Text(idBytes, message.sequence, message.createdAt, message.body)
        return link.sendApplicationMessage(payload) {
            scope.launch { repository.setState(message.id, MessageState.SENT) }
        }
    }

    private fun ensureTransport() {
        synchronized(transportLock) {
            val existing = transport
            if (existing?.socketOrNull() != null) return
            existing?.close()
            val created = UdpTransport { data, from -> routeDatagram(data, from) }
            transport = if (created.start()) created else null
            dht = null
            dhtSocket = null
        }
    }

    private fun dhtClientFor(socket: DatagramSocket): DhtClient {
        val existing = dht
        if (existing != null && dhtSocket === socket) return existing
        val created = DhtClient(socket, Crypto.randomBytes(20))
        dht = created
        dhtSocket = socket
        return created
    }

    private fun rendezvousFor(socket: DatagramSocket): PublicRendezvous {
        val existing = rendezvous
        if (existing != null && rendezvousSocket === socket) return existing
        val created = PublicRendezvous(socket)
        rendezvous = created
        rendezvousSocket = socket
        return created
    }

    private fun routeDatagram(data: ByteArray, from: InetSocketAddress) {
        if (Wire.isPeerPacket(data)) {
            routePeerPacket(data, from)
            return
        }
        if (data.isNotEmpty() && data[0] == 'd'.code.toByte()) {
            dht?.onPacket(data, from)
            return
        }
        rendezvous?.onPacket(data, from)
    }

    /**
     * Peer traffic normally belongs to the one active link. When no link exists, an authentic
     * initiation is a peer that found us first (tracker, LAN beacon, DHT); it must be answered
     * immediately instead of waiting for this side to dial, or a one-sided discovery can
     * never connect.
     */
    private fun routePeerPacket(data: ByteArray, from: InetSocketAddress) {
        if (!from.address.isReachableAddress() || from.port <= 0) return
        val active = activeLink
        val incoming = synchronized(incomingLock) { incomingLink }

        if (Wire.kindOf(data) != Wire.KIND_INITIATION) {
            (active?.takeIf { !it.isClosed } ?: incoming?.takeIf {
                !it.isClosed && incomingFrom == from
            })?.onDatagram(data, from)
            return
        }

        // An initiation is only forwarded after it proves knowledge of the invitation, is
        // not our own reflection, and matches the pinned peer once pairing has happened.
        val authKey = routingAuthKey ?: return
        if (!Handshake.isAuthenticInitiation(authKey, data)) return
        val identity = Handshake.initiationIdentity(data) ?: return
        val own = ownIdentity
        if (own != null && identity.contentEquals(own)) return
        val pinned = pinnedPeerHex
        if (pinned != null && pinned != Hex.encode(identity)) return

        if (active != null && !active.isClosed) {
            active.onDatagram(data, from)
            return
        }
        if (incoming != null && !incoming.isClosed && incomingFrom == from) {
            incoming.onDatagram(data, from)
            return
        }
        val socket = routingSocket ?: return
        val privateKey = localIdentityPrivateKey ?: return
        val publicKey = own ?: secureStore.identityPublicKey()

        val handshake = Handshake(authKey, privateKey, publicKey)
        synchronized(incomingLock) {
            val existing = incomingLink
            if (existing != null && existing !== active && !existing.isClosed) {
                existing.close("superseded")
            }
            val created = createIncomingLink(socket, from, handshake)
            incomingLink = created
            incomingFrom = from
            created.onDatagram(data, from)
        }
    }

    private fun createIncomingLink(
        socket: DatagramSocket,
        from: InetSocketAddress,
        handshake: Handshake
    ): PeerLink {
        lateinit var link: PeerLink
        val listener = object : PeerLink.Listener {
            override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) {
                scope.launch { adoptIncoming(link, peerIdentityPublicKey) }
            }

            override fun onApplicationMessage(message: AppMessage) {
                handleMessage(link, message)
            }

            override fun onClosed(reason: String) {
                synchronized(incomingLock) {
                    if (incomingLink === link) {
                        incomingLink = null
                        incomingFrom = null
                    }
                }
                if (activeLink === link) activeLink = null
            }
        }
        link = PeerLink(socket, from, handshake, scope, listener)
        return link
    }

    /** Promotes a responder link that the peer initiated. Runs on the IO dispatcher. */
    private suspend fun adoptIncoming(link: PeerLink, identity: ByteArray) {
        if (link.isClosed) return
        val current = activeLink
        if (current != null && current !== link) {
            if (current.isReady) {
                link.close("duplicate")
                return
            }
            current.close("superseded")
        }
        activeLink = link
        synchronized(incomingLock) {
            if (incomingLink === link) {
                incomingLink = null
                incomingFrom = null
            }
        }
        if (!finalizeLink(link, identity)) {
            if (activeLink === link) activeLink = null
            return
        }
        val endpoint = link.remoteAddress
        _status.value = ConnectionStatus(
            ConnectionState.CONNECTED,
            direct = if (isLocalAddress(endpoint.address)) DirectKind.LOCAL_NETWORK else DirectKind.INTERNET
        )
        // The main loop observes the ready link and runs the outbox flush once, so a message
        // is never enqueued twice by two concurrent flushes.
    }

    /** Filters our own reflection and candidates that just failed a handshake. */
    private fun isDialable(
        candidate: InetSocketAddress,
        socket: DatagramSocket,
        client: PublicRendezvous,
        tried: MutableMap<InetSocketAddress, Long>
    ): Boolean {
        if (candidate.port <= 0 || !candidate.address.isReachableAddress()) return false
        val now = System.currentTimeMillis()
        val last = tried[candidate]
        if (last != null && now - last < CANDIDATE_RETRY_MS) return false
        if (tried.size > MAX_TRACKED_CANDIDATES) {
            tried.keys.removeIf { key -> now - (tried[key] ?: now) > CANDIDATE_RETRY_MS * 2 }
        }
        val ownPublic = client.cachedPublicEndpoint()
        if (ownPublic != null && candidate == ownPublic) return false
        if (candidate.port == socket.localPort) {
            if (candidate.address.isLoopbackAddress || isLocalAddress(candidate.address)) return false
            if (ownPublic != null && candidate.address == ownPublic.address) return false
        }
        return true
    }

    private fun startLanBeacon(topic: ByteArray) {
        if (lanBeacon != null) return
        acquireMulticastLock()
        val beacon = LanBeacon(
            topic = topic,
            primaryPortProvider = { transport?.localPort ?: 0 },
            onPeer = { address, port -> candidateSink?.invoke(InetSocketAddress(address, port)) }
        )
        beacon.start()
        lanBeacon = beacon
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("tpmsg-lan-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // Without the lock broadcast reception is just less reliable; discovery still works.
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (e: Exception) {
        }
        multicastLock = null
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onDefaultNetworkChanged()

                override fun onLost(network: Network) = onDefaultNetworkChanged()
            }
            // Default network only: Wi-Fi and mobile data coexisting must not count as an
            // event, and a live link survives the switch on its own.
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
        }
    }

    private fun onDefaultNetworkChanged() {
        if (foregroundCount <= 0) return
        if (_status.value.state == ConnectionState.CONNECTED) return
        scheduleRestart()
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
        }
        networkCallback = null
    }

    private fun scheduleRestart() {
        if (foregroundCount <= 0) return
        scope.launch { restartRuntime() }
    }

    private suspend fun restartRuntime() {
        restartMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastRestartAt < RESTART_DEBOUNCE_MS) return
            lastRestartAt = now
            stopResources()
            delay(RESTART_SETTLE_MS)
            if (foregroundCount > 0) start()
        }
    }

    private fun stopResources() {
        loopJob?.cancel()
        loopJob = null
        candidateSink = null
        routingAuthKey = null
        routingSocket = null
        activeLink?.close("stopped")
        activeLink = null
        synchronized(incomingLock) {
            incomingLink?.close("stopped")
            incomingLink = null
            incomingFrom = null
        }
        lanBeacon?.stop()
        lanBeacon = null
        releaseMulticastLock()
        synchronized(transportLock) {
            transport?.close()
            transport = null
            dht = null
            dhtSocket = null
            rendezvous = null
            rendezvousSocket = null
        }
    }

    private fun isLocalAddress(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress

    private fun isCoroutineActive(): Boolean = loopJob?.isActive == true

    private fun InetAddress.isReachableAddress(): Boolean =
        !isAnyLocalAddress && !isMulticastAddress

    private fun nextBackoff(current: Long): Long {
        val jitter = Random().nextInt(500).toLong()
        return minOf(current * 2, MAX_BACKOFF_MS) + jitter
    }

    fun shutdown() {
        stopResources()
        unregisterNetworkCallback()
        scope.cancel()
    }

    companion object {
        private const val DISCOVERY_INTERVAL_MS = 4_000L
        private const val DISCOVERY_BUDGET_MS = 7_000L
        private const val RENDEZVOUS_INTERVAL_MS = 7_000L
        private const val RENDEZVOUS_BUDGET_MS = 8_000L
        private const val CANDIDATE_POLL_MS = 2_000L
        private const val CANDIDATE_RETRY_MS = 15_000L
        private const val MAX_TRACKED_CANDIDATES = 64
        private const val READY_POLL_MS = 500L
        private const val UNABLE_AFTER_MS = 40_000L
        private const val HANDSHAKE_BUDGET_MS = 6_000L
        private const val QUICK_HANDSHAKE_MS = 4_000L
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val RECONNECT_PAUSE_MS = 200L
        private const val RESTART_DEBOUNCE_MS = 3_000L
        private const val RESTART_SETTLE_MS = 250L
        private const val FLUSH_RETRY_MS = 120L
        private const val MAX_FLUSH_ROUNDS = 200
    }
}
