package dev.twoperson.messenger.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether at least one Knot activity is started. Incoming-message notifications are
 * posted only while the user is outside the app, so the app never notifies about a message
 * it is already showing on screen.
 */
object AppVisibility {

    private val startedActivities = AtomicInteger(0)

    val isVisible: Boolean get() = startedActivities.get() > 0

    fun onActivityStarted() {
        startedActivities.incrementAndGet()
    }

    fun onActivityStopped() {
        startedActivities.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }
}
