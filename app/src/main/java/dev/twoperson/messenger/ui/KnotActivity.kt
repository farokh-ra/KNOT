package dev.twoperson.messenger.ui

import android.os.Bundle
import android.view.WindowManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenSecurity()
    }

    override fun onStart() {
        super.onStart()
        val returning = !AppVisibility.isVisible
        AppVisibility.onActivityStarted()
        container.engine.onForeground()
        if (returning) container.engine.onUiVisible()
        ConnectionService.sync(this)
    }

    override fun onResume() {
        super.onResume()
        // The switch lives in Settings inside this task, so it must take effect without a
        // restart as soon as the user comes back to a screen.
        applyScreenSecurity()
    }

    override fun onStop() {
        AppVisibility.onActivityStopped()
        if (!AppVisibility.isVisible) container.engine.onUiHidden()
        container.engine.onBackground()
        super.onStop()
    }

    /** Blocks screenshots and the recents thumbnail when the user enabled screen security. */
    protected fun applyScreenSecurity() {
        if (container.settings.current.screenSecurity) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
