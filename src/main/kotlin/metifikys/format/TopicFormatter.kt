package metifikys.format

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.model.Article

private val logger = KotlinLogging.logger {}

/**
 * Pure helpers for turning raw LLM digest text into Telegram-ready topics.
 *
 * Strict three-block layout:
 *
 *     {emoji} **{headline}.**
 *
 *     {body}
 *
 *     [{label}]({url})
 *
 * Falls back to legacy `replace("[", "\n[")` when the topic does not match the
 * `**bold** … [link](url)` shape, so degenerate LLM output is never made worse.
 */
object TopicFormatter {

    private val TOPIC_SHAPE = Regex(
        """^\s*(?<emoji>\S+)?\s*\*\*(?<headline>.+?)\*\*\s*(?<body>.*?)\s*(?<link>\[[^\]]+]\([^)]+\))\s*$""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val HEADLINE_TERMINALS = setOf('.', '!', '?', '…', ':')

    // URL group tolerates one level of balanced parentheses inside the URL itself
    // (e.g. Wikipedia/MDN `..._(disambiguation)` links) so extractUrls no longer truncates.
    private const val URL_BODY = """[^()]*(?:\([^()]*\)[^()]*)*"""

    private val URL_REGEX = Regex("""\[.*?]\(($URL_BODY)\)""")

    private val SOURCE_LINK_REGEX = Regex("""\[джерело]\((.*?)\)""")

    // Label tolerates one level of nested brackets (e.g. a Reddit flair tag `[P]` at the end of a
    // title); URL group tolerates one level of balanced parens. Used when the strict shape misses.
    private val FALLBACK_LINK_REGEX =
        Regex("""\s*\[([^\[\]]*(?:\[[^\[\]]*\][^\[\]]*)*)]\(($URL_BODY)\)\s*$""")

    // Inline Markdown we convert to Telegram HTML, in one pass: **bold**, `code`, and
    // [label](url) links anywhere in the text (not just a trailing one). Label tolerates one level
    // of nested brackets (e.g. a `[P]` flair) and the URL one level of balanced parens.
    private val INLINE_HTML = Regex(
        """\*\*(.+?)\*\*|`([^`]+)`|\[([^\[\]]*(?:\[[^\[\]]*\][^\[\]]*)*)]\(($URL_BODY)\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun applyStrictLayout(topic: String): String {
        val match = TOPIC_SHAPE.matchEntire(topic) ?: run {
            logger.debug { "[Format] strict layout fell back, topic head=${topic.take(80)}" }
            val m = FALLBACK_LINK_REGEX.find(topic) ?: return topic
            val label = formatArticleLinkLabel(m.groupValues[1])
            val url   = m.groupValues[2]
            val body  = topic.substring(0, m.range.first)
            return "$body\n\n[$label]($url)"
        }
        val emoji = match.groups["emoji"]?.value?.trim().orEmpty()
        val rawHeadline = match.groups["headline"]!!.value.trim()
        val headline = if (rawHeadline.lastOrNull() in HEADLINE_TERMINALS) rawHeadline else "$rawHeadline."
        val body = match.groups["body"]?.value?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
        val link = match.groups["link"]!!.value

        val titleLine = if (emoji.isEmpty()) "**$headline**" else "$emoji **$headline**"
        return if (body.isEmpty()) "$titleLine\n\n$link" else "$titleLine\n\n$body\n\n$link"
    }

    fun splitTopics(summary: String): List<String> =
        summary.split("•")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun extractUrls(text: String): Set<String> =
        URL_REGEX.findAll(text).map { it.groupValues[1] }.toSet()

    fun replaceSourceLabel(topic: String, articleByLink: Map<String, Article>): String =
        SOURCE_LINK_REGEX.replace(topic) { match ->
            val url = match.groupValues[1]
            val articleTitle = articleByLink[url]?.title?.let(::formatArticleLinkLabel)
            if (articleTitle.isNullOrBlank()) match.value else "[$articleTitle]($url)"
        }

    fun escapeMarkdown(s: String): String =
        s.replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("`", "\\`")
            .replace("[", "\\[")

    /** Escapes the three characters Telegram HTML treats as markup. `&` must be replaced first. */
    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Renders inline Markdown into Telegram HTML (`parse_mode=HTML`) in a single pass.
     *
     * Every `[label](url)` link becomes `<a href="url">label</a>` — anywhere in the text, not just
     * a trailing one, so a multi-link message (e.g. the weekly roundup, sent as one message) renders
     * correctly. `**bold**` / `` `code` `` are converted and all other text is HTML-escaped. A label
     * containing `[`, `]`, `*`, `_` is legal here, which eliminates the legacy Markdown
     * parse-failure → bracket-stripping corruption.
     */
    fun toHtml(topic: String): String {
        val sb = StringBuilder()
        var last = 0
        for (m in INLINE_HTML.findAll(topic)) {
            sb.append(escapeHtml(topic.substring(last, m.range.first)))
            val bold = m.groups[1]
            val code = m.groups[2]
            val linkLabel = m.groups[3]
            val linkUrl = m.groups[4]
            when {
                bold != null -> sb.append("<b>").append(escapeHtml(bold.value)).append("</b>")
                code != null -> sb.append("<code>").append(escapeHtml(code.value)).append("</code>")
                linkLabel != null && linkUrl != null ->
                    sb.append("<a href=\"").append(escapeHtml(linkUrl.value)).append("\">")
                        .append(escapeHtml(linkLabel.value)).append("</a>")
            }
            last = m.range.last + 1
        }
        sb.append(escapeHtml(topic.substring(last)))
        return sb.toString()
    }

    private fun formatArticleLinkLabel(title: String): String {
        val normalized = title
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace("[", "(")
            .replace("]", ")")
        if (normalized.isBlank()) return "джерело"
        return if (normalized.length <= 80) normalized else normalized.take(77).trimEnd() + "..."
    }
}
