package metifikys.digest

import metifikys.model.Article

/**
 * Hard, deterministic keyword mute applied BEFORE Step-1 (`preferences.mute`). Unlike the
 * reaction-affinity term — which is a soft, clamped tiebreaker that must never override
 * newsworthiness — this gate drops matching articles outright, so the LLM never sees them.
 *
 * Matching is whole-word / whole-phrase and case-insensitive: the keyword `cod` matches
 * "CoD" and "CoD: Warzone" but NOT "code" or "codex"; a multi-word keyword like
 * `call of duty` matches only that phrase. Both an article's title and description are
 * checked. Pure — built once from config, no I/O.
 */
class TopicMuteFilter private constructor(
    private val patterns: List<Regex>
) {

    companion object {
        /** Builds a filter from the configured keywords. Blank entries are ignored. */
        fun of(keywords: List<String>): TopicMuteFilter = TopicMuteFilter(
            keywords.mapNotNull { raw ->
                val kw = raw.trim()
                if (kw.isEmpty()) null
                else Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE)
            }
        )

        val EMPTY = TopicMuteFilter(emptyList())
    }

    fun isEmpty(): Boolean = patterns.isEmpty()

    /** True when the article's title OR description contains any muted keyword as a whole word/phrase. */
    fun matches(article: Article): Boolean {
        if (patterns.isEmpty()) return false
        return patterns.any { it.containsMatchIn(article.title) || it.containsMatchIn(article.description) }
    }
}
