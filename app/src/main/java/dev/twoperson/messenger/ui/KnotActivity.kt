package dev.twoperson.messenger.ui

import androidx.appcompat.app.AppCompatActivity
import dev.twoperson.messenger.App
import dev.twoperson.messenger.service.ConnectionService
import dev.twoperson.messenger.util.AppVisibility

/**
 * Shared activity lifecycle: every Knot screen keeps the engine in the foreground while it is
 * visible and revalidates the direct link when the user comes back. The always-on foreground
 * service keeps the same engine running after the last screen closes.
 */
abstract class KnotActivity : AppCompatActivity() {

    val container get() = (application as App).container

    override fun onStart() {
        super.onStart()
        val returning = !AppVisibility.isVisible
        AppVisibility.onActivityStarted()
        container.engine.onForeground()
        if (returning) container.engine.onUiVisible()
        ConnectionService.sync(this)
    }

    override fun onStop() {
        AppVisibility.onActivityStopped()
        if (!AppVisibility.isVisible) container.engine.onUiHidden()
        container.engine.onBackground()
        super.onStop()
    }
}
