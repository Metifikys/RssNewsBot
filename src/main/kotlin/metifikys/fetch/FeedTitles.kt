package metifikys.fetch

import metifikys.model.HtmlText

/**
 * Titles for feed items that have none worth keeping — the posts of converted feeds (RSSHub
 * Telegram / X routes, Mastodon, Bluesky). Mastodon and Bluesky give items no `<title>` at all,
 * so Step 1, the legacy prompt and the mute filter saw an empty title; RSSHub puts the post's
 * truncated text there, which only repeats the description and eats prompt budget.
 *
 * The replacement is the post's first line — read from the RAW description, because
 * [HtmlText.strip] glues `<br>` / `<p>` lines together — cut to its first sentence, or when
 * that is too long, at a word boundary with `…`.
 */
object FeedTitles {

    const val DEFAULT_MAX_CHARS = 120

    /** Shortest sentence worth being a title on its own ("Анонс!" is not; the next line may be). */
    private const val MIN_SENTENCE_CHARS = 20

    private val LINE_BREAK = Regex(
        """<br\s*/?>|</?(p|div|li|h[1-6]|blockquote|tr)(\s[^>]*)?>|\r?\n""",
        RegexOption.IGNORE_CASE
    )
    private val SENTENCE_END = Regex("""[.!?…](?=\s)""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * The title to use: [rawTitle] unless it is blank, or — with [rewriteDuplicate] — it merely
     * repeats the start of the description; then the description's first line. Returns [rawTitle]
     * (trimmed) when the description has no text either.
     */
    fun derive(
        rawTitle: String?,
        rawDescriptionHtml: String,
        rewriteDuplicate: Boolean = false,
        maxChars: Int = DEFAULT_MAX_CHARS
    ): String {
        val title = normalize(HtmlText.strip(rawTitle.orEmpty()))
        val needed = title.isEmpty() || (rewriteDuplicate && repeatsDescription(title, rawDescriptionHtml))
        if (!needed) return rawTitle.orEmpty().trim()
        return firstLine(rawDescriptionHtml, maxChars) ?: rawTitle.orEmpty().trim()
    }

    /** True when [title] (a truncated copy, possibly ending in `…`) is the start of the description text. */
    internal fun repeatsDescription(title: String, rawDescriptionHtml: String): Boolean {
        val head = normalize(title).trimEnd('…', '.', ' ').lowercase()
        if (head.length < 8) return false
        return normalize(HtmlText.strip(rawDescriptionHtml)).lowercase().startsWith(head)
    }

    /**
     * The first non-blank line of [rawHtml] as plain text, shortened to [maxChars]; null when there
     * is none. A too-short first line ("Анонс!", "🔥 Новина") takes the next line along.
     */
    fun firstLine(rawHtml: String, maxChars: Int = DEFAULT_MAX_CHARS): String? {
        val lines = rawHtml.split(LINE_BREAK)
            .asSequence()
            .map { normalize(HtmlText.strip(it)) }
            .filter { it.any(Char::isLetterOrDigit) }
            .take(2)
            .toList()
        val first = lines.firstOrNull() ?: return null
        val line = if (first.length < MIN_SENTENCE_CHARS && lines.size > 1) "$first ${lines[1]}" else first
        return shorten(line, maxChars)
    }

    internal fun shorten(text: String, maxChars: Int): String {
        // First sentence, when it is long enough to stand alone and fits: a title, not the post again.
        SENTENCE_END.findAll(text).firstOrNull { it.range.last + 1 >= MIN_SENTENCE_CHARS }?.let { m ->
            if (m.range.last + 1 <= maxChars) return text.substring(0, m.range.last + 1)
        }
        if (text.length <= maxChars) return text
        // Otherwise the last whole word that fits, with an ellipsis.
        val room = maxChars - 1
        val space = text.lastIndexOf(' ', room)
        var cut = if (space >= room / 2) space else room
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
        return text.substring(0, cut).trimEnd(' ', ',', ';', ':', '-', '—') + "…"
    }

    private fun normalize(s: String): String = s.replace(WHITESPACE, " ").trim()
}
