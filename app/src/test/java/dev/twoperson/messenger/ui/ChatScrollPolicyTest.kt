package dev.twoperson.messenger.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollPolicyTest {

    @Test
    fun followsIncomingMessagesOnlyWhileReadingTheLatest() {
        assertTrue(ChatScrollPolicy.shouldFollow(isAtBottom = true, isOwnMessage = false))
        assertFalse(ChatScrollPolicy.shouldFollow(isAtBottom = false, isOwnMessage = false))
    }

    @Test
    fun ownMessageAlwaysBringsTheConversationBack() {
        assertTrue(ChatScrollPolicy.shouldFollow(isAtBottom = false, isOwnMessage = true))
        assertTrue(ChatScrollPolicy.shouldFollow(isAtBottom = true, isOwnMessage = true))
    }

    @Test
    fun emptyListCountsAsAtBottom() {
        assertTrue(ChatScrollPolicy.isAtBottom(lastVisiblePosition = -1, itemCount = 0))
    }

    @Test
    fun lastVisibleItemIsTheBottom() {
        assertTrue(ChatScrollPolicy.isAtBottom(lastVisiblePosition = 9, itemCount = 10))
        assertFalse(ChatScrollPolicy.isAtBottom(lastVisiblePosition = 8, itemCount = 10))
    }
}
