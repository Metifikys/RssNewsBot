package metifikys.format

import metifikys.format.FooterLines.Slot
import java.net.URI

/**
 * Posts as Bot API 10.1 rich messages (HTML, built with [RichHtml]). Pure: no DB, no network.
 */
object RichPost {

    /**
     * `post.longForm.rich`: a long-form topic as one rich message — the headline as a heading, the
     * picture, every body paragraph, the footer lines that carry facts (persona, badges, intro,
     * anti-bubble, the other channel, the story lines; [Slot.ALSO] is left out, its outlets are in
     * the sources), the sources collapsed in «🔗 Джерела · N» with a button to the post's own
     * article, and the hashtag line last as plain text.
     *
     * [sources] are the post's links first, then the event's other articles; the topic's trailing
     * `[label](url)` is the [primary] button.
     */
    fun longForm(topic: String, footer: FooterLines, image: String?, sources: List<RichHtml.Link>): String {
        val parts = TopicParts.of(topic)
        val primary = parts.link?.let { TopicFormatter.MARKDOWN_LINK.matchEntire(it) }
            ?.let { RichHtml.Link(it.groupValues[1].trim(), it.groupValues[2].trim()) }
        val links = (listOfNotNull(primary) + sources).filter { it.url.startsWith("http") }.distinctBy { it.url }
        return buildString {
            parts.headline?.let { append(RichHtml.heading(2, it)) }
            image?.let { append(RichHtml.figure(it)) }
            for (paragraph in parts.body) append(RichHtml.paragraph(paragraph))
            for (slot in FACT_SLOTS) for (line in footer.lines(slot)) append(RichHtml.paragraph(line))
            if (links.isNotEmpty()) {
                val button = primary?.let { RichHtml.Link(readOn(it.url), it.url) }
                append(RichHtml.details("🔗 Джерела · ${links.size}", RichHtml.linkList(links) + RichHtml.buttonRow(listOfNotNull(button))))
            }
            for (line in footer.lines(Slot.HASHTAGS)) append(RichHtml.paragraph(line))
        }
    }

    /**
     * `weekly.rich`: the roundup as one rich message — [title] as the heading, [photos] as a
     * collage, [bullets] as a numbered list, every line of [blocks] (the leaderboard, a sparkline
     * barometer) as a paragraph, the chart uploaded with the message under [chartId] with its
     * [chartCaption], and the [hashtag] as plain text.
     */
    fun weekly(
        title: String,
        photos: List<String>,
        bullets: List<String>,
        blocks: List<String>,
        chartId: String?,
        chartCaption: String?,
        hashtag: String?
    ): String = buildString {
        append(RichHtml.heading(2, title))
        append(RichHtml.collage(photos))
        append(RichHtml.list(bullets.map { RichHtml.inline(it) }))
        for (block in blocks) for (line in block.lines().map { it.trim() }.filter { it.isNotEmpty() }) append(RichHtml.paragraph(line))
        if (chartId != null) append(RichHtml.figure(RichHtml.attached(chartId), chartCaption))
        hashtag?.takeIf { it.isNotBlank() }?.let { append(RichHtml.paragraph(it)) }
    }

    /** The footer slots a rich post shows as paragraphs (all but the also line and the hashtags). */
    private val FACT_SLOTS = Slot.entries - setOf(Slot.ALSO, Slot.HASHTAGS)

    /** `Читати на kotaku.com` — the button to the post's own article. */
    internal fun readOn(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")
        return if (host.isNullOrBlank()) "Читати джерело" else "Читати на $host"
    }
}
