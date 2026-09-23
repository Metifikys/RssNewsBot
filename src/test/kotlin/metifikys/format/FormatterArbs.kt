package metifikys.format

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import kotlin.random.Random

/**
 * Generators for the property-based formatter tests: bullet-like text mixing ASCII, Cyrillic
 * (with `й`/`ї`, whose NFD forms broke hashtags once), astral emoji (skin tones, ZWJ families —
 * surrogate pairs), HTML/Markdown specials and three kinds of links (plain, a `[P]`-flaired
 * label, a URL with balanced parens or a `"`), from empty up to 6000 chars, with and without
 * newlines so both the line packing and the hard split of [metifikys.telegram.TelegramSender]
 * get exercised.
 *
 * Square brackets and parentheses appear ONLY inside generated links, so "every `](` belongs to
 * a whole link" is a checkable property. When a property fails kotest prints the seed; rerun
 * with `PropTestConfig(seed = …)` locally, never pin it in the committed test.
 */
object FormatterArbs {

    private val asciiWords = listOf("Valve", "update", "patch", "2.0", "is", "out", "GPU", "a", "the", "Rust", "x86_64")
    private val cyrillicWords = listOf("гра", "вийшла", "їжак", "йогурт", "Київ", "оновлення", "ґанок", "є", "Шеф", "ЗСУ")
    private val emoji = listOf("😀", "🎮", "👍🏽", "👨‍👩‍👧", "🏳️‍🌈", "🔥", "✅", "⚡️", "🇺🇦")
    private val specials = listOf("&", "<", ">", "\"", "*", "_", "`", "**", "&amp;", "<b>", "'", "—", "…", "\\", "#")

    private val links = listOf(
        "[Valve ships Steam Deck 2](https://example.com/news/steam-deck-2)",
        "[Джерело](https://ua.example.com/статті/1)",
        "[I built a parser [P]](https://reddit.com/r/programming/comments/abc)",
        "[Doom](https://en.wikipedia.org/wiki/Doom_(2016_video_game))",
        "[quote](https://example.com/search?q=\"steam\")",
        "[🎮 Games](https://example.com/g?a=1&b=2)"
    )

    private fun <T> Random.pick(list: List<T>): T = list[nextInt(list.size)]

    /** One token of bullet text; [newlineOdds] is the per-token chance (in 1/1000) of a line break. */
    private fun token(r: Random, newlineOdds: Int): String {
        if (r.nextInt(1000) < newlineOdds) return if (r.nextBoolean()) "\n" else "\n\n"
        return when (r.nextInt(100)) {
            in 0..29 -> r.pick(asciiWords)
            in 30..54 -> r.pick(cyrillicWords)
            in 55..64 -> r.pick(emoji)
            in 65..74 -> r.pick(specials)
            in 75..79 -> r.pick(links)
            in 80..82 -> "**" + r.pick(asciiWords) + " " + r.pick(cyrillicWords) + "**"
            in 83..84 -> "`" + r.pick(asciiWords) + "`"
            else -> " "
        } + if (r.nextInt(3) > 0) " " else ""
    }

    private fun build(r: Random, target: Int, newlineOdds: Int): String {
        val sb = StringBuilder()
        while (sb.length < target) sb.append(token(r, newlineOdds))
        return sb.toString()
    }

    /** Free-form text, 0..[maxLen] chars; a third of the samples have no line breaks at all. */
    fun text(maxLen: Int = 6000): Arb<String> = arbitrary { rs: RandomSource ->
        val r = rs.random
        val newlineOdds = when (r.nextInt(3)) { 0 -> 0; 1 -> 5; else -> 60 }
        build(r, r.nextInt(maxLen + 1), newlineOdds)
    }

    /**
     * A digest bullet in (roughly) the shape Step 2 is asked for: optional emoji, `**headline**`,
     * a body, and a trailing source link — sometimes a flaired / paren-URL one.
     */
    fun topic(): Arb<String> = arbitrary { rs: RandomSource ->
        val r = rs.random
        val emojiPart = if (r.nextInt(4) == 0) "" else r.pick(emoji) + " "
        val headline = noLinks(build(r, r.nextInt(5, 80), 0)).replace("*", "").trim().ifEmpty { "Headline" }
        val body = noLinks(build(r, r.nextInt(0, 900), 20))
        emojiPart + "**" + headline + "**\n" + body + "\n" + r.pick(links)
    }

    private fun noLinks(s: String) = s.filterNot { it in "[]()" }

    /** Footer lines as the decorator gets them: badges, intro, hashtags — some blank, some long. */
    fun footer(): Arb<List<String>> = arbitrary { rs: RandomSource ->
        val r = rs.random
        List(r.nextInt(0, 5)) {
            when (r.nextInt(5)) {
                0 -> ""
                1 -> "   "
                else -> build(r, r.nextInt(1, 400), 0)
            }
        }
    }

    /** Entity names as Step 1 emits them: franchise / subject strings in any script. */
    fun entityName(): Arb<String> = arbitrary { rs: RandomSource ->
        val r = rs.random
        val parts = listOf(
            "Tōkon", "Pokémon", "Ærø", "İstanbul", "ǅemal", "Straße", "Київ", "їжак", "Йорданія", "𝐁𝐨𝐥𝐝",
            "𠀀𠀁", "Half-Life 3", "Baldur's Gate", "C++", "none", "N/A", "  ", "__", "#tag", "123", "🎮", "ŁÓDŹ", "ﬁx"
        )
        List(r.nextInt(1, 12)) { r.pick(parts) }.joinToString(if (r.nextBoolean()) " " else "")
    }

    /** Words of Cyrillic letters only (no Latin, no diacritics beyond й/ї/є/ґ), up to the tag cap. */
    fun cyrillicWord(): Arb<String> = arbitrary { rs: RandomSource ->
        val r = rs.random
        val letters = "абвгґдеєжзиіїйклмнопрстуфхцчшщьюяАБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ"
        String(CharArray(r.nextInt(1, Hashtags.MAX_LEN + 1)) { letters[r.nextInt(letters.length)] })
    }
}
