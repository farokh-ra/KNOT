package dev.twoperson.messenger.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Message
import dev.twoperson.messenger.data.MessageKind
import dev.twoperson.messenger.data.MessageRepository
import dev.twoperson.messenger.ui.ChatActivity
import dev.twoperson.messenger.util.AppVisibility

/**
 * Notification policy:
 *  - the foreground service uses a silent, minimum-importance channel; it never alerts;
 *  - a normal notification is posted only when a real incoming message (or a completed
 *    incoming file) was stored while no Knot screen is open;
 *  - the permission is requested once, never on every launch.
 */
object MessageNotifications {

    const val SERVICE_CHANNEL_ID = "connection_v3"
    const val MESSAGE_CHANNEL_ID = "messages_v1"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val MESSAGE_NOTIFICATION_ID = 1002

    private const val PREFS = "notification_prefs"
    private const val KEY_ASKED = "permission_asked"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            val service = NotificationChannel(
                SERVICE_CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(service)
        }

        if (manager.getNotificationChannel(MESSAGE_CHANNEL_ID) == null) {
            val messages = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                context.getString(R.string.notif_messages_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_messages_channel_desc)
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(messages)
        }
    }

    fun shouldAskPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (permissionGranted(context)) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)
    }

    fun markPermissionAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun canPost(context: Context): Boolean =
        permissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun showIncoming(context: Context, message: Message, peerName: String?, unreadCount: Int = 1) {
        if (!canPost(context)) return
        val appContext = context.applicationContext
        ensureChannels(appContext)
        val title = peerName?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.notif_new_message)
        val body = when (message.kind) {
            MessageKind.FILE -> appContext.getString(
                R.string.notif_new_file,
                message.fileName ?: message.body
            )

            MessageKind.TEXT -> message.body
        }
        val unread = unreadCount.coerceAtLeast(1)
        val openChat = PendingIntent.getActivity(
            appContext,
            10,
            Intent(appContext, ChatActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val publicVersion = NotificationCompat.Builder(appContext, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(appContext.getString(R.string.notif_new_message))
            .setContentText(appContext.getString(R.string.notif_private_preview))
            .setAutoCancel(true)
            .build()

        val notification = NotificationCompat.Builder(appContext, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openChat)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setNumber(unread)
            .setSubText(
                if (unread > 1) appContext.getString(R.string.notif_unread_count, unread) else null
            )
            .setWhen(message.createdAt)
            .setShowWhen(true)
            .build()
        try {
            NotificationManagerCompat.from(appContext)
                .notify(MESSAGE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between the checks; never crash over a notification.
        }
    }

    fun dismissIncoming(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(MESSAGE_NOTIFICATION_ID)
        } catch (e: Exception) {
        }
    }
}

/** Called by the engine after a new message is durably stored. */
class IncomingNotifier(
    context: Context,
    private val repository: MessageRepository
) {
    private val appContext = context.applicationContext

    suspend fun onIncoming(message: Message) {
        if (AppVisibility.isVisible) return
        val peer = repository.peer()
        val name = peer?.label?.takeIf { it.isNotBlank() }
            ?: peer?.displayName?.takeIf { it.isNotBlank() }
        MessageNotifications.showIncoming(
            appContext,
            message,
            name,
            repository.unreadCount.value
        )
    }
}
