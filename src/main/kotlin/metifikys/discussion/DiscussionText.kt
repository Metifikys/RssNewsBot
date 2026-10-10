package metifikys.discussion

import metifikys.config.DiscussionConfig
import metifikys.enrich.ukPlural

/**
 * The source-independent text work of `post.discussion`: which comments the model reads and how,
 * and what of its answer reaches the channel.
 */
object DiscussionText {

    /** A top-level comment shorter than this ("This.", "+1") is skipped; its replies with it. */
    const val MIN_TOP_LEVEL_CHARS = 40

    /** A reply shorter than this is skipped. */
    const val MIN_REPLY_CHARS = 20

    /** One point of the reply longer than this is cut. */
    const val MAX_POINT_CHARS = 300

    private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val MARKUP = Regex("""[\[\]*`_<>#]""")
    private val SPACES = Regex("""\s+""")
    private val BULLET = Regex("""^[\s•\-–—·]+""")

    /**
     * The comments as the model reads them, best first: the first [DiscussionConfig.topLevel]
     * substantial top-level comments, each followed by its first
     * [DiscussionConfig.repliesPerComment] replies, every comment cut at `maxCommentChars`, and the
     * whole stopped before `maxChars`. Empty when no comment qualifies.
     *
     * ```
     * [1] (12 відповідей) The real problem is …
     *     ↳ Not in my experience: …
     * ```
     */
    fun formatComments(thread: DiscussionThread, cfg: DiscussionConfig): String {
        val sb = StringBuilder()
        var n = 0
        for (c in thread.comments) {
            if (n >= cfg.topLevel) break
            if (c.text.length < MIN_TOP_LEVEL_CHARS) continue
            val head = "[${n + 1}]" +
                (c.score?.let { " ($it)" } ?: "") +
                (if (c.descendants > 0) " (${ukPlural(c.descendants, "відповідь", "відповіді", "відповідей")})" else "") +
                " " + clip(c.text, cfg.maxCommentChars)
            val block = StringBuilder(head)
            for (r in c.replies.filter { it.text.length >= MIN_REPLY_CHARS }.take(cfg.repliesPerComment)) {
                block.append("\n    ↳ ").append(clip(r.text, cfg.maxCommentChars))
            }
            val sep = if (sb.isEmpty()) 0 else 2
            if (sb.length + sep + block.length > cfg.maxChars) {
                // The top-level comment alone may still fit without its replies.
                if (sb.length + sep + head.length > cfg.maxChars) break
                if (sep > 0) sb.append("\n\n")
                sb.append(head)
                n++
                break
            }
            if (sep > 0) sb.append("\n\n")
            sb.append(block)
            n++
        }
        return sb.toString()
    }

    /**
     * A model's point as channel text: one line, no URLs (the comments are untrusted — a point
     * must not carry a link someone planted in the thread), no Markdown / HTML markers, cut at
     * [MAX_POINT_CHARS]. Null when nothing is left.
     */
    fun cleanPoint(raw: String): String? {
        val s = SPACES.replace(MARKUP.replace(URL.replace(MD_LINK.replace(raw, "$1"), " "), ""), " ")
            .replace(BULLET, "").trim()
        if (s.length < 10) return null
        return clip(s, MAX_POINT_CHARS)
    }

    /**
     * The reply: `🗣 Обговорення на [HN](thread) · 401 коментар`, a blank line, then one `•` line
     * per point. Markdown links only in the header — the thread URL comes from the source adapter,
     * never from the model.
     */
    fun reply(thread: DiscussionThread, points: List<String>): String = buildString {
        append("🗣 Обговорення на [").append(thread.sourceName).append("](").append(thread.ref.url).append(")")
        append(" · ").append(ukPlural(thread.commentCount, "коментар", "коментарі", "коментарів"))
        append("\n")
        for (p in points) append("\n• ").append(p)
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}
