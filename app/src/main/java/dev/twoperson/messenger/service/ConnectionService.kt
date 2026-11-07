package dev.twoperson.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.twoperson.messenger.App
import dev.twoperson.messenger.R
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.engine.DirectKind
import dev.twoperson.messenger.ui.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the engine open for as long as the app is paired, so the direct connection is not
 * torn down when the activity is backgrounded or the screen turns off.
 *
 * This is only a lifecycle holder: it does no networking itself, holds no content and makes
 * no claim that the peer is reachable. The notification mirrors the real engine state.
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var foregroundStarted = false

    private val engine get() = (application as App).container.engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine.onForeground()
        statusJob = scope.launch {
            engine.status.collect { status ->
                if (foregroundStarted) publish(status)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!engine.isConfigured()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(engine.status.value)
        return START_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        engine.onBackground()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(status: ConnectionStatus) {
        val notification = buildNotification(status)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
            foregroundStarted = true
        } catch (e: Exception) {
            // If the platform refuses the foreground promotion the service still runs while
            // the app is visible; the engine keeps the same behaviour, only less durable.
            foregroundStarted = false
        }
    }

    private fun publish(status: ConnectionStatus) {
        val notification = buildNotification(status)
        try {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
        }
    }

    private fun buildNotification(status: ConnectionStatus): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (status.state) {
            ConnectionState.CONNECTED -> when (status.direct) {
                DirectKind.LOCAL_NETWORK -> getString(R.string.notif_text_connected_lan)
                else -> getString(R.string.notif_text_connected_internet)
            }

            ConnectionState.NOT_PAIRED -> getString(R.string.notif_text_not_paired)
            ConnectionState.UNABLE_TO_CONNECT -> getString(R.string.notif_text_unable)
            ConnectionState.DISCOVERING, ConnectionState.CONNECTING, ConnectionState.DISCONNECTED ->
                getString(R.string.notif_text_reconnecting)

            ConnectionState.IDLE -> getString(R.string.notif_text_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1001

        /** Idempotent: safe to call from every activity start. */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ConnectionService::class.java)
                )
            } catch (e: Exception) {
                // Background-start restrictions on recent Android versions can refuse this;
                // the engine then runs while an activity is visible, as before.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ConnectionService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
