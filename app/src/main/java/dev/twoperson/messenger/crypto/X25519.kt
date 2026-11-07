package dev.twoperson.messenger.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 (RFC 7748) implemented directly on BigInteger.
 *
 * Deliberately dependency free: the app must not rely on a JCA provider that may be absent
 * or subtly different between Android versions, and there is no vendored crypto library.
 * Verified against the RFC 7748 test vectors in X25519Test.
 */
object X25519 {

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val TWO = BigInteger.valueOf(2)
    private val MASK_255: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)
    private val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** Clamps a private scalar in place, as required by RFC 7748 section 5. */
    fun clamp(scalar: ByteArray): ByteArray {
        val out = scalar.copyOf()
        out[0] = (out[0].toInt() and 248).toByte()
        out[31] = (out[31].toInt() and 127).toByte()
        out[31] = (out[31].toInt() or 64).toByte()
        return out
    }

    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(32)
        random.nextBytes(key)
        return clamp(key)
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(clamp(privateKey), BASE_POINT)

    /**
     * Returns the 32-byte shared secret, or null for a low-order peer point (the all-zero
     * result), which must never be used as a key.
     */
    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray? {
        if (peerPublicKey.size != 32) return null
        val result = scalarMult(clamp(privateKey), peerPublicKey)
        return if (result.all { it == 0.toByte() }) null else result
    }

    private fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        val uBytes = u.copyOf()
        uBytes[31] = (uBytes[31].toInt() and 127).toByte()
        val x1 = decodeScalar(uBytes)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = (k[t ushr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor bit
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            val sum = da.add(cb).mod(P)
            val difference = da.subtract(cb).mod(P)
            x3 = sum.multiply(sum).mod(P)
            z3 = x1.multiply(difference.multiply(difference).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }

        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        val inverse = z2.modPow(P.subtract(TWO), P)
        return encodeScalar(x2.multiply(inverse).mod(P))
    }

    private fun decodeScalar(bytes: ByteArray): BigInteger {
        val bigEndian = ByteArray(bytes.size)
        for (i in bytes.indices) bigEndian[i] = bytes[bytes.size - 1 - i]
        return BigInteger(1, bigEndian).and(MASK_255)
    }

    private fun encodeScalar(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        val bigEndian = value.mod(P).toByteArray()
        val start = maxOf(0, bigEndian.size - 32)
        var i = 0
        while (i < 32 && start + i < bigEndian.size) {
            out[i] = bigEndian[bigEndian.size - 1 - i]
            i++
        }
        return out
    }
}
