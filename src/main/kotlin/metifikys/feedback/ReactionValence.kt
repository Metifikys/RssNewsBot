package metifikys.feedback

/**
 * Maps a stored reaction key (the plain emoji, `custom:<id>`, or `paid` — see
 * [metifikys.telegram.TelegramUpdatesPoller]) to a valence in [-1.0, +1.0]. The built-in
 * table covers Telegram's free reaction set; `feedback.valence` config entries are merged
 * over it, so a channel whose audience uses 🤡 approvingly can flip it without a rebuild.
 *
 * Unknown emoji (including `custom:*` by default) are neutral (0.0) — they still count
 * toward a message's reaction volume, they just don't push its tone either way.
 */
class ReactionValence(overrides: Map<String, Double> = emptyMap()) {

    private val table: Map<String, Double> = DEFAULTS + overrides

    /** Valence of one reaction key; 0.0 for anything not in the table. */
    fun of(emoji: String): Double = table[emoji] ?: 0.0

    companion object {
        /**
         * Defaults for Telegram's standard reaction emoji. Strong positive = enthusiastic
         * approval; mild positive = warm but low-commitment; neutral = attention without
         * direction (🤔 curiosity, 😢 sympathy — the story is sad, not the coverage);
         * negative = disapproval of the post itself. `paid` (Telegram Stars) is the
         * costliest signal a reader can send, hence the maximum.
         */
        val DEFAULTS: Map<String, Double> = mapOf(
            // strong positive
            "🔥" to 1.0, "❤" to 1.0, "❤️" to 1.0, "😍" to 1.0, "🤩" to 1.0, "paid" to 1.0,
            "⚡" to 1.0, "🏆" to 1.0, "💯" to 1.0,
            // positive
            "👍" to 0.75, "🥰" to 0.5, "👏" to 0.5, "🎉" to 0.5, "🙏" to 0.5, "😁" to 0.5,
            "🤣" to 0.5, "😱" to 0.25, "🤯" to 0.25,
            // neutral / undirected attention
            "🤔" to 0.0, "👀" to 0.0, "😢" to 0.0, "😭" to 0.0, "🕊" to 0.0, "🙈" to 0.0,
            // negative
            "😐" to -0.25, "🥱" to -0.5, "👎" to -0.5, "🤨" to -0.25,
            // strong negative
            "💩" to -1.0, "🤮" to -1.0, "🤬" to -1.0, "😡" to -1.0, "🤡" to -0.75
        )
    }
}
