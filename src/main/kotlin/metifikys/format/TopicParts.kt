package metifikys.format

/**
 * A topic in [TopicFormatter.applyStrictLayout]'s shape cut into its parts: the title line
 * (`{emoji} **Headline.**`, when the first block has a `**bold**` headline), the body paragraphs
 * (blank-line separated) and the trailing `[label](url)` source-link block. Shared by the
 * telegra.ph page and the rich long-form post. Pure.
 */
data class TopicParts(val title: String?, val body: List<String>, val link: String?) {

    /** The title line as plain heading text: `**` markers and a trailing period dropped, emoji kept. */
    val headline: String?
        get() = title?.replace("**", "")?.replace(WHITESPACE, " ")?.trim()?.removeSuffix(".")?.trimEnd()?.takeIf { it.isNotEmpty() }

    companion object {
        private val BLOCK_BREAK = Regex("""\n[ \t\r]*\n\s*""")
        private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
        private val WHITESPACE = Regex("""\s+""")

        fun of(topic: String): TopicParts {
            val blocks = topic.trim().split(BLOCK_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
            if (blocks.isEmpty()) return TopicParts(null, emptyList(), null)
            val title = blocks.first().takeIf { BOLD.containsMatchIn(it) }
            val rest = if (title != null) blocks.drop(1) else blocks
            val link = rest.lastOrNull()?.takeIf { TopicFormatter.MARKDOWN_LINK.matchEntire(it) != null }
            val body = if (link != null) rest.dropLast(1) else rest
            return TopicParts(title, body, link)
        }
    }
}
