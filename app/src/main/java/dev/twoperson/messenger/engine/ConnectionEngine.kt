package dev.twoperson.messenger.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import dev.twoperson.messenger.crypto.Crypto
import dev.twoperson.messenger.crypto.Invite
import dev.twoperson.messenger.data.ConnectionMethod
import dev.twoperson.messenger.data.Message
import dev.twoperson.messenger.data.MessageRepository
import dev.twoperson.messenger.data.MessageState
import dev.twoperson.messenger.data.SecureStore
import dev.twoperson.messenger.data.SettingsStore
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

enum class ConnectionState { NOT_PAIRED, IDLE, DISCOVERING, CONNECTING, CONNECTED, DISCONNECTED, UNABLE_TO_CONNECT }

enum class DirectKind { LOCAL_NETWORK, INTERNET }

data class ConnectionStatus(
    val state: ConnectionState = ConnectionState.IDLE,
    val direct: DirectKind? = null,
    val detail: String? = null,
    val peerName: String? = null,
    /** Concrete discovery route that produced the live link, when known. */
    val route: ConnectionRoute? = null
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
    private val repository: MessageRepository,
    private val settings: SettingsStore,
    /** Invoked only after a new incoming message (or completed file) was durably stored. */
    private val onIncomingMessage: suspend (Message) -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class IncomingApp(
        val contactId: String,
        val link: PeerLink?,
        val message: AppMessage
    )

    /** An endpoint announced by one of the discovery loops, tagged with its provenance. */
    private data class DiscoveryCandidate(
        val endpoint: InetSocketAddress,
        val route: ConnectionRoute
    )

    /**
     * Application messages are processed by one serial worker. File data must be appended in
     * transport order; launching one coroutine per datagram could reorder 16 KiB blocks.
     */
    private val messageQueue = Channel<IncomingApp>(Channel.UNLIMITED)

    private val fileTransfers = FileTransferManager(
        appContext,
        repository,
        scope,
        onIncomingFileReady = { message -> onIncomingMessage(message) }
    )

    private val _status = MutableStateFlow(ConnectionStatus())
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val messageWorker: Job = scope.launch {
        for (incoming in messageQueue) {
            try {
                processMessage(incoming.contactId, incoming.link, incoming.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A malformed or failing message must not stop later messages.
            }
        }
    }

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

    /** Contact whose invitation currently owns the socket and discovery loops. */
    @Volatile
    private var engineContactId: String? = null

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
    private var candidateSink: ((DiscoveryCandidate) -> Unit)? = null

    /**
     * Recent discovery results, used to label an incoming initiation when the endpoint was
     * also announced to this side. Cleared for every fresh discovery round.
     */
    private val candidateRoutes = ConcurrentHashMap<InetSocketAddress, ConnectionRoute>()

    /** Route attached to each live/handshaking link; the map is small and short-lived. */
    private val linkRoutes = ConcurrentHashMap<PeerLink, ConnectionRoute>()

    /** Last endpoint that carried an authenticated frame; used for instant reconnects. */
    @Volatile
    private var lastPeerEndpoint: InetSocketAddress? = null

    private var loopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var foregroundCount = 0
    private var lastRestartAt = 0L
    private var graceStopJob: Job? = null

    private val deviceName: String = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .trim()
        .ifEmpty { "Android device" }
        .take(40)

    fun isConfigured(): Boolean = repository.activeContact.value?.hasInvitation == true

    fun currentInvitationCode(contactId: String): String? =
        secureStore.inviteSecret(contactId)?.let { Invite.encode(it) }

    fun onForeground() {
        synchronized(this) {
            graceStopJob?.cancel()
            graceStopJob = null
            foregroundCount++
            if (foregroundCount == 1) start()
        }
    }

    /**
     * Android does not allow network work to continue indefinitely after the last screen goes
     * away unless a foreground service is showing its mandatory notification. By default the
     * engine keeps the direct link alive for a short grace period so a reply that arrives right
     * after leaving the app can still raise a message notification, then it shuts the socket
     * down cleanly. Users who need always-on background delivery can opt into the service.
     */
    fun onBackground() {
        synchronized(this) {
            foregroundCount = maxOf(0, foregroundCount - 1)
            if (foregroundCount != 0) return
            graceStopJob?.cancel()
            graceStopJob = scope.launch {
                delay(BACKGROUND_GRACE_MS)
                synchronized(this@ConnectionEngine) {
                    if (foregroundCount != 0) return@synchronized
                    stopResources()
                    unregisterNetworkCallback()
                    _status.value = ConnectionStatus(
                        if (isConfigured()) ConnectionState.IDLE else ConnectionState.NOT_PAIRED
                    )
                }
            }
        }
    }

    /**
     * Called after the UI changes the selected conversation. Only the selected contact is
     * connected at a time, so the old discovery socket is closed and the loop restarts with
     * the new invitation.
     */
    fun onActiveContactChanged() {
        pinnedPeerHex = null
        lastPeerEndpoint = null
        scope.launch {
            fileTransfers.cancelAll()
            restartRuntime(force = true)
        }
    }

    /**
     * Applies the user's preferred connection method. Discovery is restarted immediately so the
     * selected method is used for the next attempt instead of waiting for a reconnect.
     */
    fun setConnectionMethod(method: ConnectionMethod) {
        settings.setConnectionMethod(method)
        scope.launch { restartRuntime(force = true) }
    }

    /**
     * Makes one contact the connection target. The engine only keeps one direct link at a time,
     * so switching contacts closes the previous runtime and starts discovery for the new one.
     */
    suspend fun selectContact(contactId: String): Boolean {
        val changed = repository.activeContact.value?.id != contactId
        val selected = repository.setActiveContact(contactId)
        if (selected && changed) onActiveContactChanged()
        return selected
    }

    /** Creates a fresh invitation for one contact. Any previous pairing is dropped. */
    fun createInvitation(contactId: String): String {
        val secret = Invite.newSecret()
        secureStore.saveInviteSecret(contactId, secret)
        settings.clearLastPeerEndpoint(contactId)
        scope.launch {
            fileTransfers.cancelAll()
            repository.markInvitation(contactId, true)
            repository.resetContactPairing(contactId)
            repository.setActiveContact(contactId)
            repository.resetFailedToPending(contactId)
            pinnedPeerHex = null
            lastPeerEndpoint = null
            if (foregroundCount > 0) restartRuntime(force = true)
        }
        return Invite.encode(secret)
    }

    /** Accepts the other device's invitation code for one contact. */
    fun joinInvitation(contactId: String, code: String): Boolean {
        val secret = Invite.decode(code) ?: return false
        secureStore.saveInviteSecret(contactId, secret)
        settings.clearLastPeerEndpoint(contactId)
        scope.launch {
            fileTransfers.cancelAll()
            repository.markInvitation(contactId, true)
            repository.resetContactPairing(contactId)
            repository.setActiveContact(contactId)
            repository.resetFailedToPending(contactId)
            pinnedPeerHex = null
            lastPeerEndpoint = null
            if (foregroundCount > 0) restartRuntime(force = true)
        }
        return true
    }

    /** Removes the invitation and pinned identity but keeps local history for that contact. */
    fun forgetPartner(contactId: String) {
        secureStore.saveInviteSecret(contactId, null)
        settings.clearLastPeerEndpoint(contactId)
        scope.launch {
            repository.markInvitation(contactId, false)
            repository.resetContactPairing(contactId)
            if (engineContactId == contactId) {
                pinnedPeerHex = null
                lastPeerEndpoint = null
                stopResources()
                _status.value = ConnectionStatus(ConnectionState.NOT_PAIRED)
            }
        }
    }

    /** Deletes a contact, its invitation, its history and any app-private files. */
    suspend fun deleteContact(contactId: String) = withContext(Dispatchers.IO) {
        val wasActive = repository.activeContact.value?.id == contactId
        if (wasActive) {
            fileTransfers.cancelAll()
            stopResources()
        }
        secureStore.saveInviteSecret(contactId, null)
        settings.clearLastPeerEndpoint(contactId)
        val removed = repository.deleteContact(contactId)
        fileTransfers.deleteLocalFiles(removed)
        if (wasActive) {
            pinnedPeerHex = null
            lastPeerEndpoint = null
            if (repository.activeContact.value != null) {
                restartRuntime(force = true)
            } else {
                _status.value = ConnectionStatus(ConnectionState.NOT_PAIRED)
            }
        }
    }

    /** Deletes every contact, invitation, message and app-private file on this device. */
    suspend fun deleteAllContacts() = withContext(Dispatchers.IO) {
        fileTransfers.cancelAll()
        stopResources()
        repository.contacts.value.forEach { contact ->
            secureStore.saveInviteSecret(contact.id, null)
        }
        settings.clearAllLastPeerEndpoints()
        val removed = repository.deleteAllContacts()
        fileTransfers.deleteLocalFiles(removed)
        pinnedPeerHex = null
        lastPeerEndpoint = null
        _status.value = ConnectionStatus(ConnectionState.NOT_PAIRED)
    }

    /**
     * Cancels in-flight transfers first, then deletes one conversation and the app-private
     * copies that only those messages referenced. SAF originals are untouched. Passing no
     * contact targets the currently selected conversation.
     */
    suspend fun clearConversation(contactId: String? = null) = withContext(Dispatchers.IO) {
        val target = contactId ?: repository.activeContact.value?.id ?: return@withContext
        fileTransfers.cancelAll()
        val removed = repository.clearConversation(target)
        fileTransfers.deleteLocalFiles(removed)
    }

    /** Deletes every local conversation but keeps contacts and pairings. */
    suspend fun clearAllConversations() = withContext(Dispatchers.IO) {
        fileTransfers.cancelAll()
        val removed = repository.clearAllConversations()
        fileTransfers.deleteLocalFiles(removed)
    }

    /**
     * Stores the message locally first, then tries to send it. Returns false only when the
     * text itself is unacceptable; being offline is not a failure, it stays queued.
     */
    suspend fun sendText(raw: String): Boolean {
        val body = raw.trim()
        if (body.isEmpty()) return false
        if (body.codePointCount(0, body.length) > Protocol.MAX_TEXT_CODE_POINTS) return false
        val contact = repository.activeContact.value ?: return false
        if (!contact.hasInvitation) return false

        val id = Hex.encode(Crypto.randomBytes(Protocol.MESSAGE_ID_BYTES))
        val sequence = repository.nextSequence(contact.id)
        val message = repository.insertOutgoing(
            contact.id, id, body, System.currentTimeMillis(), sequence
        )
        repository.refresh()

        val link = activeLink
        if (link != null && link.isReady && !deliver(link, message)) {
            // Send window was full; the outbox flusher retries as acknowledgements arrive.
            scope.launch { flushOutbox(link, contact.id) }
        }
        return true
    }

    /**
     * Queues a file of any size. It is persisted immediately and streamed when the direct link
     * is available; disconnects pause the transfer and the receiver's acknowledged offset is
     * used to resume automatically.
     */
    suspend fun sendFile(uri: Uri, displayName: String, mimeType: String, size: Long): Boolean {
        val contact = repository.activeContact.value ?: return false
        if (!contact.hasInvitation) return false
        return fileTransfers.enqueueFile(contact.id, uri, displayName, mimeType, size) != null
    }

    /**
     * Re-queues one failed outgoing message or file. A connected link is nudged immediately so
     * the user does not have to wait for the next reconnect or queue poll.
     */
    suspend fun retryMessage(id: String): Boolean {
        val retried = repository.retryMessage(id)
        if (!retried) return false
        val contactId = repository.messageById(id)?.contactId ?: return true
        val link = activeLink
        if (link != null && link.isReady && repository.activeContact.value?.id == contactId) {
            scope.launch { flushOutbox(link, contactId) }
            fileTransfers.onLinkReady(link, contactId)
        }
        return true
    }

    /** Deletes one message and any app-private file copy that belonged to it. */
    suspend fun deleteMessage(id: String): Boolean {
        val message = repository.messageById(id) ?: return false
        // Stop an active transfer before its row disappears, otherwise it would keep writing
        // to a part file or streaming bytes for a message the user just removed.
        fileTransfers.cancelMessage(message)
        val removed = repository.deleteMessage(id) ?: return false
        fileTransfers.deleteLocalFiles(listOf(removed))
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
                val active = repository.activeContact.value
                if (active == null || !active.hasInvitation) {
                    routingAuthKey = null
                    routingSocket = null
                    engineContactId = null
                    _status.value = ConnectionStatus(ConnectionState.NOT_PAIRED)
                    return
                }
                val contactId = active.id
                val secret = secureStore.inviteSecret(contactId)
                if (secret == null) {
                    routingAuthKey = null
                    routingSocket = null
                    engineContactId = null
                    _status.value = ConnectionStatus(ConnectionState.NOT_PAIRED)
                    return
                }
                engineContactId = contactId
                val authKey = Invite.authKey(secret)
                val topic = Invite.topic(secret)
                val method = settings.current.connectionMethod
                // The in-memory hint is freshest; otherwise reuse the endpoint that worked
                // before the process restarted.
                if (lastPeerEndpoint == null) {
                    lastPeerEndpoint = settings.lastPeerEndpoint(contactId)
                }

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
                pinnedPeerHex = active.publicKeyHex
                routingAuthKey = authKey
                routingSocket = socket
                candidateRoutes.clear()
                val candidates = Channel<DiscoveryCandidate>(Channel.UNLIMITED)
                candidateSink = { candidate -> candidates.trySend(candidate) }
                if (method.usesLanBeacon) startLanBeacon(topic)
                val dhtClient = if (method.usesDht) dhtClientFor(socket) else null
                val rendezvousClient = if (method.usesUdpTracker) rendezvousFor(socket) else null
                _status.value = ConnectionStatus(ConnectionState.DISCOVERING)

                val discoveryJob = scope.launch {
                    coroutineScope {
                        if (dhtClient != null) {
                            launch { dhtDiscoveryLoop(topic, currentTransport, dhtClient, candidates) }
                        }
                        if (rendezvousClient != null) {
                            launch {
                                rendezvousDiscoveryLoop(
                                    topic, currentTransport, rendezvousClient, candidates
                                )
                            }
                        }
                    }
                }

                var connectedLink: PeerLink? = null
                var connectedRoute: ConnectionRoute? = null
                val tried = HashMap<InetSocketAddress, Long>()
                try {
                    // Reconnect fast path: the endpoint that last carried authenticated traffic
                    // is almost always still correct, and after a peer restart its initiation
                    // already told us the new address. Waiting for a fresh lookup costs seconds.
                    if (method.usesLastEndpointHint) {
                        lastPeerEndpoint?.let { hint ->
                            _status.value = ConnectionStatus(ConnectionState.CONNECTING)
                            tried[hint] = System.currentTimeMillis()
                            candidateRoutes[hint] = ConnectionRoute.LAST_ENDPOINT
                            connectedLink = attemptConnection(
                                hint,
                                authKey,
                                socket,
                                QUICK_HANDSHAKE_MS,
                                contactId,
                                ConnectionRoute.LAST_ENDPOINT
                            )
                            if (connectedLink != null) {
                                connectedRoute = ConnectionRoute.LAST_ENDPOINT
                            }
                        }
                    }

                    var phaseStart = System.currentTimeMillis()
                    while (isCoroutineActive() && connectedLink == null) {
                        val adopted = activeLink
                        if (adopted != null && adopted.isReady) {
                            connectedLink = adopted
                            connectedRoute = linkRoutes[adopted]
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
                        if (repository.activeContact.value?.id != contactId) break
                        if (!isDialable(candidate.endpoint, socket, rendezvousClient, tried)) continue
                        tried[candidate.endpoint] = System.currentTimeMillis()
                        _status.value = ConnectionStatus(ConnectionState.CONNECTING)
                        connectedLink = attemptConnection(
                            candidate.endpoint,
                            authKey,
                            socket,
                            HANDSHAKE_BUDGET_MS,
                            contactId,
                            candidate.route
                        )
                        if (connectedLink != null) connectedRoute = candidate.route
                        phaseStart = System.currentTimeMillis()
                    }
                } finally {
                    // Also runs when an unexpected exception unwinds the loop: an orphaned
                    // discovery job would keep querying trackers and filling a channel that
                    // nobody reads for the lifetime of the process.
                    discoveryJob.cancel()
                    candidateSink = null
                    candidates.close()
                }

                val connected = connectedLink
                if (connected == null) {
                    _status.value = ConnectionStatus(ConnectionState.UNABLE_TO_CONNECT)
                    delay(backoff)
                    backoff = nextBackoff(backoff)
                    continue
                }
                if (repository.activeContact.value?.id != contactId) {
                    connected.close("contact_switched")
                    if (activeLink === connected) activeLink = null
                    continue
                }

                backoff = INITIAL_BACKOFF_MS
                val endpoint = connected.remoteAddress
                rememberPeerEndpoint(contactId, endpoint)
                _status.value = ConnectionStatus(
                    ConnectionState.CONNECTED,
                    direct = if (isLocalAddress(endpoint.address)) DirectKind.LOCAL_NETWORK else DirectKind.INTERNET,
                    route = connectedRoute ?: linkRoutes[connected]
                )
                repository.resetFailedToPending(contactId)
                fileTransfers.onLinkReady(connected, contactId)
                val outboxJob = scope.launch { outboxLoop(connected, contactId) }
                val reason = connected.awaitClosed()
                outboxJob.cancel()
                fileTransfers.onLinkClosed(connected)
                if (activeLink === connected) activeLink = null
                // Keep the peer endpoint the link last used: after a NAT rebind or a peer
                // restart this is the address that actually works.
                rememberPeerEndpoint(contactId, connected.remoteAddress)
                // The peer acknowledged the handshake but then stopped acknowledging data for
                // ~45s: report the honest failed state instead of pretending it is in transit.
                // Nothing is deleted, and the next direct connection retries the same IDs.
                if (reason == "delivery_failed") {
                    repository.markUndeliveredAsFailed(contactId)
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
        candidates: Channel<DiscoveryCandidate>
    ) {
        while (isCoroutineActive()) {
            val socket = currentTransport.socketOrNull() ?: return
            try {
                if (client.bootstrap()) {
                    client.discover(topic, socket.localPort, DISCOVERY_BUDGET_MS)
                        .forEach { offerCandidate(it, ConnectionRoute.DHT) }
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
        candidates: Channel<DiscoveryCandidate>
    ) {
        while (isCoroutineActive()) {
            val socket = currentTransport.socketOrNull() ?: return
            try {
                client.discover(topic, socket.localPort, RENDEZVOUS_BUDGET_MS)
                    .forEach { offerCandidate(it, ConnectionRoute.UDP_TRACKER) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // STUN and trackers are best-effort; the LAN beacon and DHT keep working.
            }
            delay(RENDEZVOUS_INTERVAL_MS)
        }
    }

    /**
     * Publishes an endpoint to the dialing loop and remembers its provenance. The map also
     * lets an incoming initiation from the same endpoint report the same route.
     */
    private fun offerCandidate(endpoint: InetSocketAddress, route: ConnectionRoute) {
        candidateRoutes[endpoint] = route
        candidateSink?.invoke(DiscoveryCandidate(endpoint, route))
    }

    private suspend fun attemptConnection(
        candidate: InetSocketAddress,
        authKey: ByteArray,
        socket: DatagramSocket,
        budgetMs: Long,
        contactId: String,
        route: ConnectionRoute?
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
                handleMessage(contactId, linkRef, message)
            }

            override fun onClosed(reason: String) {
                if (activeLink === linkRef) activeLink = null
                linkRoutes.remove(linkRef)
            }
        }

        val handshake = Handshake(authKey, secureStore.identityPrivateKey(), secureStore.identityPublicKey())
        val link = PeerLink(socket, candidate, handshake, scope, listener)
        linkRef = link
        activeLink = link
        if (route != null) linkRoutes[link] = route
        val identity = link.awaitReadyOrSuperseded(budgetMs)
        if (identity == null || activeLink !== link) {
            if (activeLink === link) activeLink = null
            link.close("handshake_failed")
            return null
        }
        if (!finalizeLink(link, identity, contactId, route)) {
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
    private suspend fun finalizeLink(
        link: PeerLink,
        identity: ByteArray,
        contactId: String,
        route: ConnectionRoute?
    ): Boolean {
        val identityHex = Hex.encode(identity)
        val pinned = repository.contact(contactId)
        if (pinned?.publicKeyHex != null && pinned.publicKeyHex != identityHex) {
            link.close("identity_mismatch")
            _events.tryEmit(EngineEvent.PeerIdentityMismatch)
            return false
        }
        if (pinned?.publicKeyHex == null) {
            repository.savePeer(contactId, null, identityHex, null)
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
        // The receiver uses this to name the actual route in its connected status. Older
        // builds drop the unknown message type instead of failing the handshake.
        route?.let { link.sendApplicationMessage(AppMessage.RouteHint(it.wireCode)) }
        return true
    }

    private fun handleMessage(contactId: String, link: PeerLink?, message: AppMessage) {
        messageQueue.trySend(IncomingApp(contactId, link, message))
    }

    private suspend fun processMessage(contactId: String, link: PeerLink?, message: AppMessage) {
        when (message) {
            is AppMessage.Text -> {
                val id = Hex.encode(message.messageId)
                val sender = repository.contact(contactId)?.deviceId ?: "peer"
                // Store before acknowledging, as the protocol requires.
                val stored = repository.insertIncoming(
                    contactId = contactId,
                    id = id,
                    body = message.body,
                    createdAt = message.createdAt,
                    sequence = message.sequence,
                    senderId = sender
                )
                (link ?: activeLink)?.sendApplicationMessage(AppMessage.Ack(message.messageId))
                if (stored) {
                    repository.messageById(id)?.let { onIncomingMessage(it) }
                }
            }

            is AppMessage.Ack -> {
                repository.setState(Hex.encode(message.messageId), MessageState.DELIVERED)
            }

            is AppMessage.RouteHint -> {
                val route = ConnectionRoute.fromWireCode(message.route) ?: return
                val target = link ?: activeLink ?: return
                // Never replace a route this side observed itself; only fill a gap when an
                // incoming link could not be attributed locally.
                linkRoutes.putIfAbsent(target, route)
                if (target === activeLink) {
                    _status.update { current ->
                        if (current.state == ConnectionState.CONNECTED && current.route == null) {
                            current.copy(route = route)
                        } else {
                            current
                        }
                    }
                }
            }

            is AppMessage.Hello -> {
                val handshakeIdentity = link?.peerIdentity
                if (message.version != Protocol.WIRE_VERSION ||
                    (handshakeIdentity != null && !message.identityPublicKey.contentEquals(handshakeIdentity))
                ) {
                    (link ?: activeLink)?.close("identity_mismatch")
                    _events.tryEmit(EngineEvent.PeerIdentityMismatch)
                    return
                }
                val deviceId = Hex.encode(message.deviceId)
                val pinned = repository.contact(contactId)
                if (pinned?.deviceId != null && pinned.deviceId != deviceId) {
                    (link ?: activeLink)?.close("identity_mismatch")
                    _events.tryEmit(EngineEvent.PeerIdentityMismatch)
                    return
                }
                repository.savePeer(
                    contactId,
                    deviceId,
                    Hex.encode(message.identityPublicKey),
                    message.displayName
                )
                _status.update { it.copy(peerName = message.displayName) }
                _events.tryEmit(EngineEvent.PairingCompleted)
            }

            is AppMessage.FileOffer,
            is AppMessage.FileData,
            is AppMessage.FileEnd,
            is AppMessage.FileAck,
            is AppMessage.FileDone -> fileTransfers.handle(contactId, message, link ?: activeLink)
        }
    }

    /**
     * Delivers text messages still queued. Bulk file data is handled by [FileTransferManager]
     * and pauses whenever this queue has work, so chat stays responsive during huge transfers.
     */
    private suspend fun flushOutbox(link: PeerLink, contactId: String) {
        var rounds = 0
        while (link.isReady && rounds < MAX_FLUSH_ROUNDS) {
            val pending = repository.pendingTextOutgoing(contactId)
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

    private suspend fun outboxLoop(link: PeerLink, contactId: String) {
        while (link.isReady) {
            try {
                val pending = repository.pendingTextOutgoing(contactId)
                if (pending.isEmpty()) {
                    delay(OUTBOX_IDLE_MS)
                    continue
                }
                var blocked = false
                for (message in pending) {
                    if (!link.isReady) return
                    if (!deliver(link, message)) blocked = true
                }
                if (blocked) delay(FLUSH_RETRY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(OUTBOX_IDLE_MS)
            }
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
            val created = createIncomingLink(socket, from, handshake) ?: return
            incomingLink = created
            incomingFrom = from
            created.onDatagram(data, from)
        }
    }

    private fun createIncomingLink(
        socket: DatagramSocket,
        from: InetSocketAddress,
        handshake: Handshake
    ): PeerLink? {
        lateinit var link: PeerLink
        // Capture the contact this responder link belongs to. A contact switch closes the
        // socket and clears routingAuthKey, so this cannot be stale by the time a packet is
        // authenticated.
        val contactId = engineContactId ?: return null
        val listener = object : PeerLink.Listener {
            override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) {
                scope.launch { adoptIncoming(link, peerIdentityPublicKey, contactId) }
            }

            override fun onApplicationMessage(message: AppMessage) {
                handleMessage(contactId, link, message)
            }

            override fun onClosed(reason: String) {
                synchronized(incomingLock) {
                    if (incomingLink === link) {
                        incomingLink = null
                        incomingFrom = null
                    }
                }
                linkRoutes.remove(link)
                if (activeLink === link) activeLink = null
            }
        }
        link = PeerLink(socket, from, handshake, scope, listener)
        routeForIncoming(from)?.let { linkRoutes[link] = it }
        return link
    }

    /** Promotes a responder link that the peer initiated. Runs on the IO dispatcher. */
    private suspend fun adoptIncoming(link: PeerLink, identity: ByteArray, contactId: String) {
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
        if (!finalizeLink(link, identity, contactId, linkRoutes[link])) {
            if (activeLink === link) activeLink = null
            return
        }
        val endpoint = link.remoteAddress
        rememberPeerEndpoint(contactId, endpoint)
        _status.value = ConnectionStatus(
            ConnectionState.CONNECTED,
            direct = if (isLocalAddress(endpoint.address)) DirectKind.LOCAL_NETWORK else DirectKind.INTERNET,
            route = linkRoutes[link]
        )
        // The main loop observes the ready link and runs the outbox flush once, so a message
        // is never enqueued twice by two concurrent flushes.
    }

    /** Filters our own reflection and candidates that just failed a handshake. */
    private fun isDialable(
        candidate: InetSocketAddress,
        socket: DatagramSocket,
        client: PublicRendezvous?,
        tried: MutableMap<InetSocketAddress, Long>
    ): Boolean {
        if (candidate.port <= 0 || !candidate.address.isReachableAddress()) return false
        val now = System.currentTimeMillis()
        val last = tried[candidate]
        if (last != null && now - last < CANDIDATE_RETRY_MS) return false
        if (tried.size > MAX_TRACKED_CANDIDATES) {
            tried.keys.removeIf { key -> now - (tried[key] ?: now) > CANDIDATE_RETRY_MS * 2 }
        }
        val ownPublic = client?.cachedPublicEndpoint()
        if (ownPublic != null && candidate == ownPublic) return false
        if (candidate.port == socket.localPort) {
            if (candidate.address.isLoopbackAddress || isLocalAddress(candidate.address)) return false
            if (ownPublic != null && candidate.address == ownPublic.address) return false
        }
        return true
    }

    private fun rememberPeerEndpoint(contactId: String, endpoint: InetSocketAddress) {
        if (endpoint.port <= 0 || !endpoint.address.isReachableAddress()) return
        lastPeerEndpoint = endpoint
        settings.setLastPeerEndpoint(contactId, endpoint)
    }

    /**
     * Attributes an incoming initiation. The endpoint may have been announced by one of this
     * side's discovery loops; failing that, a private source address can only be the LAN.
     */
    private fun routeForIncoming(from: InetSocketAddress): ConnectionRoute? =
        candidateRoutes[from] ?: if (isLocalAddress(from.address)) ConnectionRoute.LAN else null

    private fun startLanBeacon(topic: ByteArray) {
        if (lanBeacon != null) return
        acquireMulticastLock()
        val beacon = LanBeacon(
            topic = topic,
            primaryPortProvider = { transport?.localPort ?: 0 },
            onPeer = { address, port ->
                offerCandidate(InetSocketAddress(address, port), ConnectionRoute.LAN)
            }
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

    private suspend fun restartRuntime(force: Boolean = false) {
        restartMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force) {
                if (now - lastRestartAt < RESTART_DEBOUNCE_MS) return
            }
            lastRestartAt = now
            stopResources()
            if (foregroundCount > 0) {
                // Give immediate feedback when the user switches method instead of leaving
                // the old "connected" or "unable" state on screen until the loop restarts.
                _status.value = ConnectionStatus(
                    if (isConfigured()) ConnectionState.DISCOVERING else ConnectionState.NOT_PAIRED
                )
            }
            delay(RESTART_SETTLE_MS)
            if (foregroundCount > 0) start()
        }
    }

    private fun stopResources() {
        loopJob?.cancel()
        loopJob = null
        engineContactId = null
        candidateSink = null
        candidateRoutes.clear()
        linkRoutes.clear()
        routingAuthKey = null
        routingSocket = null
        activeLink?.let { fileTransfers.onLinkClosed(it) }
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
        graceStopJob?.cancel()
        graceStopJob = null
        stopResources()
        unregisterNetworkCallback()
        scope.cancel()
    }

    companion object {
        private const val BACKGROUND_GRACE_MS = 20_000L
        private const val DISCOVERY_INTERVAL_MS = 4_000L
        private const val DISCOVERY_BUDGET_MS = 7_000L
        private const val RENDEZVOUS_INTERVAL_MS = 7_000L
        private const val RENDEZVOUS_BUDGET_MS = 8_000L
        private const val CANDIDATE_POLL_MS = 2_000L
        private const val CANDIDATE_RETRY_MS = 15_000L
        private const val MAX_TRACKED_CANDIDATES = 64
        private const val READY_POLL_MS = 500L
        private const val UNABLE_AFTER_MS = 30_000L
        private const val HANDSHAKE_BUDGET_MS = 6_000L
        private const val QUICK_HANDSHAKE_MS = 2_000L
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val MAX_BACKOFF_MS = 15_000L
        private const val RECONNECT_PAUSE_MS = 200L
        private const val RESTART_DEBOUNCE_MS = 3_000L
        private const val RESTART_SETTLE_MS = 250L
        private const val FLUSH_RETRY_MS = 120L
        private const val OUTBOX_IDLE_MS = 400L
        private const val MAX_FLUSH_ROUNDS = 200
    }
}
