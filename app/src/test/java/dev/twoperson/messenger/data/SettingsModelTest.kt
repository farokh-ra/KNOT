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
    fun `background service requires both the setting and a pairing`() {
        assertTrue(SettingsPolicy.shouldRunBackgroundService(true, true))
        assertFalse(SettingsPolicy.shouldRunBackgroundService(true, false))
        assertFalse(SettingsPolicy.shouldRunBackgroundService(false, true))
        assertFalse(SettingsPolicy.shouldRunBackgroundService(false, false))
    }

    @Test
    fun `connection method parses stored values and defaults to automatic`() {
        assertEquals(ConnectionMethod.LAN, ConnectionMethod.fromStored(" LAN "))
        assertEquals(ConnectionMethod.UDP_TRACKER, ConnectionMethod.fromStored("udp_tracker"))
        assertEquals(ConnectionMethod.DHT, ConnectionMethod.fromStored("DHT"))
        assertEquals(
            ConnectionMethod.LAST_ENDPOINT,
            ConnectionMethod.fromStored("last_endpoint")
        )
        assertEquals(ConnectionMethod.AUTOMATIC, ConnectionMethod.fromStored(null))
        assertEquals(ConnectionMethod.AUTOMATIC, ConnectionMethod.fromStored("carrier-pigeon"))
    }

    @Test
    fun `connection method capability helpers follow the selection`() {
        assertTrue(ConnectionMethod.AUTOMATIC.usesLanBeacon)
        assertTrue(ConnectionMethod.AUTOMATIC.usesUdpTracker)
        assertTrue(ConnectionMethod.AUTOMATIC.usesDht)
        assertTrue(ConnectionMethod.AUTOMATIC.usesLastEndpointHint)

        assertTrue(ConnectionMethod.LAN.usesLanBeacon)
        assertFalse(ConnectionMethod.LAN.usesUdpTracker)
        assertFalse(ConnectionMethod.LAN.usesDht)

        assertTrue(ConnectionMethod.UDP_TRACKER.usesUdpTracker)
        assertFalse(ConnectionMethod.UDP_TRACKER.usesLanBeacon)
        assertFalse(ConnectionMethod.UDP_TRACKER.usesDht)

        assertTrue(ConnectionMethod.DHT.usesDht)
        assertFalse(ConnectionMethod.DHT.usesLanBeacon)
        assertFalse(ConnectionMethod.DHT.usesUdpTracker)

        assertTrue(ConnectionMethod.LAST_ENDPOINT.usesLastEndpointHint)
        assertFalse(ConnectionMethod.LAST_ENDPOINT.usesLanBeacon)
        assertFalse(ConnectionMethod.LAST_ENDPOINT.usesUdpTracker)
        assertFalse(ConnectionMethod.LAST_ENDPOINT.usesDht)
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
    fun `default settings are private and background friendly by choice`() {
        val defaults = AppSettings()
        assertEquals(ThemeMode.SYSTEM, defaults.theme)
        assertTrue(defaults.notificationPreviews)
        assertFalse(defaults.backgroundConnection)
        assertEquals(ConnectionMethod.AUTOMATIC, defaults.connectionMethod)
    }
}
