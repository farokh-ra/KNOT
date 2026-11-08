package dev.twoperson.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactModelTest {

    private fun contact(
        id: String = "c1",
        label: String? = null,
        note: String? = null,
        deviceId: String? = null,
        publicKey: String? = null,
        displayName: String? = null,
        pairedAt: Long = 0L,
        createdAt: Long = 1_000L,
        hasInvitation: Boolean = false
    ) = Contact(
        id = id,
        label = label,
        note = note,
        deviceId = deviceId,
        publicKeyHex = publicKey,
        displayName = displayName,
        pairedAt = pairedAt,
        createdAt = createdAt,
        hasInvitation = hasInvitation
    )

    @Test
    fun `sanitizeLabel trims and collapses whitespace`() {
        assertEquals("Alex Smith", ContactNames.sanitizeLabel("  Alex   Smith  "))
    }

    @Test
    fun `sanitizeLabel returns null for blank input`() {
        assertNull(ContactNames.sanitizeLabel(null))
        assertNull(ContactNames.sanitizeLabel("   "))
    }

    @Test
    fun `sanitizeLabel caps at the maximum length`() {
        val label = ContactNames.sanitizeLabel("a".repeat(120))
        assertEquals(ContactNames.MAX_LABEL_LENGTH, label?.length)
    }

    @Test
    fun `sanitizeNote caps at the maximum length and cleans whitespace`() {
        val note = ContactNames.sanitizeNote("  one   two \n three  ")
        assertEquals("one two three", note)
        assertEquals(
            ContactNames.MAX_NOTE_LENGTH,
            ContactNames.sanitizeNote("n".repeat(400))?.length
        )
    }

    @Test
    fun `initial ignores punctuation and falls back to K`() {
        assertEquals("A", ContactNames.initial("  alex"))
        assertEquals("B", ContactNames.initial("!!! bob"))
        assertEquals("1", ContactNames.initial("123"))
        assertEquals("K", ContactNames.initial(""))
        assertEquals("K", ContactNames.initial(null))
    }

    @Test
    fun `name prefers the local label then the device name`() {
        assertEquals("Local", contact(label = "Local", displayName = "Device").name)
        assertEquals("Device", contact(displayName = "Device").name)
        assertEquals("Unnamed contact", contact().name)
        assertEquals("Device", contact(label = "   ", displayName = "Device").name)
    }

    @Test
    fun `isPaired requires a timestamp and a pinned identity`() {
        assertFalse(contact(pairedAt = 10L).isPaired)
        assertFalse(contact(pairedAt = 0L, publicKey = "abc").isPaired)
        assertTrue(contact(pairedAt = 10L, publicKey = "abc").isPaired)
        assertTrue(contact(pairedAt = 10L, deviceId = "device").isPaired)
    }

    @Test
    fun `isOnKnot covers invitations pairings and devices`() {
        assertFalse(contact().isOnKnot)
        assertTrue(contact(hasInvitation = true).isOnKnot)
        assertTrue(contact(pairedAt = 10L, publicKey = "pk").isOnKnot)
        assertTrue(contact(deviceId = "device").isOnKnot)
    }

    @Test
    fun `deviceLine only appears when the device name differs`() {
        assertNull(contact(label = "Alex", displayName = "Alex").deviceLine)
        assertEquals("Pixel", contact(label = "Alex", displayName = "Pixel").deviceLine)
        assertNull(contact(label = "Alex", displayName = "  ").deviceLine)
    }

    @Test
    fun `search matches label display name note and device line`() {
        val target = contact(
            label = "Alex",
            displayName = "Pixel 9",
            note = "neighbour",
            deviceId = "abc"
        )
        assertTrue(ContactNames.matches(target, "alex"))
        assertTrue(ContactNames.matches(target, "PIXEL"))
        assertTrue(ContactNames.matches(target, "neigh"))
        assertTrue(ContactNames.matches(target, "pixel 9"))
        assertFalse(ContactNames.matches(target, "unrelated"))
        assertTrue(ContactNames.matches(target, "   "))
    }

    @Test
    fun `chat summary sorts by message then pairing then creation`() {
        val message = Message(
            id = "m1",
            contactId = "c1",
            direction = MessageDirection.INCOMING,
            body = "hi",
            createdAt = 5_000L,
            sequence = 1,
            state = MessageState.DELIVERED,
            senderId = "peer"
        )
        assertEquals(5_000L, ChatSummary(contact(pairedAt = 9_000L), message, 1).sortTime)
        assertEquals(9_000L, ChatSummary(contact(pairedAt = 9_000L), null, 0).sortTime)
        assertEquals(1_000L, ChatSummary(contact(), null, 0).sortTime)
    }

    @Test
    fun `chat preview formats outgoing text file and empty states`() {
        val outgoing = Message(
            id = "m1",
            contactId = "c1",
            direction = MessageDirection.OUTGOING,
            body = "hello",
            createdAt = 1L,
            sequence = 1,
            state = MessageState.DELIVERED,
            senderId = "local"
        )
        val incomingFile = Message(
            id = "m2",
            contactId = "c1",
            direction = MessageDirection.INCOMING,
            body = "report.pdf",
            createdAt = 2L,
            sequence = 2,
            state = MessageState.DELIVERED,
            senderId = "peer",
            kind = MessageKind.FILE,
            fileName = "report.pdf"
        )
        assertEquals(
            "You: hello",
            ChatPreview.build(outgoing, "You: %1\$s", "File · %1\$s", "No messages")
        )
        assertEquals(
            "File · report.pdf",
            ChatPreview.build(incomingFile, "You: %1\$s", "File · %1\$s", "No messages")
        )
        assertEquals(
            "No messages",
            ChatPreview.build(null, "You: %1\$s", "File · %1\$s", "No messages")
        )
    }
}
