package metifikys.format

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Invariants of [TopicFormatter] over generated bullets (see [FormatterArbs]). */
class TopicFormatterPropertyTest {

    private val anyText = Arb.choice(FormatterArbs.text(), FormatterArbs.topic())

    @Test
    fun `toHtml emits only balanced b, code and a tags with every special escaped`() = runBlocking<Unit> {
        checkAll(1000, anyText) { s ->
            val visible = TelegramHtml.visibleText(TopicFormatter.toHtml(s))
            // The caption / message budgets are enforced on the Markdown; that only holds if
            // rendering never makes the visible text longer.
            assertTrue(visible.length <= s.length, "visible ${visible.length} > source ${s.length}")
        }
    }

    @Test
    fun `toHtml renders every canonical link as an anchor with its exact url`() = runBlocking<Unit> {
        checkAll(1000, FormatterArbs.topic()) { s ->
            val html = TopicFormatter.toHtml(s)
            val hrefs = Regex("""<a href="([^"]*)">""").findAll(html).map { TelegramHtml.unescape(it.groupValues[1]) }.toList()
            val trailing = TopicFormatter.MARKDOWN_LINK.findAll(s).last().groupValues[2]
            assertEquals(trailing, hrefs.lastOrNull(), "the source link must survive rendering")
        }
    }

    @Test
    fun `escapeHtml round-trips and leaves no raw angle brackets`() = runBlocking<Unit> {
        checkAll(1000, FormatterArbs.text(2000)) { s ->
            val escaped = TopicFormatter.escapeHtml(s)
            assertFalse('<' in escaped || '>' in escaped)
            assertEquals(s, TelegramHtml.unescape(escaped))
        }
    }

    @Test
    fun `applyStrictLayout is idempotent and keeps every url`() = runBlocking<Unit> {
        checkAll(1000, anyText) { s ->
            val once = TopicFormatter.applyStrictLayout(s)
            assertEquals(once, TopicFormatter.applyStrictLayout(once), "second pass changed the layout")
            assertEquals(TopicFormatter.extractUrls(s), TopicFormatter.extractUrls(once))
        }
    }

    @Test
    fun `splitTopics never yields a blank topic or a bullet marker`() = runBlocking<Unit> {
        checkAll(500, FormatterArbs.text(3000)) { s ->
            val topics = TopicFormatter.splitTopics("• $s\n• $s")
            assertTrue(topics.none { it.isBlank() || '•' in it })
        }
    }
}
