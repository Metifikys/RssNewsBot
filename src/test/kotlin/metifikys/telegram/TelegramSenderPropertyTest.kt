package metifikys.telegram

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import metifikys.format.FormatterArbs
import metifikys.format.TelegramHtml
import metifikys.format.TopicDecorator
import metifikys.format.TopicFormatter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Invariants of [TelegramSender.chunkMessage] / [TelegramSender.truncateForCaption] and the send pipeline. */
class TelegramSenderPropertyTest {

    private val sender = TelegramSender("token")
    private val anyText = Arb.choice(FormatterArbs.text(), FormatterArbs.topic())

    private fun assertChunks(s: String, n: Int, chunks: List<String>) {
        for (c in chunks) {
            assertTrue(c.isNotEmpty() || s.isEmpty(), "empty chunk")
            assertTrue(c.length <= n, "chunk ${c.length} > $n")
            assertTrue(TelegramHtml.surrogatesPaired(c), "chunk splits a surrogate pair")
        }
        // Only line breaks at chunk boundaries may go; every other char survives, in order.
        assertEquals(s.replace("\n", ""), chunks.joinToString("").replace("\n", ""))
        // A link that fits in one message is never cut in half.
        for (m in TopicFormatter.MARKDOWN_LINK.findAll(s)) {
            if ('\n' in m.value || m.value.length > n) continue
            assertTrue(chunks.any { m.value in it }, "link cut across chunks: ${m.value}")
        }
    }

    @Test
    fun `chunkMessage respects the 4096 limit without losing text or cutting links`() = runBlocking<Unit> {
        checkAll(1000, anyText) { s -> assertChunks(s, 4096, sender.chunkMessage(s)) }
    }

    @Test
    fun `chunkMessage holds for small limits too`() = runBlocking<Unit> {
        checkAll(1000, FormatterArbs.text(1500), Arb.int(20..600)) { s, n -> assertChunks(s, n, sender.chunkMessage(s, n)) }
    }

    @Test
    fun `truncateForCaption fits 1024, keeps a prefix and never leaves half a link`() = runBlocking<Unit> {
        checkAll(1000, anyText) { s ->
            val out = sender.truncateForCaption(s)
            assertTrue(out.length <= 1024, "caption ${out.length} > 1024")
            if (s.length <= 1024) {
                assertEquals(s, out)
                return@checkAll
            }
            assertTrue(out.endsWith("…"))
            assertTrue(s.startsWith(out.dropLast(1)), "truncation must keep a prefix of the text")
            assertTrue(TelegramHtml.surrogatesPaired(out), "caption splits a surrogate pair")
            // Brackets only occur inside generated links, so every `](` must belong to a whole link.
            assertEquals(Regex("""]\(""").findAll(out).count(), TopicFormatter.MARKDOWN_LINK.findAll(out).count(), out.takeLast(120))
        }
    }

    @Test
    fun `decorated caption and message pipelines stay within Telegram's visible limits`() = runBlocking<Unit> {
        checkAll(500, anyText, FormatterArbs.footer()) { topic, footer ->
            val caption = TopicDecorator.decorate(topic, footer, TopicDecorator.CAPTION_BUDGET)
            val html = TopicFormatter.toHtml(sender.truncateForCaption(caption))
            assertTrue(TelegramHtml.visibleText(html).length <= 1024)

            val message = TopicDecorator.decorate(topic, footer, TopicDecorator.TEXT_BUDGET)
            for (chunk in sender.chunkMessage(message)) {
                assertTrue(TelegramHtml.visibleText(TopicFormatter.toHtml(chunk)).length <= 4096)
            }
        }
    }
}
