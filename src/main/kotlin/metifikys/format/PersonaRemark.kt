package metifikys.format

import metifikys.config.PersonaConfig

/**
 * The editor-persona remark (`post.persona`): the A/B arm of an event and the guards a model's
 * remark must pass before it may stand under a post as `💬 <remark>`. Pure: no DB, no network.
 */
object PersonaRemark {

    const val PREFIX = "💬"

    /** Markup that would render (Telegram HTML via [TopicFormatter.toHtml]) or make a hashtag. */
    private val FORBIDDEN_MARKUP = listOf("[", "]", "#", "`", "**")

    private val URL_LIKE = Regex("""https?://|www\.|t\.me/|\b[a-z0-9-]+\.(com|net|org|io|ua|ru|gg|tv)\b""", RegexOption.IGNORE_CASE)

    private val NUMBER = Regex("""\d+(?:[.,]\d+)*""")

    /** Quotes / dashes a model likes to wrap a one-liner in. */
    private val WRAPPERS = charArrayOf('"', '\'', '«', '»', '“', '”', '„', ' ')

    /** True when [eventKey] falls in the persona arm: `floorMod(hashCode, 100) < share × 100`. */
    fun inPersonaArm(eventKey: String, share: Double): Boolean =
        Math.floorMod(eventKey.hashCode(), 100) < Math.round(share * 100)

    /** A checked remark: [text] when it passed, else the [reason] it did not. */
    data class Checked(val text: String?, val reason: String?)

    /**
     * Cleans [raw] (trim, a leading `💬`, wrapping quotes, inner whitespace) and checks it against
     * [topic] (the bullet as posted, Markdown) and [cfg]: one line, at most `maxChars`, no URL, no
     * markup from [FORBIDDEN_MARKUP], no banned phrase, and every number it states present in the
     * topic's text (link targets excluded — a year in a URL proves nothing).
     */
    fun check(raw: String, topic: String, cfg: PersonaConfig): Checked {
        var text = raw.trim().removePrefix(PREFIX).trim().trim(*WRAPPERS).trim()
        if (text.isEmpty()) return Checked(null, "empty")
        if (text.contains('\n')) return Checked(null, "several lines")
        text = text.replace(Regex("""\s+"""), " ")
        if (text.length > cfg.maxChars) return Checked(null, "${text.length} chars > maxChars=${cfg.maxChars}")
        if (URL_LIKE.containsMatchIn(text)) return Checked(null, "URL")
        FORBIDDEN_MARKUP.firstOrNull { it in text }?.let { return Checked(null, "markup '$it'") }
        val lower = text.lowercase()
        cfg.bannedPhrases.firstOrNull { it.isNotBlank() && it.lowercase() in lower }
            ?.let { return Checked(null, "banned phrase '$it'") }
        val topicText = TopicFormatter.MARKDOWN_LINK.replace(topic) { it.groupValues[1] }
        val topicNumbers = NUMBER.findAll(topicText).map { normalizeNumber(it.value) }.toSet()
        NUMBER.findAll(text).map { it.value }.firstOrNull { normalizeNumber(it) !in topicNumbers }
            ?.let { return Checked(null, "number '$it' not in the topic") }
        return Checked(text, null)
    }

    /** `1,5` and `1.5` are one number; thousands separators are not worth telling apart here. */
    private fun normalizeNumber(n: String): String = n.replace(',', '.')

    /** The footer line for a checked remark. */
    fun line(text: String): String = "$PREFIX $text"
}
