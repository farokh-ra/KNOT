package dev.twoperson.messenger.net

import dev.twoperson.messenger.crypto.Crypto
import dev.twoperson.messenger.crypto.Invite
import dev.twoperson.messenger.crypto.X25519
import dev.twoperson.messenger.protocol.AppMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Full stack test without Android: two PeerLinks over loopback UDP perform the real
 * handshake, exchange an encrypted message and confirm transport delivery.
 */
class PeerLinkTest {

    @Test
    fun twoLinksHandshakeAndDeliverAMessage() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val socketA = DatagramSocket(0, loopback)
        val socketB = DatagramSocket(0, loopback)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secret = Invite.newSecret()
        val authKey = Invite.authKey(secret)

        val privateA = X25519.generatePrivateKey()
        val publicA = X25519.publicKey(privateA)
        val privateB = X25519.generatePrivateKey()
        val publicB = X25519.publicKey(privateB)

        val received = Channel<AppMessage>(Channel.UNLIMITED)
        val delivered = CompletableDeferred<Unit>()
        var identitySeenByB: ByteArray? = null

        lateinit var linkA: PeerLink
        lateinit var linkB: PeerLink

        linkA = PeerLink(
            socketA,
            InetSocketAddress(loopback, socketB.localPort),
            Handshake(authKey, privateA, publicA),
            scope,
            object : PeerLink.Listener {
                override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit
                override fun onApplicationMessage(message: AppMessage) = Unit
                override fun onClosed(reason: String) = Unit
            }
        )
        linkB = PeerLink(
            socketB,
            InetSocketAddress(loopback, socketA.localPort),
            Handshake(authKey, privateB, publicB),
            scope,
            object : PeerLink.Listener {
                override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) {
                    identitySeenByB = peerIdentityPublicKey
                }

                override fun onApplicationMessage(message: AppMessage) {
                    received.trySend(message)
                }

                override fun onClosed(reason: String) = Unit
            }
        )

        val pumpA = scope.launch(Dispatchers.IO) { pump(socketA, linkA) }
        val pumpB = scope.launch(Dispatchers.IO) { pump(socketB, linkB) }

        try {
            assertNotNull(linkA.awaitReady(10_000))
            assertNotNull(linkB.awaitReady(10_000))
            assertEquals(publicA.toList(), identitySeenByB!!.toList())

            val messageId = Crypto.randomBytes(16)
            val text = AppMessage.Text(messageId, 3, System.currentTimeMillis(), "direct hello ✓")
            assertTrue(linkA.sendApplicationMessage(text) { delivered.complete(Unit) })

            val receivedMessage = withTimeout(10_000) { received.receive() } as AppMessage.Text
            assertEquals("direct hello ✓", receivedMessage.body)
            assertEquals(messageId.toList(), receivedMessage.messageId.toList())

            withTimeout(10_000) { delivered.await() }
        } finally {
            linkA.close("test_done")
            linkB.close("test_done")
            pumpA.cancel()
            pumpB.cancel()
            socketA.close()
            socketB.close()
            scope.cancel()
        }
    }

    /**
     * Regression for the connection that "worked, then kept disconnecting".
     *
     * Acknowledgements and heartbeats used to consume the same counter as data frames while
     * only data frames advanced the receive pointer, so the first ACK or heartbeat left a
     * permanent hole: every later message was buffered as out of order, retransmitted and
     * finally killed the link with delivery_failed. With the v2 framing the data sequence is
     * carried inside the encrypted payload and control frames are outside it, so both
     * directions must keep working across ACKs and idle heartbeats.
     */
    @Test
    fun messagesFlowBothWaysAfterAcksAndHeartbeats() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val socketA = DatagramSocket(0, loopback)
        val socketB = DatagramSocket(0, loopback)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authKey = Invite.authKey(Invite.newSecret())

        val privateA = X25519.generatePrivateKey()
        val privateB = X25519.generatePrivateKey()
        val toA = Channel<AppMessage>(Channel.UNLIMITED)
        val toB = Channel<AppMessage>(Channel.UNLIMITED)
        var closedA: String? = null
        var closedB: String? = null

        fun message(body: String) = AppMessage.Text(
            Crypto.randomBytes(16),
            1,
            System.currentTimeMillis(),
            body
        )

        lateinit var linkA: PeerLink
        lateinit var linkB: PeerLink

        linkA = PeerLink(
            socketA,
            InetSocketAddress(loopback, socketB.localPort),
            Handshake(authKey, privateA, X25519.publicKey(privateA)),
            scope,
            object : PeerLink.Listener {
                override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit
                override fun onApplicationMessage(message: AppMessage) {
                    toA.trySend(message)
                }

                override fun onClosed(reason: String) {
                    closedA = reason
                }
            }
        )
        linkB = PeerLink(
            socketB,
            InetSocketAddress(loopback, socketA.localPort),
            Handshake(authKey, privateB, X25519.publicKey(privateB)),
            scope,
            object : PeerLink.Listener {
                override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit
                override fun onApplicationMessage(message: AppMessage) {
                    toB.trySend(message)
                }

                override fun onClosed(reason: String) {
                    closedB = reason
                }
            }
        )

        val pumpA = scope.launch(Dispatchers.IO) { pump(socketA, linkA) }
        val pumpB = scope.launch(Dispatchers.IO) { pump(socketB, linkB) }

        try {
            assertNotNull(linkA.awaitReady(10_000))
            assertNotNull(linkB.awaitReady(10_000))

            // First message: B receives it and answers with a transport ACK.
            assertTrue(linkA.sendApplicationMessage(message("one")))
            assertEquals("one", (withTimeout(10_000) { toB.receive() } as AppMessage.Text).body)

            // Idle past the heartbeat interval so control frames are exchanged too.
            delay(3_500)
            assertTrue(linkA.isReady)
            assertTrue(linkB.isReady)

            // The reverse direction was the one that used to stall forever.
            assertTrue(linkB.sendApplicationMessage(message("two")))
            assertEquals("two", (withTimeout(10_000) { toA.receive() } as AppMessage.Text).body)

            assertTrue(linkA.sendApplicationMessage(message("three")))
            assertEquals("three", (withTimeout(10_000) { toB.receive() } as AppMessage.Text).body)

            assertEquals(null, closedA)
            assertEquals(null, closedB)
        } finally {
            linkA.close("test_done")
            linkB.close("test_done")
            pumpA.cancel()
            pumpB.cancel()
            socketA.close()
            socketB.close()
            scope.cancel()
        }
    }

    /**
     * A packet that merely looks like an initiation must not be able to tear down a live link
     * (the peer-restart path is gated on a MAC-valid initiation).
     */
    @Test
    fun spoofedInitiationDoesNotCloseAHealthyLink() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val socketA = DatagramSocket(0, loopback)
        val socketB = DatagramSocket(0, loopback)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authKey = Invite.authKey(Invite.newSecret())
        val privateA = X25519.generatePrivateKey()
        val privateB = X25519.generatePrivateKey()

        var closedA: String? = null
        lateinit var linkA: PeerLink
        lateinit var linkB: PeerLink
        linkA = PeerLink(
            socketA,
            InetSocketAddress(loopback, socketB.localPort),
            Handshake(authKey, privateA, X25519.publicKey(privateA)),
            scope,
            object : PeerLink.Listener {
                override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit
                override fun onApplicationMessage(message: AppMessage) = Unit
                override fun onClosed(reason: String) {
                    closedA = reason
                }
            }
        )
        linkB = PeerLink(
            socketB,
            InetSocketAddress(loopback, socketA.localPort),
            Handshake(authKey, privateB, X25519.publicKey(privateB)),
            scope,
            object : PeerLink.Listener {
                override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit
                override fun onApplicationMessage(message: AppMessage) = Unit
                override fun onClosed(reason: String) = Unit
            }
        )
        val pumpA = scope.launch(Dispatchers.IO) { pump(socketA, linkA) }
        val pumpB = scope.launch(Dispatchers.IO) { pump(socketB, linkB) }
        val attacker = DatagramSocket(0, loopback)
        try {
            assertNotNull(linkA.awaitReady(10_000))
            assertNotNull(linkB.awaitReady(10_000))

            // Correct shape, wrong MAC: exactly what an off-path sender can produce.
            val forged = Handshake(authKey, X25519.generatePrivateKey(), X25519.publicKey(X25519.generatePrivateKey())).initiationPacket
            val tampered = forged.copyOf()
            tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
            attacker.send(DatagramPacket(tampered, tampered.size, InetSocketAddress(loopback, socketA.localPort)))

            delay(1_000)
            assertEquals(null, closedA)
            assertTrue(linkA.isReady)
            assertTrue(linkB.isReady)
        } finally {
            linkA.close("test_done")
            linkB.close("test_done")
            pumpA.cancel()
            pumpB.cancel()
            attacker.close()
            socketA.close()
            socketB.close()
            scope.cancel()
        }
    }

    @Test
    fun linkWithWrongInvitationNeverConnects() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val socketA = DatagramSocket(0, loopback)
        val socketB = DatagramSocket(0, loopback)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val privateA = X25519.generatePrivateKey()
        val privateB = X25519.generatePrivateKey()
        val listener = object : PeerLink.Listener {
            override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit
            override fun onApplicationMessage(message: AppMessage) = Unit
            override fun onClosed(reason: String) = Unit
        }

        lateinit var linkA: PeerLink
        lateinit var linkB: PeerLink
        linkA = PeerLink(
            socketA,
            InetSocketAddress(loopback, socketB.localPort),
            Handshake(Invite.authKey(Invite.newSecret()), privateA, X25519.publicKey(privateA)),
            scope,
            listener
        )
        linkB = PeerLink(
            socketB,
            InetSocketAddress(loopback, socketA.localPort),
            Handshake(Invite.authKey(Invite.newSecret()), privateB, X25519.publicKey(privateB)),
            scope,
            listener
        )

        val pumpA = scope.launch(Dispatchers.IO) { pump(socketA, linkA) }
        val pumpB = scope.launch(Dispatchers.IO) { pump(socketB, linkB) }
        try {
            assertEquals(null, linkA.awaitReady(3_000))
            assertEquals(null, linkB.awaitReady(1))
        } finally {
            linkA.close("test_done")
            linkB.close("test_done")
            pumpA.cancel()
            pumpB.cancel()
            socketA.close()
            socketB.close()
            scope.cancel()
        }
    }

    /**
     * Mirrors the engine path where a link exists only because the peer dialed us: the
     * responder has never sent its initiation to the peer and must still complete the
     * handshake from the incoming packet alone.
     */
    @Test
    fun aLinkThatOnlyAnswersAnIncomingInitiationStillConnects() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val socketDialer = DatagramSocket(0, loopback)
        val socketResponder = DatagramSocket(0, loopback)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authKey = Invite.authKey(Invite.newSecret())
        val privateA = X25519.generatePrivateKey()
        val privateB = X25519.generatePrivateKey()
        val publicA = X25519.publicKey(privateA)
        val publicB = X25519.publicKey(privateB)
        val toResponder = Channel<AppMessage>(Channel.UNLIMITED)

        lateinit var dialer: PeerLink
        lateinit var responder: PeerLink

        dialer = PeerLink(
            socketDialer,
            InetSocketAddress(loopback, socketResponder.localPort),
            Handshake(authKey, privateA, publicA),
            scope,
            object : PeerLink.Listener {
                override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit
                override fun onApplicationMessage(message: AppMessage) = Unit
                override fun onClosed(reason: String) = Unit
            }
        )
        // The responder starts with a dead remote: it can only learn the peer from the
        // initiation that arrives on its socket, exactly like the engine's incoming path.
        responder = PeerLink(
            socketResponder,
            InetSocketAddress(loopback, 1),
            Handshake(authKey, privateB, publicB),
            scope,
            object : PeerLink.Listener {
                override fun onHandshakeComplete(peerIdentityPublicKey: ByteArray) = Unit
                override fun onApplicationMessage(message: AppMessage) {
                    toResponder.trySend(message)
                }

                override fun onClosed(reason: String) = Unit
            }
        )
        val pumpDialer = scope.launch(Dispatchers.IO) { pump(socketDialer, dialer) }
        val pumpResponder = scope.launch(Dispatchers.IO) { pump(socketResponder, responder) }

        try {
            assertNotNull(dialer.awaitReady(10_000))
            assertNotNull(responder.awaitReady(10_000))
            assertEquals(publicA.toList(), responder.peerIdentity!!.toList())
            assertEquals(publicB.toList(), dialer.peerIdentity!!.toList())

            val message = AppMessage.Text(
                Crypto.randomBytes(16),
                1,
                System.currentTimeMillis(),
                "incoming-only"
            )
            assertTrue(dialer.sendApplicationMessage(message))
            val received = withTimeout(10_000) { toResponder.receive() } as AppMessage.Text
            assertEquals("incoming-only", received.body)
        } finally {
            dialer.close("test_done")
            responder.close("test_done")
            pumpDialer.cancel()
            pumpResponder.cancel()
            socketDialer.close()
            socketResponder.close()
            scope.cancel()
        }
    }

    private fun pump(socket: DatagramSocket, link: PeerLink) {
        val buffer = ByteArray(2048)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val from = packet.socketAddress as InetSocketAddress
                link.onDatagram(data, from)
            } catch (e: Exception) {
                if (socket.isClosed) return
            }
        }
    }
}
