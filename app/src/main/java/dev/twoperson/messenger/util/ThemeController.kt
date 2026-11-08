package dev.twoperson.messenger.util

import androidx.appcompat.app.AppCompatDelegate
import dev.twoperson.messenger.data.ThemeMode

/** Single place that maps the local theme preference onto AppCompat's night mode. */
object ThemeController {

    fun delegateMode(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    fun apply(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(delegateMode(mode))
    }
}
