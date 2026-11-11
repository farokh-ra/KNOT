package dev.twoperson.messenger.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress

/** How the app chooses between the light and dark Material 3 palettes. */
enum class ThemeMode(val storedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStored(value: String?): ThemeMode {
            val normalized = value?.trim()?.lowercase()
            return entries.firstOrNull { it.storedValue == normalized } ?: SYSTEM
        }
    }
}

/**
 * Compact storage form for a peer endpoint. The engine keeps the endpoint of the last
 * authenticated link so reconnects do not have to repeat discovery from scratch.
 */
internal object EndpointCodec {

    fun encode(endpoint: InetSocketAddress?): String? {
        val address = endpoint?.address?.hostAddress ?: return null
        if (endpoint.port !in 1..65535) return null
        return "$address|${endpoint.port}"
    }

    fun decode(stored: String?): InetSocketAddress? {
        val value = stored ?: return null
        val separator = value.lastIndexOf('|')
        if (separator <= 0 || separator == value.lastIndex) return null
        val port = value.substring(separator + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return try {
            InetSocketAddress(InetAddress.getByName(value.substring(0, separator)), port)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * The discovery path the user prefers for finding the other device. Every method still ends in
 * the same direct, authenticated, encrypted UDP link; this only limits how Knot looks for the
 * peer's endpoint.
 */
enum class ConnectionMethod(val storedValue: String) {
    /** Try every method below, exactly as earlier versions did. */
    AUTOMATIC("automatic"),

    /** Same-subnet LAN beacon on UDP port 41999. */
    LAN("lan"),

    /** BEP 15 UDP trackers plus STUN for the public endpoint. */
    UDP_TRACKER("udp_tracker"),

    /** BitTorrent mainline DHT (BEP 5). */
    DHT("dht"),

    /** No discovery: redial only the address that last carried authenticated traffic. */
    LAST_ENDPOINT("last_endpoint");

    val usesLanBeacon: Boolean get() = this == AUTOMATIC || this == LAN
    val usesUdpTracker: Boolean get() = this == AUTOMATIC || this == UDP_TRACKER
    val usesDht: Boolean get() = this == AUTOMATIC || this == DHT
    val usesLastEndpointHint: Boolean get() = this == AUTOMATIC || this == LAST_ENDPOINT

    companion object {
        fun fromStored(value: String?): ConnectionMethod {
            val normalized = value?.trim()?.lowercase()
            return entries.firstOrNull { it.storedValue == normalized } ?: AUTOMATIC
        }
    }
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Show the sender name and message text on message notifications. */
    val notificationPreviews: Boolean = true,
    /**
     * Keep the direct link alive while the app is fully in the background. Android requires a
     * silent, ongoing notification for this opt-in mode, so it is off by default.
     */
    val backgroundConnection: Boolean = false,
    /** Preferred discovery path for the direct connection. */
    val connectionMethod: ConnectionMethod = ConnectionMethod.AUTOMATIC
)

/** Pure settings policy, separated from SharedPreferences so it can be unit tested. */
object SettingsPolicy {

    fun notificationTitle(previews: Boolean, contactName: String?, appName: String): String =
        if (previews) {
            contactName?.trim()?.takeIf { it.isNotEmpty() } ?: appName
        } else {
            appName
        }

    fun notificationBody(previews: Boolean, preview: String, privateText: String): String =
        if (previews) preview else privateText

    fun shouldRunBackgroundService(enabled: Boolean, engineConfigured: Boolean): Boolean =
        enabled && engineConfigured
}

/**
 * Local preferences only. Nothing here is synced anywhere; the values live in the app sandbox
 * and can be removed by clearing app data like the rest of Knot's state.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.storedValue).apply()
        _settings.value = _settings.value.copy(theme = mode)
    }

    fun setNotificationPreviews(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEWS, enabled).apply()
        _settings.value = _settings.value.copy(notificationPreviews = enabled)
    }

    fun setBackgroundConnection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND, enabled).apply()
        _settings.value = _settings.value.copy(backgroundConnection = enabled)
    }

    fun setConnectionMethod(method: ConnectionMethod) {
        prefs.edit().putString(KEY_CONNECTION_METHOD, method.storedValue).apply()
        _settings.value = _settings.value.copy(connectionMethod = method)
    }

    /**
     * Last endpoint that carried authenticated traffic for [contactId]. Keeping it across
     * restarts makes the "Last known endpoint" method usable and lets Automatic reconnect
     * without running discovery again.
     */
    fun lastPeerEndpoint(contactId: String): InetSocketAddress? =
        EndpointCodec.decode(prefs.getString(lastEndpointKey(contactId), null))

    fun setLastPeerEndpoint(contactId: String, endpoint: InetSocketAddress?) {
        val key = lastEndpointKey(contactId)
        val encoded = EndpointCodec.encode(endpoint)
        val editor = prefs.edit()
        if (encoded == null) editor.remove(key) else editor.putString(key, encoded)
        editor.apply()
    }

    fun clearLastPeerEndpoint(contactId: String) {
        prefs.edit().remove(lastEndpointKey(contactId)).apply()
    }

    fun clearAllLastPeerEndpoints() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_LAST_ENDPOINT_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    /** Used by "Delete all contacts & data": the preferences are local data too. */
    fun reset() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
    }

    private fun lastEndpointKey(contactId: String): String =
        KEY_LAST_ENDPOINT_PREFIX + contactId

    private fun read(): AppSettings = AppSettings(
        theme = ThemeMode.fromStored(prefs.getString(KEY_THEME, null)),
        notificationPreviews = prefs.getBoolean(KEY_PREVIEWS, true),
        backgroundConnection = prefs.getBoolean(KEY_BACKGROUND, false),
        connectionMethod = ConnectionMethod.fromStored(prefs.getString(KEY_CONNECTION_METHOD, null))
    )

    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_PREVIEWS = "notification_previews"
        private const val KEY_BACKGROUND = "background_connection"
        private const val KEY_CONNECTION_METHOD = "connection_method"
        private const val KEY_LAST_ENDPOINT_PREFIX = "last_endpoint_"

        /** Reads the theme before the first activity is created. */
        fun storedTheme(context: Context): ThemeMode = ThemeMode.fromStored(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
        )
    }
}
