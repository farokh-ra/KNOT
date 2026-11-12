package dev.twoperson.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the always-on connection after a reboot or an app update. Without this the engine
 * would stay down until the user opened the app, which would silently stop realtime delivery
 * for a paired device.
 *
 * Starting a foreground service from these broadcasts is an allowed Android exemption. The
 * service still verifies that a contact is paired and stops itself otherwise.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> ConnectionService.ensureRunning(context)
        }
    }
}
