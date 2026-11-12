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

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Show the sender name and message text on message notifications. */
    val notificationPreviews: Boolean = true
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

    /**
     * Realtime delivery needs the foreground service for as long as a pairing exists. There is
     * no user-facing switch: a setting mismatch between the two devices is exactly the kind of
     * problem that made delivery look one-sided.
     */
    fun shouldRunBackgroundService(engineConfigured: Boolean): Boolean = engineConfigured
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

    /**
     * Last endpoint that carried authenticated traffic for [contactId]. Keeping it across
     * restarts lets the engine redial immediately while discovery runs in parallel.
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
        notificationPreviews = prefs.getBoolean(KEY_PREVIEWS, true)
    )

    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_PREVIEWS = "notification_previews"
        private const val KEY_LAST_ENDPOINT_PREFIX = "last_endpoint_"

        /** Reads the theme before the first activity is created. */
        fun storedTheme(context: Context): ThemeMode = ThemeMode.fromStored(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
        )
    }
}
