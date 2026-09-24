package metifikys.telegraph

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import metifikys.format.TopicFormatter

/**
 * A long-form topic as a telegra.ph page, and the channel post that points to it. Pure: no DB,
 * no network.
 *
 * The topic has [TopicFormatter.applyStrictLayout]'s shape — `{emoji} **Headline.**`, the body
 * paragraphs (blank-line separated), the `[label](url)` source link — and the page is: the
 * headline as the title, the picture as a `figure`, every body paragraph as a `p` (`**bold**` →
 * `strong`, `` `code` `` → `code`, `[l](u)` → `a`), then «Джерела» and the channel link.
 */
object TelegraphPage {

    /** A link of the page's «Джерела» list, or the channel link. */
    data class Link(val label: String, val url: String)

    /** Telegraph's title limit. */
    const val MAX_TITLE = 256

    fun readMoreLine(pageUrl: String): String = "⚡ [Читати повністю]($pageUrl)"

    /** The topic cut into its title line, body paragraphs and trailing source-link block (if any). */
    private data class Parts(val title: String?, val body: List<String>, val link: String?)

    private val BLOCK_BREAK = Regex("""\n[ \t\r]*\n\s*""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|${TopicFormatter.MARKDOWN_LINK.pattern}""", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("""\s+""")

    private fun parts(topic: String): Parts {
        val blocks = topic.trim().split(BLOCK_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return Parts(null, emptyList(), null)
        val title = blocks.first().takeIf { BOLD.containsMatchIn(it) }
        val rest = if (title != null) blocks.drop(1) else blocks
        val link = rest.lastOrNull()?.takeIf { TopicFormatter.MARKDOWN_LINK.matchEntire(it) != null }
        val body = if (link != null) rest.dropLast(1) else rest
        return Parts(title, body, link)
    }

    /** A title derived from a lead paragraph (no `**headline**`, e.g. tech bullets) stays this short. */
    const val MAX_LEAD_TITLE = 100

    private val SENTENCE_END = Regex("""[.!?…](?=\s|$)""")
    private val CLAUSE_BREAKS = listOf(" — ", " – ", ": ", "; ", ", ")

    /**
     * The page title: the `**headline**` without its trailing period; without one (a tech bullet is
     * just paragraphs) the first sentence of the lead paragraph, emoji dropped, cut at a clause
     * break to [MAX_LEAD_TITLE]. Whitespace collapsed, at most [MAX_TITLE] characters; never blank.
     */
    fun title(topic: String): String {
        val p = parts(topic)
        val raw = p.title?.let { BOLD.find(it)?.groupValues?.get(1)?.trim()?.removeSuffix(".") }
            ?: leadTitle(p.body.firstOrNull() ?: topic.trim().lineSequence().firstOrNull().orEmpty())
        val clean = raw.replace(WHITESPACE, " ").trim().ifEmpty { "Новина" }
        return if (clean.length <= MAX_TITLE) clean else clean.take(MAX_TITLE - 1).trimEnd() + "…"
    }

    private fun leadTitle(paragraph: String): String {
        val text = TopicFormatter.MARKDOWN_LINK.replace(paragraph) { it.groupValues[1] }
            .replace("*", "").replace("`", "").replace(WHITESPACE, " ").trim()
            .dropWhile { !it.isLetterOrDigit() && it != '«' && it != '"' }
        val sentence = SENTENCE_END.find(text)?.let { text.substring(0, it.range.first) } ?: text
        if (sentence.length <= MAX_LEAD_TITLE) return sentence
        val head = sentence.take(MAX_LEAD_TITLE)
        val clause = CLAUSE_BREAKS.maxOfOrNull { head.lastIndexOf(it) } ?: -1
        if (clause >= MAX_LEAD_TITLE / 3) return head.substring(0, clause).trimEnd()
        val word = head.lastIndexOf(' ')
        return (if (word > 0) head.substring(0, word) else head).trimEnd(',', ';', ':', ' ') + "…"
    }

    /**
     * The page body as Telegraph Nodes: the picture ([imageUrl], http(s) only), the body paragraphs,
     * «Джерела» ([sources], deduplicated by URL) and a line linking [channel].
     */
    fun content(topic: String, imageUrl: String?, sources: List<Link>, channel: Link?): JsonArray {
        val p = parts(topic)
        return buildJsonArray {
            if (imageUrl != null && isHttp(imageUrl)) {
                add(element("figure", listOf(element("img", attrs = mapOf("src" to imageUrl)))))
            }
            for (paragraph in p.body) add(element("p", inline(paragraph.replace(WHITESPACE, " "))))
            val links = sources.filter { isHttp(it.url) && it.label.isNotBlank() }.distinctBy { it.url }
            if (links.isNotEmpty()) {
                add(element("h4", listOf(JsonPrimitive("Джерела"))))
                add(element("ul", links.map { element("li", listOf(anchor(it))) }))
            }
            if (channel != null && isHttp(channel.url)) {
                add(element("p", listOf(JsonPrimitive("Більше новин: "), anchor(channel))))
            }
        }
    }

    /**
     * The `teaser` channel post: the title line (when the topic has a `**headline**`), the first
     * body paragraph, [readMoreLine] and the source link — the rest of the body lives on the page.
     * A topic with nothing to hide (a single paragraph) keeps its text and only gains the ⚡ line.
     */
    fun teaser(topic: String, pageUrl: String): String {
        val p = parts(topic)
        if (p.body.isEmpty() || (p.title == null && p.body.size < 2)) return withReadMore(topic, pageUrl)
        return listOfNotNull(p.title, p.body.first(), readMoreLine(pageUrl), p.link).joinToString("\n\n")
    }

    /** The `full` channel post: the topic as it is, with [readMoreLine] right before the source link. */
    fun withReadMore(topic: String, pageUrl: String): String {
        val p = parts(topic)
        val head = listOfNotNull(p.title) + p.body
        return (head + readMoreLine(pageUrl) + listOfNotNull(p.link)).joinToString("\n\n")
    }

    /** The source links of the topic's `[label](url)`s, in order. */
    fun topicLinks(topic: String): List<Link> =
        TopicFormatter.MARKDOWN_LINK.findAll(topic).map { Link(it.groupValues[1].trim(), it.groupValues[2].trim()) }.toList()

    private fun inline(text: String): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        var last = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > last) out += JsonPrimitive(text.substring(last, m.range.first))
            val bold = m.groups[1]
            val code = m.groups[2]
            val label = m.groups[3]
            val url = m.groups[4]
            out += when {
                bold != null -> element("strong", listOf(JsonPrimitive(bold.value)))
                code != null -> element("code", listOf(JsonPrimitive(code.value)))
                label != null && url != null && isHttp(url.value) -> anchor(Link(label.value, url.value))
                else -> JsonPrimitive(label?.value ?: m.value)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += JsonPrimitive(text.substring(last))
        return out
    }

    private fun anchor(link: Link): JsonElement = element("a", listOf(JsonPrimitive(link.label)), mapOf("href" to link.url))

    private fun element(tag: String, children: List<JsonElement> = emptyList(), attrs: Map<String, String> = emptyMap()): JsonElement =
        buildJsonObject {
            put("tag", tag)
            if (attrs.isNotEmpty()) putJsonObject("attrs") { attrs.forEach { (k, v) -> put(k, v) } }
            if (children.isNotEmpty()) putJsonArray("children") { children.forEach { add(it) } }
        }

    private fun isHttp(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")
}
