package metifikys.format

import java.util.EnumMap

/**
 * The footer under a bullet, collected by kind and emitted in one fixed order. [TopicDecorator]
 * drops lines from the END when the message budget is tight, so the order is also the priority:
 * a feature only picks its [Slot], never its position, and no call site can shuffle the footer.
 * Pure: no DB, no network.
 */
class FooterLines {

    /** Footer kinds, most valuable first (= display order). */
    enum class Slot {
        /** `💬` the editor persona's remark (editorial 9). */
        PERSONA,
        /** Live-data badges (Steam / HN / GitHub) and the `▶️` trailer link, in provider order. */
        BADGES,
        /** `📚` the first-mention Wikipedia intro. */
        INTRO,
        /** `🧭` the anti-bubble marker (editorial 8). */
        EXPLORE,
        /** `🔗` the related post in another channel (editorial 5). */
        CROSS_LINK,
        /** Story-cluster facts: `📢` / `🥇`, `⚖️`, `🌀` (`post.storyLines`). */
        STORY,
        /** `📰 Також:` the other outlets the dedup folded into the event (`post.also`). */
        ALSO,
        /** The hashtag line — the first to go. */
        HASHTAGS
    }

    private val lines = EnumMap<Slot, MutableList<String>>(Slot::class.java)

    /** Adds [line] to [slot]; a null or blank line is ignored. */
    fun add(slot: Slot, line: String?): FooterLines {
        if (!line.isNullOrBlank()) lines.getOrPut(slot) { mutableListOf() } += line
        return this
    }

    fun addAll(slot: Slot, more: Collection<String>): FooterLines {
        for (line in more) add(slot, line)
        return this
    }

    /** Every line, slot by slot in [Slot] order, each slot's lines in the order they were added. */
    fun build(): List<String> = Slot.entries.flatMap { lines[it].orEmpty() }

    /** The lines of one [slot], in the order they were added. */
    fun lines(slot: Slot): List<String> = lines[slot].orEmpty().toList()
}
