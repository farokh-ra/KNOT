package dev.twoperson.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyContentTest {

    @Test
    fun collapsesWhitespaceIntoOneLine() {
        assertEquals("hello world", ReplyContent.snippet("  hello\n\n  world \t"))
    }

    @Test
    fun emptyBodyProducesAnEmptyQuote() {
        assertEquals("", ReplyContent.snippet("   \n "))
    }

    @Test
    fun fileQuoteUsesTheFileName() {
        assertEquals(
            "report.pdf",
            ReplyContent.snippet("ignored", isFile = true, fileName = "report.pdf")
        )
    }

    @Test
    fun longTextIsTruncatedOnCodePointBoundaries() {
        val quoted = ReplyContent.snippet("🌍".repeat(200))
        assertTrue(quoted.endsWith("…"))
        assertTrue(quoted.codePointCount(0, quoted.length) <= ReplyContent.MAX_SNIPPET_CODE_POINTS + 1)
        // No broken surrogate pair at the cut.
        assertTrue(quoted.dropLast(1).codePointCount(0, quoted.dropLast(1).length) > 0)
    }

    @Test
    fun shortTextIsKeptExactly() {
        val body = "سلام، این یک پیام کوتاه است."
        assertEquals(body, ReplyContent.snippet(body))
    }
}
