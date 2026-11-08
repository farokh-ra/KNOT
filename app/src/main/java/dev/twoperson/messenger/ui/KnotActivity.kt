package dev.twoperson.messenger.ui

import androidx.appcompat.app.AppCompatActivity
import dev.twoperson.messenger.App
import dev.twoperson.messenger.service.ConnectionService
import dev.twoperson.messenger.util.AppVisibility

/**
 * Shared activity lifecycle: every Knot screen keeps the engine in the foreground while it is
 * visible and lets it fall back to the short background grace period afterwards. The optional
 * foreground service is only started when the user enabled it in Settings.
 */
abstract class KnotActivity : AppCompatActivity() {

    val container get() = (application as App).container

    override fun onStart() {
        super.onStart()
        AppVisibility.onActivityStarted()
        container.engine.onForeground()
        ConnectionService.sync(this)
    }

    override fun onStop() {
        AppVisibility.onActivityStopped()
        container.engine.onBackground()
        super.onStop()
    }
}
