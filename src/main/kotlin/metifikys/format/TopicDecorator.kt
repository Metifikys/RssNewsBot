package metifikys.format

/**
 * Appends deterministic footer lines (badges, first-mention intro, hashtags) to a finished
 * bullet right before it is sent. Pure: no DB, no network.
 *
 * The footer is passed in display order and, when the result would not fit [decorate]'s
 * character budget, lines are dropped from the END until it does — so the caller orders them
 * most-valuable-first (badges, intro, hashtags) and the least valuable line is the first to go.
 * The bullet itself is never touched; the budget is enforced against the Markdown text, the same
 * length [metifikys.telegram.TelegramSender] chunks and truncates on.
 */
object TopicDecorator {

    /** Telegram's hard limit for a photo caption. */
    const val CAPTION_BUDGET = 1024

    /** Telegram's hard limit for one text message — keeps the footer in the same message as the bullet. */
    const val TEXT_BUDGET = 4096

    fun decorate(topic: String, footer: List<String>, budgetChars: Int): String {
        val lines = footer.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (lines.isEmpty()) return topic
        val head = topic.trimEnd()
        while (lines.isNotEmpty()) {
            val candidate = head + "\n" + lines.joinToString("\n")
            if (candidate.length <= budgetChars) return candidate
            lines.removeAt(lines.size - 1)
        }
        return topic
    }
}
