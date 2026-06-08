package metifikys.format

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicFormatterTest {


    @Test
    fun `splitTopics splits on delimiter`() {
        val summary = "•Topic 1 text\n•Topic 2 text\n•Topic 3 text"
        val topics = TopicFormatter.splitTopics(summary)
        assertEquals(3, topics.size)
        assertEquals("Topic 1 text", topics[0])
        assertEquals("Topic 2 text", topics[1])
        assertEquals("Topic 3 text", topics[2])
    }

    @Test
    fun `splitTopics returns single element when no delimiter`() {
        val summary = "Just one continuous summary"
        val topics = TopicFormatter.splitTopics(summary)
        assertEquals(1, topics.size)
        assertEquals("Just one continuous summary", topics[0])
    }

    @Test
    fun `splitTopics trims whitespace and filters empty blocks`() {
        val summary = "  •Topic 1   •Topic 2  "
        val topics = TopicFormatter.splitTopics(summary)
        assertEquals(2, topics.size)
        assertEquals("Topic 1", topics[0])
        assertEquals("Topic 2", topics[1])
    }

    // ── Strict topic layout tests ─────────────────────────────────────────────

    @Test
    fun `applyStrictTopicLayout emits three blocks separated by blank lines`() {
        // applyStrictTopicLayout passes the link through verbatim — label/URL truncation
        // is the upstream job of replaceSourceLabelWithArticleTitle / formatArticleLinkLabel.
        val link = "[Yoshi-P hoped we wouldn't notice, but Final Fan...](https://www.gamesradar.com/x)"

        val topic = "📚 **FF14 Evercold і скандинавська міфологія.** " +
                "Yoshi-P підтвердив, що наступний напрямок Final Fantasy XIV натхненний норвезькими міфами. " +
                link
        val out = TopicFormatter.applyStrictLayout(topic)
        assertEquals(
            "📚 **FF14 Evercold і скандинавська міфологія.**\n\n" +
                    "Yoshi-P підтвердив, що наступний напрямок Final Fantasy XIV натхненний норвезькими міфами.\n\n" +
                    link,
            out
        )
    }

    @Test
    fun `applyStrictTopicLayout works without an emoji prefix`() {
        val topic = "**Splatoon Raiders — 23 липня.** Дата релізу підтверджена. [Source](https://x/y)"
        val out = TopicFormatter.applyStrictLayout(topic)
        assertEquals(
            "**Splatoon Raiders — 23 липня.**\n\nДата релізу підтверджена.\n\n[Source](https://x/y)",
            out
        )
    }

    @Test
    fun `applyStrictTopicLayout omits body block when body is empty`() {
        val topic = "🎮 **Headline only.** [Label](https://x/y)"
        val out = TopicFormatter.applyStrictLayout(topic)
        assertEquals("🎮 **Headline only.**\n\n[Label](https://x/y)", out)
    }

    @Test
    fun `applyStrictTopicLayout does not double punctuate a headline ending in question mark`() {
        val topic = "🎮 **Чи буде продовження?** Розробник натякнув. [Source](https://x/y)"
        val out = TopicFormatter.applyStrictLayout(topic)
        assertTrue(out.startsWith("🎮 **Чи буде продовження?**"), "Expected unchanged headline punctuation, got: $out")
    }

    @Test
    fun `applyStrictTopicLayout adds period to a headline missing terminal punctuation`() {
        val topic = "🎮 **Headline without dot** Body sentence. [Src](https://x/y)"
        val out = TopicFormatter.applyStrictLayout(topic)
        assertTrue(out.startsWith("🎮 **Headline without dot.**"), "Expected period appended, got: $out")
    }

    @Test
    fun `applyStrictTopicLayout collapses multi-line body into one paragraph`() {
        val topic = "📅 **Release date.** First sentence.\nSecond sentence.\n  Third\tline. [Src](https://x/y)"
        val out = TopicFormatter.applyStrictLayout(topic)
        assertEquals(
            "📅 **Release date.**\n\nFirst sentence. Second sentence. Third line.\n\n[Src](https://x/y)",
            out
        )
    }

    @Test
    fun `applyStrictTopicLayout falls back when input has no bold headline`() {
        val topic = "Plain text without bold [Src](https://x/y)"
        val out = TopicFormatter.applyStrictLayout(topic)
        // Legacy fallback: link gets pushed onto its own line, nothing else changes.
        assertEquals("Plain text without bold\n\n[Src](https://x/y)", out)
    }

    @Test
    fun `applyStrictTopicLayout falls back when input has no link`() {
        val topic = "🎮 **Headline.** Body without a link."
        val out = TopicFormatter.applyStrictLayout(topic)
        // Legacy fallback runs: nothing to replace, returned as-is.
        assertEquals(topic, out)
    }

    @Test
    fun `applyStrictTopicLayout fallback truncates long link label when no bold headline`() {
        val topic =
            "🤝 США відмовилися від іранської пропозиції щодо відкриття Ормузької протоки в обмін на відкладення ядерних переговорів, пише Reuters. Канал переговорів через посередників не дав результату. " +
                    "[Іран пропонував відкрити Ормузьку протоку, а ядерні переговори відкласти, але США відмовилися](https://t.me/babel/83354)"
        val out = TopicFormatter.applyStrictLayout(topic)
        assertEquals(
            "🤝 США відмовилися від іранської пропозиції щодо відкриття Ормузької протоки в обмін на відкладення ядерних переговорів, пише Reuters. Канал переговорів через посередників не дав результату." +
                    "\n\n[Іран пропонував відкрити Ормузьку протоку, а ядерні переговори відкласти, але...](https://t.me/babel/83354)",
            out
        )
    }

    @Test
    fun `applyStrictTopicLayout fallback keeps short link label unchanged when no bold headline`() {
        val topic = "⚡ EVE Online повертає PLEX for Good як постійну благодійну ініціативу. Це не контентне оновлення для геймплею, але важлива зміна в екосистемі гри та її спільноти. " +
                "[EVE Online brings PLEX For Good charity platform back… for good](https://massivelyop.com/2026/05/01/eve-online-brings-plex-for-good-charity-platform-back-for-good/)"
        val out = TopicFormatter.applyStrictLayout(topic)
        assertEquals(
            "⚡ EVE Online повертає PLEX for Good як постійну благодійну ініціативу. Це не контентне оновлення для геймплею, але важлива зміна в екосистемі гри та її спільноти." +
                    "\n\n[EVE Online brings PLEX For Good charity platform back… for good](https://massivelyop.com/2026/05/01/eve-online-brings-plex-for-good-charity-platform-back-for-good/)",
            out
        )
    }

    // ── HTML rendering (toHtml) tests ─────────────────────────────────────────

    @Test
    fun `toHtml keeps a link label that contains nested brackets`() {
        // The production bug: a Reddit title ending in a `[P]` flair tag broke legacy Markdown.
        val url = "https://www.reddit.com/r/MachineLearning/comments/1tx6g3i/scrap_the_llms/"
        val topic = "🧠 **Заголовок.**\n\nТекст опису.\n\n" +
                "[Scrap the LLMs. Scoring 4.76% on the brand new ARC-3 … and zero AI tokens.[P]]($url)"
        val out = TopicFormatter.toHtml(topic)
        assertEquals(
            "🧠 <b>Заголовок.</b>\n\nТекст опису.\n\n" +
                    "<a href=\"$url\">Scrap the LLMs. Scoring 4.76% on the brand new ARC-3 … and zero AI tokens.[P]</a>",
            out
        )
        // No orphaned "(url)" — the exact corruption we are fixing.
        assertFalse(out.contains(".[P](https"), "Brackets must not collapse into an orphan link: $out")
    }

    @Test
    fun `toHtml converts bold headline and escapes special characters`() {
        val topic = "🎮 **Head.**\n\nFoo *bar* and a_b with AT&T <tag> a>b.\n\n[L](https://x/y)"
        val out = TopicFormatter.toHtml(topic)
        // **bold** becomes <b>; literal * and _ pass through; & < > are escaped; emitted tags are not.
        assertEquals(
            "🎮 <b>Head.</b>\n\nFoo *bar* and a_b with AT&amp;T &lt;tag&gt; a&gt;b.\n\n" +
                    "<a href=\"https://x/y\">L</a>",
            out
        )
    }

    @Test
    fun `toHtml renders a link even when the topic has no bold headline`() {
        val out = TopicFormatter.toHtml("Plain text without bold\n\n[Src](https://x/y)")
        assertEquals("Plain text without bold\n\n<a href=\"https://x/y\">Src</a>", out)
    }

    @Test
    fun `toHtml escapes the headline and body without a trailing link`() {
        val out = TopicFormatter.toHtml("**A & B** body <here>")
        assertEquals("<b>A &amp; B</b> body &lt;here&gt;", out)
    }

    @Test
    fun `toHtml keeps a URL containing parentheses intact`() {
        val url = "https://en.wikipedia.org/wiki/Bar_(baz)"
        val out = TopicFormatter.toHtml("[Foo]($url)")
        assertEquals("<a href=\"$url\">Foo</a>", out)
    }

    // ── extractUrls tests ─────────────────────────────────────────────────────

    @Test
    fun `extractUrls keeps a URL containing balanced parentheses`() {
        val url = "https://en.wikipedia.org/wiki/Bar_(baz)"
        assertEquals(setOf(url), TopicFormatter.extractUrls("Body. [Foo]($url)"))
    }
}