package dev.twoperson.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class SettingsModelTest {

    @Test
    fun `theme mode parses stored values case insensitively`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored(" LIGHT "))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("Dark"))
    }

    @Test
    fun `theme mode falls back to system for unknown values`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("sepia"))
    }

    @Test
    fun `notification title respects previews and blank names`() {
        assertEquals("Alex", SettingsPolicy.notificationTitle(true, "Alex", "Knot"))
        assertEquals("Knot", SettingsPolicy.notificationTitle(true, "   ", "Knot"))
        assertEquals("Knot", SettingsPolicy.notificationTitle(true, null, "Knot"))
        assertEquals("Knot", SettingsPolicy.notificationTitle(false, "Alex", "Knot"))
    }

    @Test
    fun `notification body is redacted when previews are off`() {
        assertEquals(
            "hello",
            SettingsPolicy.notificationBody(true, "hello", "Open Knot to read the message")
        )
        assertEquals(
            "Open Knot to read the message",
            SettingsPolicy.notificationBody(false, "hello", "Open Knot to read the message")
        )
    }

    @Test
    fun `background service is always on while a contact is configured`() {
        assertTrue(SettingsPolicy.shouldRunBackgroundService(true))
        assertFalse(SettingsPolicy.shouldRunBackgroundService(false))
    }

    @Test
    fun `last endpoint round trips numeric addresses`() {
        val endpoint = InetSocketAddress("192.0.2.10", 41999)
        val stored = EndpointCodec.encode(endpoint)
        val restored = EndpointCodec.decode(stored)
        assertEquals("192.0.2.10", restored?.address?.hostAddress)
        assertEquals(41999, restored?.port)
    }

    @Test
    fun `last endpoint rejects malformed or out of range values`() {
        assertNull(EndpointCodec.encode(null))
        assertNull(EndpointCodec.encode(InetSocketAddress("192.0.2.10", 0)))
        assertNull(EndpointCodec.decode(null))
        assertNull(EndpointCodec.decode("192.0.2.10"))
        assertNull(EndpointCodec.decode("|41999"))
        assertNull(EndpointCodec.decode("192.0.2.10|"))
        assertNull(EndpointCodec.decode("192.0.2.10|0"))
        assertNull(EndpointCodec.decode("192.0.2.10|70000"))
    }

    @Test
    fun `default settings keep previews on and background delivery mandatory`() {
        val defaults = AppSettings()
        assertEquals(ThemeMode.SYSTEM, defaults.theme)
        assertTrue(defaults.notificationPreviews)
    }
}
