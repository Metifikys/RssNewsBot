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

    // The trailing source link uses the canonical LINK grammar, so a `[P]`-flaired label or a
    // `Foo_(bar)` URL still gets the strict layout instead of silently falling back. The emoji
    // prefix never starts with `*`: otherwise a one-word headline (`**Silksong.**`) was taken
    // for the "emoji" and the next `**bold**` in the body for the headline.
    private val TOPIC_SHAPE = Regex(
        """^\s*(?<emoji>[^\s*]\S*)?\s*\*\*(?<headline>.+?)\*\*\s*(?<body>.*?)\s*(?<link>$LINK)\s*$""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val HEADLINE_TERMINALS = setOf('.', '!', '?', '…', ':')

    private val PARAGRAPH_BREAK = Regex("""\n[ \t\r]*\n\s*""")
    private val WHITESPACE_RUN = Regex("""\s+""")

    // URL group tolerates one level of balanced parentheses inside the URL itself
    // (e.g. Wikipedia/MDN `..._(disambiguation)` links) so extractUrls no longer truncates.
    private const val URL_BODY = """[^()]*(?:\([^()]*\)[^()]*)*"""

    private val URL_REGEX = Regex("""\[.*?]\(($URL_BODY)\)""")

    private val SOURCE_LINK_REGEX = Regex("""\[джерело]\((.*?)\)""")

    // Label tolerates one level of nested brackets (e.g. a Reddit flair tag `[P]` at the end of a
    // title); URL group tolerates one level of balanced parens. Used when the strict shape misses.
    private val FALLBACK_LINK_REGEX =
        Regex("""\s*\[([^\[\]]*(?:\[[^\[\]]*\][^\[\]]*)*)]\(($URL_BODY)\)\s*$""")

    // A [label](url) link: label tolerates one level of nested brackets (e.g. a `[P]` flair), the
    // URL one level of balanced parens. Groups: 1 = label, 2 = url.
    private const val LINK = """\[([^\[\]]*(?:\[[^\[\]]*\][^\[\]]*)*)]\(($URL_BODY)\)"""

    /**
     * The canonical Markdown link matcher — exactly the links [toHtml] renders as `<a>`. Code that
     * must not cut or mis-scan a link (caption truncation, bare-URL checks) should use this one.
     */
    val MARKDOWN_LINK = Regex(LINK)

    // A date-time entity a footer line asks for (`post.dateLine`): ⟦t:<unix>:<format>|<fallback>⟧.
    // Only a valid Bot API format matches; anything else stays (escaped) text.
    private const val TIME_TOKEN = """⟦t:(\d{1,12}):(r|w?[dD]?[tT]?)\|([^⟧\n]*)⟧"""

    // Inline Markdown we convert to Telegram HTML, in one pass: **bold**, `code`, and
    // [label](url) links anywhere in the text (not just a trailing one), and time tokens.
    private val INLINE_HTML = Regex(
        """\*\*(.+?)\*\*|`([^`]+)`|$LINK|$TIME_TOKEN""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * A token [toHtml] turns into a `<tg-time>` date-time entity: every reader sees [unix] in their
     * own zone and language as [format] says (`r` relative, `w` weekday, `d`/`D` date, `t`/`T` time);
     * [fallback] is the text of a client without the entity and of the plain-text retry.
     */
    fun timeToken(unix: Long, format: String, fallback: String): String = "⟦t:$unix:$format|$fallback⟧"

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
        // Whitespace inside a paragraph collapses to one space; a blank line between paragraphs
        // (a long-form body, `post.longForm`) survives as the paragraph break.
        val body = match.groups["body"]?.value.orEmpty()
            .split(PARAGRAPH_BREAK)
            .map { it.replace(WHITESPACE_RUN, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        val link = match.groups["link"]!!.value

        val titleLine = if (emoji.isEmpty()) "**$headline**" else "$emoji **$headline**"
        return if (body.isEmpty()) "$titleLine\n\n$link" else "$titleLine\n\n$body\n\n$link"
    }

    // Topic delimiters: the `•` bullet the render/weekly prompts ask for, and a standalone
    // `---` rule — the separator the legacy single-step prompt asks for. Without the second a
    // legacy digest (Step 1 fallback) arrives as ONE topic, and applyStrictLayout then flattens
    // its `---` lines into an inline ` --- ` (2026-09-09 science digests).
    private val TOPIC_DELIMITER = Regex("""(?m)•|^[ \t]*-{3,}[ \t]*$""")

    fun splitTopics(summary: String): List<String> =
        summary.split(TOPIC_DELIMITER)
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

    private val HTML_TAG = Regex("<[^>]*>")

    /**
     * How long Telegram counts [html] against its message limit — "after entities parsing":
     * tags (with their `href`s) are free and an escape counts as the one character it stands for.
     */
    fun htmlVisibleLength(html: String): Int =
        HTML_TAG.replace(html, "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            .length

    /**
     * [escapeHtml] plus `"` → `&quot;`, for a double-quoted attribute value (`href`): a raw quote
     * would end the attribute early and Telegram rejects the message ("can't parse entities").
     */
    private fun escapeHtmlAttribute(s: String): String = escapeHtml(s).replace("\"", "&quot;")

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
            val timeUnix = m.groups[5]
            when {
                bold != null -> sb.append("<b>").append(escapeHtml(bold.value)).append("</b>")
                code != null -> sb.append("<code>").append(escapeHtml(code.value)).append("</code>")
                linkLabel != null && linkUrl != null ->
                    sb.append("<a href=\"").append(escapeHtmlAttribute(linkUrl.value)).append("\">")
                        .append(escapeHtml(linkLabel.value)).append("</a>")
                timeUnix != null ->
                    sb.append("<tg-time unix=\"").append(timeUnix.value).append("\" format=\"").append(m.groups[6]?.value.orEmpty())
                        .append("\">").append(escapeHtml(m.groups[7]?.value.orEmpty())).append("</tg-time>")
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
