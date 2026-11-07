package dev.twoperson.messenger.net.dht

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BencodeTest {

    @Test
    fun roundTripsNestedStructures() {
        val value = BValue.BDict(
            mapOf(
                "t" to BValue.BStr(byteArrayOf(0, 1, 2)),
                "y" to Bencode.str("q"),
                "a" to BValue.BDict(
                    mapOf(
                        "id" to BValue.BStr(ByteArray(20) { it.toByte() }),
                        "port" to BValue.BInt(6881),
                        "implied_port" to BValue.BInt(1)
                    )
                ),
                "nodes" to BValue.BList(listOf(BValue.BStr(ByteArray(26))))
            )
        )
        val decoded = Bencode.decode(Bencode.encode(value))
        assertEquals(value, decoded)
    }

    @Test
    fun encodesCanonicalDictionaryOrder() {
        val encoded = Bencode.encode(BValue.BDict(mapOf("b" to BValue.BInt(1), "a" to BValue.BInt(2))))
        assertEquals("d1:ai2e1:bi1ee", String(encoded, Charsets.US_ASCII))
    }

    @Test
    fun decodesBinaryStringsWithNulBytes() {
        val payload = BValue.BStr(ByteArray(20) { (it * 7).toByte() })
        val decoded = Bencode.decode(Bencode.encode(payload)) as BValue.BStr
        assertArrayEquals(payload.bytes, decoded.bytes)
    }

    @Test
    fun rejectsMalformedInput() {
        assertNull(Bencode.decode(ByteArray(0)))
        assertNull(Bencode.decode("i12".toByteArray()))
        assertNull(Bencode.decode("5:abc".toByteArray()))
        assertNull(Bencode.decode("d1:ai1e".toByteArray()))
        assertNull(Bencode.decode("x".toByteArray()))
        assertNull(Bencode.decode("l".toByteArray()))
    }

    @Test
    fun rejectsTrailingGarbage() {
        assertNull(Bencode.decode("i1ei2e".toByteArray()))
        assertTrue(Bencode.decode("le".toByteArray()) != null)
    }
}
