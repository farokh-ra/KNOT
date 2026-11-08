package dev.twoperson.messenger.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Show the sender name and message text on message notifications. */
    val notificationPreviews: Boolean = true,
    /**
     * Keep the direct link alive while the app is fully in the background. Android requires a
     * silent, ongoing notification for this opt-in mode, so it is off by default.
     */
    val backgroundConnection: Boolean = false
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

    /** Used by "Delete all contacts & data": the preferences are local data too. */
    fun reset() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
    }

    private fun read(): AppSettings = AppSettings(
        theme = ThemeMode.fromStored(prefs.getString(KEY_THEME, null)),
        notificationPreviews = prefs.getBoolean(KEY_PREVIEWS, true),
        backgroundConnection = prefs.getBoolean(KEY_BACKGROUND, false)
    )

    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_PREVIEWS = "notification_previews"
        private const val KEY_BACKGROUND = "background_connection"

        /** Reads the theme before the first activity is created. */
        fun storedTheme(context: Context): ThemeMode = ThemeMode.fromStored(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
        )
    }
}
