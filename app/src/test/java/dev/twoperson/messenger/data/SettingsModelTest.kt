package dev.twoperson.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `default settings are private and background friendly by choice`() {
        val defaults = AppSettings()
        assertEquals(ThemeMode.SYSTEM, defaults.theme)
        assertTrue(defaults.notificationPreviews)
        assertFalse(defaults.backgroundConnection)
    }
}
