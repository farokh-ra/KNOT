package dev.twoperson.messenger.util

import java.io.ByteArrayOutputStream

/**
 * RFC 4648 base32 without padding, used to make invitation secrets copyable and QR friendly.
 * Decoding is deliberately lenient about separators humans add (dashes, spaces) and strict
 * about every other character.
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = ((buffer shl 8) or (b.toInt() and 0xFF)) and 0xFFFF
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer ushr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray? {
        val clean = text.uppercase().filter { it != '-' && it != '=' && !it.isWhitespace() }
        if (clean.isEmpty()) return null
        val out = ByteArrayOutputStream(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = ((buffer shl 5) or value) and 0xFFFF
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write((buffer ushr (bitsLeft - 8)) and 0xFF)
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
