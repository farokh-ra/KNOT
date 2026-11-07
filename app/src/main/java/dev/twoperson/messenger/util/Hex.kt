package dev.twoperson.messenger.util

/** Strict hexadecimal helpers. Used for IDs in storage and logs, never for secrets. */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(DIGITS[v ushr 4])
            out.append(DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = digit(text[2 * i]) ?: return null
            val lo = digit(text[2 * i + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
