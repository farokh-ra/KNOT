package dev.twoperson.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTest {

    @Test
    fun codeRoundTrips() {
        val secret = Invite.newSecret()
        val code = Invite.encode(secret)
        assertTrue(code.startsWith("TPM1-"))
        assertArrayEquals(secret, Invite.decode(code))
    }

    @Test
    fun decodeToleratesCopyArtifacts() {
        val secret = Invite.newSecret()
        val code = Invite.encode(secret)
        val messy = "  " + code.lowercase().replace("-", " ") + "\n"
        assertArrayEquals(secret, Invite.decode(messy))
    }

    @Test
    fun rejectsMalformedCodes() {
        assertNull(Invite.decode(""))
        assertNull(Invite.decode("hello"))
        assertNull(Invite.decode("TPM1-"))
        // One chunk missing: decoded length no longer matches 32 bytes.
        assertNull(Invite.decode("TPM1-AAAA-AAAA"))
        assertNull(Invite.decode("TPM1-" + "A".repeat(51)))
        assertFalse(Invite.isValid("TPM2-AAA"))
    }

    @Test
    fun topicsDifferForDifferentSecretsAndAreStable() {
        val a = Invite.newSecret()
        val b = Invite.newSecret()
        assertArrayEquals(Invite.topic(a), Invite.topic(a))
        assertNotEquals(Invite.topic(a).toList(), Invite.topic(b).toList())
        assertEquals(20, Invite.topic(a).size)
    }

    @Test
    fun topicIsDerivedAndDistinctFromTheSecret() {
        val secret = Invite.newSecret()
        val topic = Invite.topic(secret)
        assertEquals(20, topic.size)
        assertFalse(topic.contentEquals(secret.copyOfRange(0, 20)))
        assertFalse(topic.contentEquals(Invite.authKey(secret).copyOfRange(0, 20)))
    }
}
