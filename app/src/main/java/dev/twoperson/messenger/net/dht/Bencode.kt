package dev.twoperson.messenger.net.dht

import java.io.ByteArrayOutputStream

/** Bencode value model. Dictionary keys are ISO-8859-1 strings, values are binary safe. */
sealed class BValue {

    class BStr(val bytes: ByteArray) : BValue() {
        override fun equals(other: Any?): Boolean = this === other || (other is BStr && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    class BInt(val value: Long) : BValue() {
        override fun equals(other: Any?): Boolean = this === other || (other is BInt && value == other.value)
        override fun hashCode(): Int = value.hashCode()
    }

    class BList(val items: List<BValue>) : BValue() {
        override fun equals(other: Any?): Boolean = this === other || (other is BList && items == other.items)
        override fun hashCode(): Int = items.hashCode()
    }

    class BDict(val entries: Map<String, BValue>) : BValue() {
        override fun equals(other: Any?): Boolean = this === other || (other is BDict && entries == other.entries)
        override fun hashCode(): Int = entries.hashCode()
    }

    fun asString(): String? = (this as? BStr)?.bytes?.toString(Charsets.ISO_8859_1)
    fun asLong(): Long? = (this as? BInt)?.value
    fun asDict(): BDict? = this as? BDict
    fun asList(): BList? = this as? BList
}

object Bencode {

    private const val MAX_DEPTH = 8
    private const val MAX_ELEMENTS = 4096
    private const val MAX_STRING_BYTES = 8192

    fun encode(value: BValue): ByteArray {
        val out = ByteArrayOutputStream()
        write(value, out)
        return out.toByteArray()
    }

    fun str(text: String): BValue.BStr = BValue.BStr(text.toByteArray(Charsets.ISO_8859_1))

    fun int(value: Long): BValue.BInt = BValue.BInt(value)

    private fun write(value: BValue, out: ByteArrayOutputStream) {
        when (value) {
            is BValue.BStr -> {
                out.write(value.bytes.size.toString().toByteArray(Charsets.US_ASCII))
                out.write(':'.code)
                out.write(value.bytes)
            }

            is BValue.BInt -> {
                out.write('i'.code)
                out.write(value.value.toString().toByteArray(Charsets.US_ASCII))
                out.write('e'.code)
            }

            is BValue.BList -> {
                out.write('l'.code)
                value.items.forEach { write(it, out) }
                out.write('e'.code)
            }

            is BValue.BDict -> {
                out.write('d'.code)
                value.entries.entries
                    .sortedBy { it.key }
                    .forEach { (key, item) ->
                        write(BValue.BStr(key.toByteArray(Charsets.ISO_8859_1)), out)
                        write(item, out)
                    }
                out.write('e'.code)
            }
        }
    }

    /** Returns null for anything that is not exactly one well-formed value. */
    fun decode(data: ByteArray): BValue? {
        if (data.isEmpty()) return null
        return Parser(data).run {
            val value = parseValue(0) ?: return null
            if (pos != data.size) null else value
        }
    }

    private class Parser(private val data: ByteArray) {

        var pos = 0

        fun parseValue(depth: Int): BValue? {
            if (depth > MAX_DEPTH || pos >= data.size) return null
            return when (val c = data[pos].toInt().toChar()) {
                'i' -> parseInteger()
                'l' -> parseList(depth)
                'd' -> parseDict(depth)
                in '0'..'9' -> parseString()
                else -> null
            }
        }

        private fun parseInteger(): BValue.BInt? {
            pos++
            val start = pos
            while (pos < data.size && data[pos].toInt().toChar() != 'e') pos++
            if (pos >= data.size || pos - start == 0) return null
            val text = String(data, start, pos - start, Charsets.US_ASCII)
            pos++
            val value = text.toLongOrNull() ?: return null
            return BValue.BInt(value)
        }

        private fun parseString(): BValue.BStr? {
            val start = pos
            while (pos < data.size && data[pos].toInt().toChar() in '0'..'9') pos++
            if (pos >= data.size || data[pos].toInt().toChar() != ':') return null
            val length = String(data, start, pos - start, Charsets.US_ASCII).toIntOrNull() ?: return null
            if (length < 0 || length > MAX_STRING_BYTES) return null
            pos++
            if (pos + length > data.size) return null
            val bytes = data.copyOfRange(pos, pos + length)
            pos += length
            return BValue.BStr(bytes)
        }

        private fun parseList(depth: Int): BValue.BList? {
            pos++
            val items = ArrayList<BValue>()
            while (true) {
                if (pos >= data.size) return null
                if (data[pos].toInt().toChar() == 'e') {
                    pos++
                    return BValue.BList(items)
                }
                if (items.size >= MAX_ELEMENTS) return null
                items.add(parseValue(depth + 1) ?: return null)
            }
        }

        private fun parseDict(depth: Int): BValue.BDict? {
            pos++
            val entries = LinkedHashMap<String, BValue>()
            while (true) {
                if (pos >= data.size) return null
                if (data[pos].toInt().toChar() == 'e') {
                    pos++
                    return BValue.BDict(entries)
                }
                if (entries.size >= MAX_ELEMENTS) return null
                val key = parseString() ?: return null
                val value = parseValue(depth + 1) ?: return null
                entries[key.bytes.toString(Charsets.ISO_8859_1)] = value
            }
        }
    }
}
