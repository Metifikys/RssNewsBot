package metifikys.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramSenderTest {

    private val sender = TelegramSender("token")

    @Test
    fun `chunkMessage returns single chunk when under limit`() {
        val text = "Hello world"
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `chunkMessage returns single chunk when exactly at limit`() {
        val text = "a".repeat(4096)
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(1, chunks.size)
        assertEquals(4096, chunks[0].length)
    }

    @Test
    fun `chunkMessage splits at newlines when over limit`() {
        val line1 = "a".repeat(3000)
        val line2 = "b".repeat(3000)
        val text = "$line1\n$line2"
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(2, chunks.size)
        assertEquals(line1, chunks[0])
        assertEquals(line2, chunks[1])
    }

    @Test
    fun `chunkMessage hard-splits single line longer than maxLen`() {
        val text = "x".repeat(5000)
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(2, chunks.size)
        assertEquals(4096, chunks[0].length)
        assertEquals(904, chunks[1].length)
    }

    @Test
    fun `chunkMessage preserves all content`() {
        val lines = (1..100).map { "Line $it: ${"x".repeat(60)}" }
        val text = lines.joinToString("\n")
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        val rejoined = chunks.joinToString("\n")
        assertEquals(text, rejoined)
    }

    @Test
    fun `truncateForCaption leaves text under the limit unchanged`() {
        val text = "a short caption"
        assertEquals(text, sender.truncateForCaption(text))
    }

    @Test
    fun `truncateForCaption does not split an emoji surrogate pair (BUG-009)`() {
        // Place 🔥 (a surrogate pair) straddling the 1023 cut boundary.
        val text = "a".repeat(1022) + "🔥" + "b".repeat(50)
        val out = sender.truncateForCaption(text)
        val body = out.removeSuffix("…")
        assertTrue(
            body.isEmpty() || !body.last().isHighSurrogate(),
            "truncation left an unpaired high surrogate: invalid UTF-16"
        )
        assertTrue(out.length <= 1024)
    }

    @Test
    fun `truncateForCaption does not cut inside a markdown link (BUG-010)`() {
        val head = "x".repeat(1000)
        val link = "[Some very long article title](https://example.com/some/really/long/path?a=b)"
        val text = "$head $link tail"
        val out = sender.truncateForCaption(text)
        val body = out.removeSuffix("…")
        val open = body.lastIndexOf('[')
        if (open >= 0) {
            assertTrue(body.indexOf(')', open) > open, "left a half-open markdown link: $body")
        }
        assertTrue(out.length <= 1024)
    }

    @Test
    fun `truncateForCaption keeps a 1200-char topic within the caption limit`() {
        val text = "т".repeat(1150) + " [дж](https://ex.com/a)"
        val out = sender.truncateForCaption(text)
        assertTrue(out.length <= 1024)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `sendToChannel returns no refs when send fails`() {
        // Sender with a bogus token will get network/connection errors → no delivered refs
        val unreachableSender = TelegramSender("invalid-token")
        val result = unreachableSender.sendToChannel("@test_channel", "test message")
        assertTrue(result.isEmpty(), "Expected no refs when network call fails")
    }
}
