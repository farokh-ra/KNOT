package dev.twoperson.messenger.ui

/**
 * Telegram-style scroll behaviour, kept pure so it can be unit tested.
 *
 * A new message follows the conversation only while the reader is already at the bottom. If the
 * reader scrolled up, incoming messages never pull the list away from them; the jump button is
 * shown instead. A message the user sends always brings the list back to the latest bubble.
 */
object ChatScrollPolicy {

    fun shouldFollow(isAtBottom: Boolean, isOwnMessage: Boolean): Boolean =
        isAtBottom || isOwnMessage

    /** True when the list is close enough to the end to count as "reading the latest". */
    fun isAtBottom(lastVisiblePosition: Int, itemCount: Int): Boolean {
        if (itemCount <= 0) return true
        return lastVisiblePosition >= itemCount - 1
    }
}
