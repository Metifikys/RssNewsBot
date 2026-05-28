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
    fun `sendToChannel returns false when send fails`() {
        // Sender with a bogus token will get network/connection errors → false
        val unreachableSender = TelegramSender("invalid-token")
        val result = unreachableSender.sendToChannel("@test_channel", "test message")
        assertFalse(result, "Expected false when network call fails")
    }
}
