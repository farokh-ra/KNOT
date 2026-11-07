package dev.twoperson.messenger.ui

import android.content.Context
import android.text.format.DateFormat
import dev.twoperson.messenger.R
import java.util.Calendar
import java.util.Date

/** Locale-aware day/time labels shared by the chat list and conversation screen. */
object UiTime {

    fun sameDay(first: Long, second: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = second }
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    /** "Today", "Yesterday", or a localized date for a chat separator. */
    fun dayLabel(context: Context, timestamp: Long): String {
        val now = System.currentTimeMillis()
        if (sameDay(now, timestamp)) return context.getString(R.string.message_date_today)
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        if (sameDay(yesterday, timestamp)) return context.getString(R.string.message_date_yesterday)
        return dateFormat(context, timestamp, now)
    }

    /** Compact timestamp for a conversation row: time today, "Yesterday", otherwise a date. */
    fun conversationTime(context: Context, timestamp: Long): String {
        val now = System.currentTimeMillis()
        if (sameDay(now, timestamp)) {
            return DateFormat.getTimeFormat(context).format(Date(timestamp))
        }
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        if (sameDay(yesterday, timestamp)) return context.getString(R.string.message_date_yesterday)
        return dateFormat(context, timestamp, now)
    }

    fun clock(context: Context, timestamp: Long): String =
        DateFormat.getTimeFormat(context).format(Date(timestamp))

    private fun dateFormat(context: Context, timestamp: Long, reference: Long): String {
        val thenYear = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)
        val nowYear = Calendar.getInstance().apply { timeInMillis = reference }.get(Calendar.YEAR)
        val format = if (thenYear == nowYear) {
            DateFormat.getMediumDateFormat(context)
        } else {
            DateFormat.getDateFormat(context)
        }
        return format.format(Date(timestamp))
    }
}
