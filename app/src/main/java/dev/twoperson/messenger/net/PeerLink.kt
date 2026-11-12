package dev.twoperson.messenger.net

import dev.twoperson.messenger.protocol.AppCodec
import dev.twoperson.messenger.protocol.AppMessage
import dev.twoperson.messenger.protocol.Chunking
import dev.twoperson.messenger.protocol.Reassembler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.TreeMap

/**
 * One direct peer connection: authenticated handshake, encrypted frames, ordered reliable
 * delivery with acknowledgements and retransmission, and application message dispatch.
 *
 * The link never relays through anything: every byte is sent to the single peer endpoint
 * learned from discovery. Design rules that keep it alive:
 *
 *  - reliable ordering uses a sequence number carried inside the encrypted DATA payload, so
 *    ACK and heartbeat frames cannot create a hole in the data stream;
 *  - every authenticated frame refreshes the peer endpoint, so a NAT rebind or a switch
 *    between Wi-Fi and mobile data changes the address without dropping the link;
 *  - retransmission backs off exponentially and only gives up when the peer has been silent
 *    for far longer than a heartbeat interval, or when delivery has been stalled for ~45s;
 *  - a fresh handshake from the peer (it restarted) closes this link instead of being
 *    ignored, so the engine can redial the peer's new endpoint at once.
 */
class PeerLink(
    private val socket: DatagramSocket,
    initialRemote: InetSocketAddress,
    private val handshake: Handshake,
    scope: CoroutineScope,
    private val listener: Listener
) {

    interface Listener {
        /** Handshake finished and both sides proved invitation possession. */
        fun onHandshakeComplete(peerIdentityPublicKey: ByteArray)

        /** A complete, schema-valid application message arrived. */
        fun onApplicationMessage(message: AppMessage)

        /** The link is gone; the engine must rediscover. */
        fun onClosed(reason: String)
    }

    private enum class Phase { HANDSHAKING, READY, CLOSED }

    private class Delivery(val remaining: Int, val onDelivered: () -> Unit) {
        var left = remaining
    }

    private class Outstanding(
        val packet: ByteArray,
        var sentAt: Long,
        var attempts: Int,
        val delivery: Delivery?
    )

    private val lock = Any()
    private val reassembler = Reassembler()

    @Volatile
    private var remote: InetSocketAddress = initialRemote
    private var phase = Phase.HANDSHAKING
    private var channel: SecureChannel? = null

    /** Sequence of the next DATA frame this side sends. */
    private var sendDataSeq = 0L

    /** Sequence of the next DATA frame this side is willing to hand to the application. */
    private var receiveDataSeq = 0L

    private val outOfOrder = TreeMap<Long, ByteArray>()
    private val outstanding = LinkedHashMap<Long, Outstanding>()
    private var ackDirty = false
    private var lastAckAt = 0L
    private var lastReceivedAt = System.currentTimeMillis()
    private var lastSentAt = System.currentTimeMillis()
    private var lastInitiationAt = 0L
    private var lastConfirmationAt = 0L
    private var startedAt = System.currentTimeMillis()
    private var chunkCounter = 0
    private var closeNotified = false

    private val readySignal = CompletableDeferred<ByteArray>()
    private val closedSignal = CompletableDeferred<String>()

    private val ticker: Job = scope.launch { tickLoop() }

    val isReady: Boolean get() = synchronized(lock) { phase == Phase.READY }

    val isClosed: Boolean get() = synchronized(lock) { phase == Phase.CLOSED }

    val peerIdentity: ByteArray? get() = synchronized(lock) { handshake.peerIdentityPublicKey }

    val remoteAddress: InetSocketAddress get() = remote

    /** Frames the reliable send window can accept right now. Used by bulk file transfer. */
    fun freeSendSlots(): Int = synchronized(lock) {
        if (phase != Phase.READY) 0 else (MAX_OUTSTANDING - outstanding.size).coerceAtLeast(0)
    }

    suspend fun awaitReady(timeoutMs: Long): ByteArray? = withTimeoutOrNull(timeoutMs) {
        try {
            readySignal.await()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun awaitClosed(): String = try {
        closedSignal.await()
    } catch (e: Exception) {
        "closed"
    }

    /**
     * Queues [message] for delivery. Returns false when the link is not ready or the send
     * window is full; the caller must keep the message pending and retry later.
     */
    fun sendApplicationMessage(message: AppMessage, onDelivered: (() -> Unit)? = null): Boolean {
        val payload = try {
            AppCodec.encode(message)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val chunks = try {
            Chunking.chunk(nextChunkId(), payload)
        } catch (e: IllegalArgumentException) {
            return false
        }
        synchronized(lock) {
            if (phase != Phase.READY) return false
            if (outstanding.size + chunks.size > MAX_OUTSTANDING) return false
            val active = channel ?: return false
            val delivery = onDelivered?.let { Delivery(chunks.size, it) }
            val now = System.currentTimeMillis()
            for (chunk in chunks) {
                val sequence = sendDataSeq
                val framed = ByteBuffer.allocate(Wire.DATA_SEQ_BYTES + chunk.size)
                    .putLong(sequence)
                    .put(chunk)
                    .array()
                val packet = active.seal(Wire.KIND_DATA, framed)
                if (!sendRawLocked(packet)) return false
                outstanding[sequence] = Outstanding(packet, now, 0, delivery)
                sendDataSeq++
            }
            return true
        }
    }

    /**
     * Feeds a datagram from the shared socket. Only one authenticated peer can own a link,
     * so a valid packet from a different address is treated as the same peer appearing at
     * its new endpoint.
     */
    fun onDatagram(data: ByteArray, from: InetSocketAddress) {
        if (!Wire.isPeerPacket(data)) return
        synchronized(lock) {
            if (phase == Phase.CLOSED) return
            val kind = Wire.kindOf(data)

            if (kind == Wire.KIND_INITIATION) {
                if (handshake.acceptInitiation(data)) {
                    remote = from
                    lastReceivedAt = System.currentTimeMillis()
                    handshake.confirmationPacket()?.let {
                        sendRawLocked(it)
                        lastConfirmationAt = System.currentTimeMillis()
                    }
                    maybeBecomeReadyLocked()
                } else if (handshake.isAuthenticInitiation(data)) {
                    // A peer that proves knowledge of the invitation but presents a new
                    // handshake is restarting its link. Keeping this link would leave both
                    // sides deaf until the liveness timeout, so close now and let the engine
                    // redial the endpoint the initiation came from. Anything that does not
                    // carry a valid MAC is ignored: otherwise a random UDP packet could tear
                    // down a healthy link.
                    remote = from
                    closeLocked("peer_restarted")
                }
                return
            }

            if (kind == Wire.KIND_CONFIRMATION) {
                if (handshake.acceptConfirmation(data)) {
                    remote = from
                    lastReceivedAt = System.currentTimeMillis()
                    maybeBecomeReadyLocked()
                }
                return
            }

            val active = channel ?: return
            when (val result = active.open(data)) {
                is OpenResult.Ok -> {
                    // Authenticated: the peer may have moved (NAT rebind, Wi-Fi to mobile).
                    remote = from
                    lastReceivedAt = System.currentTimeMillis()
                    handleFrameLocked(result.frame)
                }

                is OpenResult.Duplicate -> if (result.kind == Wire.KIND_DATA) {
                    remote = from
                    lastReceivedAt = System.currentTimeMillis()
                    ackDirty = true
                }

                OpenResult.Invalid -> Unit
            }
        }
    }

    fun close(reason: String) {
        synchronized(lock) { closeLocked(reason) }
    }

    private fun maybeBecomeReadyLocked() {
        if (phase != Phase.HANDSHAKING) return
        if (!handshake.ready) return
        val sendKey = handshake.sendKey() ?: return
        val receiveKey = handshake.receiveKey() ?: return
        channel = SecureChannel(sendKey, receiveKey)
        phase = Phase.READY
        val identity = handshake.peerIdentityPublicKey ?: return
        readySignal.complete(identity)
        listener.onHandshakeComplete(identity)
    }

    private fun handleFrameLocked(frame: Frame) {
        when (frame.kind) {
            Wire.KIND_DATA -> {
                if (frame.payload.size <= Wire.DATA_SEQ_BYTES) return
                val sequence = ByteBuffer.wrap(frame.payload, 0, Wire.DATA_SEQ_BYTES).long
                if (sequence < receiveDataSeq) {
                    // Already delivered (our acknowledgement was lost): ack it again.
                    ackDirty = true
                    return
                }
                val chunk = frame.payload.copyOfRange(Wire.DATA_SEQ_BYTES, frame.payload.size)
                if (sequence == receiveDataSeq) {
                    deliverLocked(chunk)
                    receiveDataSeq++
                    while (true) {
                        val buffered = outOfOrder.remove(receiveDataSeq) ?: break
                        deliverLocked(buffered)
                        receiveDataSeq++
                    }
                } else if (outOfOrder.size < MAX_REORDER) {
                    outOfOrder[sequence] = chunk
                }
                ackDirty = true
            }

            Wire.KIND_ACK -> handleAckLocked(frame.payload)

            // Liveness only. It deliberately does not touch the data sequence.
            Wire.KIND_CONTROL -> Unit
        }
    }

    private fun deliverLocked(payload: ByteArray) {
        val complete = reassembler.accept(payload, System.currentTimeMillis()) ?: return
        val message = AppCodec.decode(complete) ?: return
        listener.onApplicationMessage(message)
    }

    private fun handleAckLocked(payload: ByteArray) {
        if (payload.size <= ACK_BASE_BYTES) return
        val cumulative = ByteBuffer.wrap(payload).long
        val bitmapBytes = payload.size - ACK_BASE_BYTES
        for (sequence in outstanding.keys.toList()) {
            val acked = if (sequence <= cumulative) {
                true
            } else {
                val offset = sequence - cumulative - 1
                if (offset >= bitmapBytes * 8L) {
                    false
                } else {
                    val bit = payload[ACK_BASE_BYTES + (offset / 8).toInt()].toInt() shr
                        ((offset % 8).toInt()) and 1
                    bit == 1
                }
            }
            if (!acked) continue
            val frame = outstanding.remove(sequence) ?: continue
            completeDeliveryLocked(frame.delivery)
        }
    }

    private fun completeDeliveryLocked(delivery: Delivery?) {
        if (delivery == null) return
        delivery.left--
        if (delivery.left == 0) delivery.onDelivered()
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(TICK_MS)
            synchronized(lock) {
                if (phase == Phase.CLOSED) return
                val now = System.currentTimeMillis()
                when (phase) {
                    Phase.HANDSHAKING -> {
                        if (now - startedAt > HANDSHAKE_TIMEOUT_MS) {
                            closeLocked("handshake_timeout")
                            return
                        }
                        if (now - lastInitiationAt > HANDSHAKE_RETRY_MS) {
                            sendRawLocked(handshake.initiationPacket)
                            lastInitiationAt = now
                        }
                        if (handshake.hasPeerInitiation && now - lastConfirmationAt > HANDSHAKE_RETRY_MS) {
                            handshake.confirmationPacket()?.let {
                                sendRawLocked(it)
                                lastConfirmationAt = now
                            }
                        }
                        maybeBecomeReadyLocked()
                    }

                    Phase.READY -> {
                        if (now - lastReceivedAt > PEER_TIMEOUT_MS) {
                            closeLocked("timeout")
                            return
                        }
                        retransmitLocked(now)
                        if (outstanding.values.any { it.attempts >= MAX_ATTEMPTS }) {
                            closeLocked("delivery_failed")
                            return
                        }
                        if (ackDirty && now - lastAckAt >= ACK_INTERVAL_MS) sendAckLocked()
                        if (now - lastSentAt >= HEARTBEAT_MS) {
                            channel?.seal(Wire.KIND_CONTROL, byteArrayOf(CONTROL_HEARTBEAT))?.let(::sendRawLocked)
                        }
                    }

                    Phase.CLOSED -> return
                }
            }
        }
    }

    private fun retransmitLocked(now: Long) {
        // Snapshot: a socket error closes the link, which clears the map mid-iteration.
        for (frame in outstanding.values.toList()) {
            if (phase == Phase.CLOSED) return
            val wait = minOf(BASE_RETRANSMIT_MS shl frame.attempts.coerceAtMost(MAX_BACKOFF_SHIFT), MAX_RETRANSMIT_MS)
            if (now - frame.sentAt < wait) continue
            frame.attempts++
            frame.sentAt = now
            sendRawLocked(frame.packet)
        }
    }

    private fun sendAckLocked() {
        val active = channel ?: return
        val cumulative = receiveDataSeq - 1
        // Selective acknowledgement covers the whole send window. With only 32 bits, any
        // frame buffered beyond cumulative+32 looked unacknowledged and was retransmitted
        // again and again, flooding the path as soon as one datagram was lost.
        val bitmap = ByteArray(MAX_OUTSTANDING / 8)
        for (sequence in outOfOrder.keys) {
            val offset = sequence - cumulative - 1
            if (offset < 0 || offset >= MAX_OUTSTANDING) continue
            val index = offset.toInt()
            bitmap[index / 8] = (bitmap[index / 8].toInt() or (1 shl (index % 8))).toByte()
        }
        val payload = ByteBuffer.allocate(ACK_BASE_BYTES + bitmap.size).putLong(cumulative).put(bitmap).array()
        sendRawLocked(active.seal(Wire.KIND_ACK, payload))
        ackDirty = false
        lastAckAt = System.currentTimeMillis()
    }

    /** Returns false when the socket refused the packet, which closes the link. */
    private fun sendRawLocked(packet: ByteArray): Boolean = try {
        socket.send(DatagramPacket(packet, packet.size, remote))
        lastSentAt = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        closeLocked("socket_error")
        false
    }

    private fun closeLocked(reason: String) {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        ticker.cancel()
        outstanding.clear()
        outOfOrder.clear()
        if (!closeNotified) {
            closeNotified = true
            if (!readySignal.isCompleted) readySignal.cancel()
            closedSignal.complete(reason)
            listener.onClosed(reason)
        }
    }

    private fun nextChunkId(): Int {
        chunkCounter = (chunkCounter + 1) and 0x7FFFFFFF
        return chunkCounter
    }

    companion object {
        /**
         * Fast enough to drain a full send window quickly, cheap enough that an idle link
         * still sleeps most of the time.
         */
        private const val TICK_MS = 50L
        private const val HANDSHAKE_TIMEOUT_MS = 25_000L
        private const val HANDSHAKE_RETRY_MS = 400L

        /**
         * The peer is considered gone after five missed 3-second heartbeats. The previous
         * 30-second window left the UI claiming CONNECTED long after the path died, which
         * delayed reconnection when a phone left and rejoined a network.
         */
        private const val PEER_TIMEOUT_MS = 15_000L
        private const val HEARTBEAT_MS = 3_000L

        private const val BASE_RETRANSMIT_MS = 300L
        private const val MAX_RETRANSMIT_MS = 4_000L
        private const val MAX_BACKOFF_SHIFT = 4
        private const val ACK_INTERVAL_MS = 15L

        /** ~60s of a completely stalled path before an undelivered message is reported failed. */
        private const val MAX_ATTEMPTS = 16

        /**
         * ~4.4 MB of data can be in flight. A wider window keeps a fast link busy even when
         * the round-trip time is high, which is what turns a chat-sized window into
         * file-transfer throughput; the encrypted payloads are still small datagrams.
         */
        private const val MAX_OUTSTANDING = 4096
        private const val MAX_REORDER = 4096

        /** Cumulative sequence plus the selective-acknowledgement bitmap. */
        private const val ACK_BASE_BYTES = 8
        private const val CONTROL_HEARTBEAT = 1.toByte()
    }
}
