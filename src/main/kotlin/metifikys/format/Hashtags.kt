package metifikys.format

import metifikys.config.HashtagsConfig
import metifikys.model.ShortlistItem
import java.text.Normalizer

/**
 * Deterministic hashtags for a digest bullet: static per-category tags from config plus tags
 * derived from the Step 1 fields of the bullet's shortlist item. No LLM involved — the same
 * franchise always yields the same tag, which is what makes the tag searchable in the channel.
 *
 * [normalize] is also the shared `entity_key` normalizer for the first-mention intro and the
 * trackers (same string minus the leading `#`), so every feature agrees on what "the same
 * entity" is.
 */
object Hashtags {

    /** Hard cap on a generated tag; longer tags are cut at a `_` boundary. */
    const val MAX_LEN = 32

    /** Values Step 1 emits when a field does not apply — never a tag. */
    private val STOPLIST = setOf(
        "none", "n/a", "na", "null", "nil", "-", "—", "general", "misc", "other", "unknown", "various",
        "інше", "загальне", "немає", "невідомо", "різне"
    )

    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val WELL_FORMED = Regex("""^#[\p{L}\p{N}_]+$""")
    private val WHITESPACE = Regex("""\s+""")

    /** Splits the space-separated static tag list from config; blank/null → empty. */
    fun splitStatic(value: String?): List<String> =
        value?.trim()?.takeIf { it.isNotEmpty() }?.split(WHITESPACE).orEmpty()

    /** `#` followed by letters/digits/underscore, with at least one letter (Telegram ignores all-digit tags). */
    fun isWellFormed(tag: String): Boolean =
        WELL_FORMED.matches(tag) && tag.any { it.isLetter() }

    /**
     * Turns a free-text entity name into a hashtag, or null when the value cannot be a tag
     * (blank, a stop word, no letters).
     *
     * Latin diacritics are folded (`Tōkon` → `tokon`) but Cyrillic is left untouched: NFD
     * decomposes `й` into `и` + U+0306 and `ї` into `і` + U+0308, so a blanket "strip
     * combining marks" would corrupt every Ukrainian tag.
     */
    fun normalize(raw: String?): String? {
        val key = entityKey(raw) ?: return null
        return "#$key"
    }

    /** [normalize] without the `#`: the canonical key of an entity name. */
    fun entityKey(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.lowercase() in STOPLIST) return null
        val folded = buildString(trimmed.length) { for (ch in trimmed) append(foldLatin(ch)) }
        var key = NON_ALNUM.replace(folded.lowercase(), "_").trim('_')
        if (key.length > MAX_LEN) {
            // Never keep half of an astral letter (math alphanumerics, CJK Ext. B): a lone surrogate
            // is not \p{L}, so the tag would fail isWellFormed and change on re-normalization.
            val cut = key.substring(0, MAX_LEN).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
            val boundary = cut.lastIndexOf('_')
            key = (if (boundary > 0) cut.substring(0, boundary) else cut).trim('_')
        }
        if (key.isEmpty() || key.none { it.isLetter() }) return null
        return key
    }

    /**
     * Tags for one bullet: the static category tags first, then up to [HashtagsConfig.max]
     * generated ones (franchise, event type, subject — in that order), deduplicated. A legacy
     * bullet ([item] null) gets the static tags only.
     */
    fun forItem(item: ShortlistItem?, config: HashtagsConfig?): List<String> {
        if (config == null) return emptyList()
        val static = splitStatic(config.category).distinct()
        if (item == null) return static
        val generated = buildList {
            if (config.fromFranchise) add(item.franchise)
            if (config.fromEventType) add(item.eventType)
            if (config.fromSubject) add(item.subject)
        }
            .mapNotNull { normalize(it) }
            .distinct()
            .filterNot { it in static }
            .take(config.max)
        return static + generated
    }

    private fun foldLatin(ch: Char): String {
        if (ch.code < 0x80) return ch.toString()
        val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
        val base = decomposed.firstOrNull() ?: return ch.toString()
        if (base !in 'A'..'Z' && base !in 'a'..'z') return ch.toString()
        return decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }
}
