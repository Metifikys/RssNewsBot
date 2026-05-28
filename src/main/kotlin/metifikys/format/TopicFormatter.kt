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

    private val URL_REGEX = Regex("""\[.*?]\((.*?)\)""")

    private val SOURCE_LINK_REGEX = Regex("""\[джерело]\((.*?)\)""")

    private val FALLBACK_LINK_REGEX = Regex("""\s*\[([^\]]*?)]\(([^)]+)\)\s*$""")

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
