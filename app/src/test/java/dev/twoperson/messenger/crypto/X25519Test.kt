package dev.twoperson.messenger.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class X25519Test {

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun rfc7748AliceAndBobAgreeOnSharedSecret() {
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expectedShared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        assertEquals(hex(alicePublic), hex(X25519.publicKey(alicePrivate)))
        assertEquals(hex(bobPublic), hex(X25519.publicKey(bobPrivate)))
        assertEquals(expectedShared, hex(X25519.sharedSecret(alicePrivate, bobPublic)!!))
        assertEquals(expectedShared, hex(X25519.sharedSecret(bobPrivate, alicePublic)!!))
    }

    @Test
    fun lowOrderPointIsRejected() {
        val private = Crypto.randomBytes(32)
        assertNull(X25519.sharedSecret(private, ByteArray(32)))
    }

    @Test
    fun keyPairRoundTripProducesEqualSecrets() {
        val a = X25519.generatePrivateKey()
        val b = X25519.generatePrivateKey()
        val sharedA = X25519.sharedSecret(a, X25519.publicKey(b))!!
        val sharedB = X25519.sharedSecret(b, X25519.publicKey(a))!!
        assertEquals(hex(sharedA), hex(sharedB))
    }
}
