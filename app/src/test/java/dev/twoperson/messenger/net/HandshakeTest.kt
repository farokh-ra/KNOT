package dev.twoperson.messenger.net

import dev.twoperson.messenger.crypto.Crypto
import dev.twoperson.messenger.crypto.Invite
import dev.twoperson.messenger.crypto.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class HandshakeTest {

    private class Identity {
        val privateKey: ByteArray = X25519.generatePrivateKey(SecureRandom())
        val publicKey: ByteArray = X25519.publicKey(privateKey)
    }

    private fun handshakeFor(secret: ByteArray, identity: Identity = Identity()): Handshake =
        Handshake(Invite.authKey(secret), identity.privateKey, identity.publicKey)

    @Test
    fun bothSidesDeriveMirroredKeys() {
        val secret = Invite.newSecret()
        val identityA = Identity()
        val identityB = Identity()
        val a = handshakeFor(secret, identityA)
        val b = handshakeFor(secret, identityB)

        assertTrue(b.acceptInitiation(a.initiationPacket))
        assertTrue(a.acceptInitiation(b.initiationPacket))
        assertNotNull(a.confirmationPacket())
        assertNotNull(b.confirmationPacket())

        assertTrue(b.acceptConfirmation(a.confirmationPacket()!!))
        assertTrue(a.acceptConfirmation(b.confirmationPacket()!!))
        assertTrue(a.ready)
        assertTrue(b.ready)

        assertArrayEquals(a.sendKey()!!, b.receiveKey()!!)
        assertArrayEquals(b.sendKey()!!, a.receiveKey()!!)
        assertFalse(a.sendKey()!!.contentEquals(a.receiveKey()!!))
        assertArrayEquals(identityB.publicKey, a.peerIdentityPublicKey)
        assertArrayEquals(identityA.publicKey, b.peerIdentityPublicKey)
    }

    @Test
    fun differentInvitationCannotJoin() {
        val a = handshakeFor(Invite.newSecret())
        val intruder = handshakeFor(Invite.newSecret())
        assertFalse(a.acceptInitiation(intruder.initiationPacket))
        assertFalse(a.hasPeerInitiation)
    }

    @Test
    fun tamperedInitiationIsRejected() {
        val secret = Invite.newSecret()
        val a = handshakeFor(secret)
        val b = handshakeFor(secret)
        val tampered = b.initiationPacket.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertFalse(a.acceptInitiation(tampered))
    }

    @Test
    fun repeatedInitiationIsIdempotent() {
        val secret = Invite.newSecret()
        val a = handshakeFor(secret)
        val b = handshakeFor(secret)
        assertTrue(a.acceptInitiation(b.initiationPacket))
        assertTrue(a.acceptInitiation(b.initiationPacket))
    }

    @Test
    fun confirmationFromAnotherSessionIsRejected() {
        val secret = Invite.newSecret()
        val a = handshakeFor(secret)
        val b = handshakeFor(secret)
        val otherPeerOne = handshakeFor(secret)
        val otherPeerTwo = handshakeFor(secret)

        assertTrue(a.acceptInitiation(b.initiationPacket))
        assertTrue(b.acceptInitiation(a.initiationPacket))
        assertTrue(otherPeerOne.acceptInitiation(otherPeerTwo.initiationPacket))

        // A confirmation from an unrelated session must not complete this handshake.
        assertFalse(a.acceptConfirmation(otherPeerOne.confirmationPacket()!!))
        assertFalse(a.ready)
        assertTrue(a.acceptConfirmation(b.confirmationPacket()!!))
        assertTrue(a.ready)
    }

    @Test
    fun staticInitiationCheckProvesInvitationWithoutSideEffects() {
        val secret = Invite.newSecret()
        val authKey = Invite.authKey(secret)
        val identity = Identity()
        val packet = Handshake(authKey, identity.privateKey, identity.publicKey).initiationPacket

        assertTrue(Handshake.isAuthenticInitiation(authKey, packet))
        assertArrayEquals(identity.publicKey, Handshake.initiationIdentity(packet))
        assertFalse(Handshake.isAuthenticInitiation(Invite.authKey(Invite.newSecret()), packet))

        val tampered = packet.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertFalse(Handshake.isAuthenticInitiation(authKey, tampered))
        assertNull(Handshake.initiationIdentity(tampered.copyOfRange(0, 12)))
    }

    @Test
    fun keyDerivationIsBoundToTheTranscript() {
        val secret = Invite.newSecret()
        val identityA = Identity()
        val identityB = Identity()
        val a1 = handshakeFor(secret, identityA)
        val b1 = handshakeFor(secret, identityB)
        val a2 = handshakeFor(secret, identityA)
        val b2 = handshakeFor(secret, identityB)

        assertTrue(a1.acceptInitiation(b1.initiationPacket))
        assertTrue(b1.acceptInitiation(a1.initiationPacket))
        assertTrue(a2.acceptInitiation(b2.initiationPacket))
        assertTrue(b2.acceptInitiation(a2.initiationPacket))

        assertFalse(Crypto.constantTimeEquals(a1.sendKey()!!, a2.sendKey()!!))
    }
}
