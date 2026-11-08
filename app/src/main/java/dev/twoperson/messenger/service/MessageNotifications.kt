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
import dev.twoperson.messenger.data.SettingsPolicy
import dev.twoperson.messenger.data.SettingsStore
import dev.twoperson.messenger.ui.ChatActivity
import dev.twoperson.messenger.ui.HomeActivity
import dev.twoperson.messenger.util.AppVisibility

/**
 * Notification policy:
 *  - the opt-in background service uses a silent, minimum-importance channel; it never alerts;
 *  - a normal notification is posted only when a real incoming message (or a completed
 *    incoming file) was stored while no Knot screen is open;
 *  - each conversation gets its own notification, so tapping it opens the right chat;
 *  - previews can be hidden in Settings, and the lockscreen public version is always redacted;
 *  - the permission is requested once, never on every launch.
 */
object MessageNotifications {

    const val SERVICE_CHANNEL_ID = "connection_v3"
    const val MESSAGE_CHANNEL_ID = "messages_v1"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val MESSAGE_SUMMARY_ID = 1002

    private const val MESSAGE_ID_BASE = 2000
    private const val PREFS = "notification_prefs"
    private const val KEY_ASKED = "permission_asked"
    private const val KEY_POSTED = "posted_message_ids"
    private const val GROUP_KEY = "dev.twoperson.messenger.MESSAGES"
    private val lock = Any()

    /** Stable per-contact notification id; also used as the PendingIntent request code. */
    fun contactNotificationId(contactId: String): Int =
        MESSAGE_ID_BASE + (contactId.hashCode() and 0x3FFFFFFF)

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

    /**
     * Posts (or updates) the alert for one conversation and the small group summary. The
     * caller has already decided that no Knot screen is visible.
     */
    fun showIncoming(
        context: Context,
        message: Message,
        contactName: String?,
        unreadCount: Int = 1,
        totalUnread: Int = unreadCount,
        showPreviews: Boolean = true
    ) {
        if (!canPost(context)) return
        val appContext = context.applicationContext
        ensureChannels(appContext)
        val appName = appContext.getString(R.string.app_name)
        val title = SettingsPolicy.notificationTitle(showPreviews, contactName, appName)
        val preview = when (message.kind) {
            MessageKind.FILE -> appContext.getString(
                R.string.notif_new_file,
                message.fileName ?: message.body
            )

            MessageKind.TEXT -> message.body
        }
        val privateText = if (message.kind == MessageKind.FILE) {
            appContext.getString(R.string.notif_private_file)
        } else {
            appContext.getString(R.string.notif_private_preview)
        }
        val body = SettingsPolicy.notificationBody(showPreviews, preview, privateText)
        val unread = unreadCount.coerceAtLeast(1)
        val notificationId = contactNotificationId(message.contactId)

        val openChat = PendingIntent.getActivity(
            appContext,
            notificationId,
            Intent(appContext, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CONTACT_ID, message.contactId)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val publicVersion = NotificationCompat.Builder(appContext, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(appName)
            .setContentText(privateText)
            .setAutoCancel(true)
            .build()

        val notification = NotificationCompat.Builder(appContext, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setColor(ContextCompat.getColor(appContext, R.color.brand_primary))
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
                if (unread > 1) {
                    appContext.resources.getQuantityString(
                        R.plurals.notif_unread_count,
                        unread,
                        unread
                    )
                } else {
                    null
                }
            )
            .setWhen(message.createdAt)
            .setShowWhen(true)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
            rememberPosted(appContext, notificationId)
            if (totalUnread > 1) postSummary(appContext, totalUnread)
        } catch (e: SecurityException) {
            // Permission was revoked between the checks; never crash over a notification.
        }
    }

    private fun postSummary(context: Context, totalUnread: Int) {
        val openApp = PendingIntent.getActivity(
            context,
            MESSAGE_SUMMARY_ID,
            Intent(context, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setColor(ContextCompat.getColor(context, R.color.brand_primary))
            .setContentTitle(context.getString(R.string.notif_summary_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.notif_summary_text,
                    totalUnread,
                    totalUnread
                )
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(MESSAGE_SUMMARY_ID, summary)
        } catch (e: SecurityException) {
        }
    }

    private fun rememberPosted(context: Context, id: Int) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
            posted.add(id.toString())
            prefs.edit().putStringSet(KEY_POSTED, posted).apply()
        }
    }

    /** Dismisses one conversation's notification, or every posted message alert. */
    fun dismissIncoming(context: Context, contactId: String? = null) {
        val appContext = context.applicationContext
        val manager = NotificationManagerCompat.from(appContext)
        if (contactId != null) {
            val id = contactNotificationId(contactId)
            try {
                manager.cancel(id)
            } catch (e: Exception) {
            }
            synchronized(lock) {
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
                posted.remove(id.toString())
                // The summary carries a total that is now stale; drop it. The remaining
                // per-conversation alerts stay visible, and the next arrival reposts a
                // summary with the correct count.
                try {
                    manager.cancel(MESSAGE_SUMMARY_ID)
                } catch (e: Exception) {
                }
                prefs.edit().putStringSet(KEY_POSTED, posted).apply()
            }
            return
        }
        synchronized(lock) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty()
            for (value in posted) {
                value.toIntOrNull()?.let { id ->
                    try {
                        manager.cancel(id)
                    } catch (e: Exception) {
                    }
                }
            }
            prefs.edit().remove(KEY_POSTED).apply()
        }
        try {
            manager.cancel(MESSAGE_SUMMARY_ID)
        } catch (e: Exception) {
        }
    }
}

/** Called by the engine after a new message is durably stored. */
class IncomingNotifier(
    context: Context,
    private val repository: MessageRepository,
    private val settings: SettingsStore
) {
    private val appContext = context.applicationContext

    suspend fun onIncoming(message: Message) {
        if (AppVisibility.isVisible) return
        val contact = repository.contact(message.contactId)
        val chat = repository.chats.value.firstOrNull { it.contact.id == message.contactId }
        MessageNotifications.showIncoming(
            appContext,
            message,
            contact?.name,
            chat?.unreadCount ?: 1,
            repository.totalUnread.value,
            settings.current.notificationPreviews
        )
    }
}
