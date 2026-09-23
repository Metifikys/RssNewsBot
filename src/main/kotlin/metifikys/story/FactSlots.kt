package metifikys.story

import metifikys.digest.TrackerDates
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Deterministic fact slots in a headline / teaser (plan item 6): prices, dates and head counts, each
 * normalized so two outlets' wordings of the same value compare equal. Pure; no LLM.
 *
 * - [Slot.PRICE]: a currency symbol or code with a number (`$69.99`, `€59`, `₴1 999`, `70 USD`,
 *   `$1.5 billion`); [Fact.unit] is the ISO currency, [Fact.value] the plain amount.
 * - [Slot.DATE]: `2026-10-14`, `October 14`, `14 жовтня 2026`, `October 2026`, `у жовтні 2026`,
 *   `Q4 2026`; [Fact.value] is in the [TrackerDates] vocabulary (`2026-10-14`, `2026-10`, `2026-Q4`).
 *   A day without a year takes the year that puts it closest to the article's own date. Bare years
 *   are not read — every text is full of them.
 * - [Slot.COUNT]: a number (or `N%`) within [COUNT_WINDOW] words of a layoffs / staff keyword;
 *   [Fact.unit] is the keyword group (`layoffs`, `staff`, with a `%` suffix for percentages).
 *
 * A span that one slot claimed is not read again by a later one (a price's number is no head count).
 */
object FactSlots {

    enum class Slot { PRICE, DATE, COUNT }

    data class Fact(val slot: Slot, val unit: String, val value: String, val span: IntRange)

    /** Words on each side of a number searched for a count keyword. */
    const val COUNT_WINDOW = 4

    fun extract(text: String, reference: LocalDate): List<Fact> {
        val facts = mutableListOf<Fact>()
        val taken = mutableListOf<IntRange>()
        fun free(r: IntRange) = taken.none { it.first <= r.last && r.first <= it.last }
        fun add(f: Fact) {
            if (!free(f.span)) return
            facts += f
            taken += f.span
        }
        prices(text).forEach(::add)
        dates(text, reference).forEach(::add)
        // Years and other date parts are no head counts either, even when unparseable as a date.
        YEAR_TOKEN.findAll(text).forEach { taken += it.range }
        counts(text).forEach(::add)
        return facts.sortedBy { it.span.first }
    }

    // ── prices ──────────────────────────────────────────────────────────────

    /** `1,299.99`, `1 999`, `59,99`, `69.99`, `70`: grouped thousands, then an optional 1–2 digit decimal part. */
    private const val NUM = """(?<![\d.,])(\d{1,3}(?:[ ,.  ]\d{3})+|\d+)(?:[.,](\d{1,2}))?(?![\d])"""
    private const val MULT = """(?:\s?(?i:(trillion|billion|million|thousand|tn|bn|mn|b|m|k|трлн|млрд|млн|тис))(?![\p{L}]))?"""

    /** A country prefix only glued to the `$` and upper-case (`CA$`, `A$`) — "a $5 fee" is dollars. */
    private val PRICE_PREFIX = Regex("""(?:(?<![\p{L}])(US|CA|AU|NZ|C|A))?([$€£₴¥])\s?$NUM$MULT""")
    private val PRICE_SUFFIX = Regex(
        """$NUM$MULT\s?(USD|EUR|GBP|UAH|JPY|CAD|AUD|dollars|euros?|грн\.?|гривень|гривні|гривня|доларів|долари|долар|євро|€|₴)(?![\p{L}])""",
        RegexOption.IGNORE_CASE
    )

    private fun prices(text: String): List<Fact> {
        val out = mutableListOf<Fact>()
        for (m in PRICE_PREFIX.findAll(text)) {
            val currency = prefixCurrency(m.groupValues[1].uppercase(), m.groupValues[2]) ?: continue
            val amount = amount(m.groupValues[3], m.groupValues[4], m.groupValues[5]) ?: continue
            out += Fact(Slot.PRICE, currency, amount, m.range)
        }
        for (m in PRICE_SUFFIX.findAll(text)) {
            val currency = suffixCurrency(m.groupValues[4]) ?: continue
            val amount = amount(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: continue
            out += Fact(Slot.PRICE, currency, amount, m.range)
        }
        return out
    }

    private fun prefixCurrency(country: String, symbol: String): String? = when (symbol) {
        "$" -> when (country) {
            "", "US" -> "USD"
            "CA", "C" -> "CAD"
            "AU", "A" -> "AUD"
            "NZ" -> "NZD"
            else -> null
        }
        "€" -> "EUR"
        "£" -> "GBP"
        "₴" -> "UAH"
        "¥" -> "JPY"
        else -> null
    }

    private fun suffixCurrency(raw: String): String? {
        val s = raw.lowercase().trimEnd('.')
        return when {
            s == "usd" || s == "dollars" || s.startsWith("долар") -> "USD"
            s == "eur" || s.startsWith("euro") || s == "євро" || s == "€" -> "EUR"
            s == "gbp" -> "GBP"
            s == "uah" || s == "грн" || s.startsWith("гривн") || s == "₴" -> "UAH"
            s == "jpy" -> "JPY"
            s == "cad" -> "CAD"
            s == "aud" -> "AUD"
            else -> null
        }
    }

    /** Plain decimal string of `int[.frac] × mult`, trailing zeros dropped; null for garbage. */
    private fun amount(intPart: String, frac: String, mult: String): String? {
        val digits = intPart.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        var v = BigDecimal(if (frac.isNotEmpty()) "$digits.$frac" else digits)
        v = when (mult.lowercase()) {
            "trillion", "tn", "трлн" -> v.movePointRight(12)
            "billion", "bn", "b", "млрд" -> v.movePointRight(9)
            "million", "mn", "m", "млн" -> v.movePointRight(6)
            "thousand", "k", "тис" -> v.movePointRight(3)
            else -> v
        }
        if (v.signum() <= 0) return null
        return v.stripTrailingZeros().toPlainString()
    }

    // ── dates ───────────────────────────────────────────────────────────────

    private const val EN_MONTH =
        """(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"""
    private const val EN_MONTH_FULL =
        """(January|February|March|April|May|June|July|August|September|October|November|December)"""
    private const val UK_MONTH_GEN =
        """(січня|лютого|березня|квітня|травня|червня|липня|серпня|вересня|жовтня|листопада|грудня)"""
    private const val UK_MONTH_STEM = """(січ|лют|берез|квіт|трав|черв|лип|серп|верес|жовт|листопад|груд)\p{L}*"""
    private const val YEAR = """(20\d{2})"""
    private const val L = """(?<![\p{L}\d])"""
    private const val R = """(?![\p{L}\d])"""

    private val ISO_DAY = Regex("""$L$YEAR-(\d{2})-(\d{2})$R""")
    private val EN_MONTH_DAY = Regex("""$L$EN_MONTH\.?\s+(\d{1,2})(?:st|nd|rd|th)?$R(?:,?\s+$YEAR$R)?""", RegexOption.IGNORE_CASE)
    private val EN_DAY_MONTH = Regex("""$L(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$EN_MONTH$R\.?(?:,?\s+$YEAR$R)?""", RegexOption.IGNORE_CASE)
    private val UK_DAY_MONTH = Regex("""$L(\d{1,2})\s+$UK_MONTH_GEN$R(?:\s+$YEAR$R)?""", RegexOption.IGNORE_CASE)
    private val EN_MONTH_YEAR = Regex("""$L$EN_MONTH_FULL,?\s+$YEAR$R""", RegexOption.IGNORE_CASE)
    private val UK_MONTH_YEAR = Regex("""$L$UK_MONTH_STEM\s+$YEAR$R""", RegexOption.IGNORE_CASE)
    private val QUARTER_FIRST = Regex("""${L}Q([1-4])\s*(?:'(\d{2})|$YEAR)$R""", RegexOption.IGNORE_CASE)
    private val YEAR_QUARTER = Regex("""$L$YEAR\s*-?\s*Q([1-4])$R""", RegexOption.IGNORE_CASE)
    private val YEAR_TOKEN = Regex("""$L(?:19|20)\d{2}$R""")

    private val EN_MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val UK_STEMS = listOf("січ", "лют", "берез", "квіт", "трав", "черв", "лип", "серп", "верес", "жовт", "листопад", "груд")

    private fun enMonth(s: String): Int = EN_MONTHS.indexOf(s.take(3).lowercase()) + 1
    private fun ukMonth(s: String): Int {
        val l = s.lowercase()
        return UK_STEMS.indexOfFirst { l.startsWith(it) } + 1
    }

    private fun dates(text: String, reference: LocalDate): List<Fact> {
        val out = mutableListOf<Fact>()
        for (m in ISO_DAY.findAll(text)) {
            day(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { out += Fact(Slot.DATE, UNIT_DATE, it, m.range) }
        }
        for (m in EN_MONTH_DAY.findAll(text)) {
            dayGuessYear(enMonth(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3], reference)
                ?.let { out += Fact(Slot.DATE, UNIT_DATE, it, m.range) }
        }
        for (m in EN_DAY_MONTH.findAll(text)) {
            dayGuessYear(enMonth(m.groupValues[2]), m.groupValues[1].toInt(), m.groupValues[3], reference)
                ?.let { out += Fact(Slot.DATE, UNIT_DATE, it, m.range) }
        }
        for (m in UK_DAY_MONTH.findAll(text)) {
            dayGuessYear(ukMonth(m.groupValues[2]), m.groupValues[1].toInt(), m.groupValues[3], reference)
                ?.let { out += Fact(Slot.DATE, UNIT_DATE, it, m.range) }
        }
        for (m in EN_MONTH_YEAR.findAll(text)) {
            month(m.groupValues[2].toInt(), enMonth(m.groupValues[1]))?.let { out += Fact(Slot.DATE, UNIT_DATE, it, m.range) }
        }
        for (m in UK_MONTH_YEAR.findAll(text)) {
            month(m.groupValues[2].toInt(), ukMonth(m.groupValues[1]))?.let { out += Fact(Slot.DATE, UNIT_DATE, it, m.range) }
        }
        for (m in QUARTER_FIRST.findAll(text)) {
            val year = m.groupValues[3].ifEmpty { "20" + m.groupValues[2] }
            TrackerDates.normalize("$year-Q${m.groupValues[1]}")?.let { out += Fact(Slot.DATE, UNIT_DATE, it, m.range) }
        }
        for (m in YEAR_QUARTER.findAll(text)) {
            TrackerDates.normalize("${m.groupValues[1]}-Q${m.groupValues[2]}")?.let { out += Fact(Slot.DATE, UNIT_DATE, it, m.range) }
        }
        // Longest (most specific) match first, so "October 14, 2026" wins over "October 2026"-style overlaps.
        return out.sortedWith(compareByDescending<Fact> { it.span.last - it.span.first }.thenBy { it.span.first })
    }

    private fun day(year: Int, month: Int, day: Int): String? =
        runCatching { LocalDate.of(year, month, day).toString() }.getOrNull()

    private fun month(year: Int, month: Int): String? =
        if (month in 1..12) TrackerDates.normalize("%04d-%02d".format(year, month)) else null

    /** A day with its year, or — without one — the year that puts it closest to [reference]. */
    private fun dayGuessYear(month: Int, dayOfMonth: Int, year: String, reference: LocalDate): String? {
        if (month !in 1..12) return null
        if (year.isNotEmpty()) return day(year.toInt(), month, dayOfMonth)
        return (reference.year - 1..reference.year + 1)
            .mapNotNull { y -> runCatching { LocalDate.of(y, month, dayOfMonth) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(reference, it)) }
            ?.toString()
    }

    // ── counts ──────────────────────────────────────────────────────────────

    private val COUNT_NUMBER = Regex("""(?<![\p{L}\d.,$€£₴¥])(\d{1,3}(?:[ ,  ]\d{3})+|\d+)(?:\s?(%))?(?![\d])""")
    private val WORD = Regex("""[\p{L}\p{N}'’%-]+""")

    /** Keyword group → the whole (lowercased) words that name it. */
    private val COUNT_KEYWORDS: Map<String, Regex> = mapOf(
        UNIT_LAYOFFS to Regex("""layoffs?|laid|lay(?:s|ing)?|redundanc\p{L}*|cuts?|jobs?|звільн\p{L}*|скороч\p{L}*"""),
        UNIT_STAFF to Regex("""employees?|staff\p{L}*|workers?|developers?|працівник\p{L}*|співробітник\p{L}*|розробник\p{L}*""")
    )

    private fun counts(text: String): List<Fact> {
        val words = WORD.findAll(text).toList()
        val out = mutableListOf<Fact>()
        for (m in COUNT_NUMBER.findAll(text)) {
            val digits = m.groupValues[1].filter { it.isDigit() }
            val n = digits.toLongOrNull() ?: continue
            if (n == 0L) continue
            val percent = m.groupValues[2].isNotEmpty()
            val at = words.indexOfFirst { it.range.first >= m.range.first }.takeIf { it >= 0 } ?: continue
            val unit = nearestGroup(words, at, m.range.last) ?: continue
            out += Fact(Slot.COUNT, if (percent) "$unit%" else unit, n.toString(), m.range)
        }
        return out
    }

    /** The keyword group nearest to the number at word [at] within [COUNT_WINDOW] words either side. */
    private fun nearestGroup(words: List<MatchResult>, at: Int, numberEnd: Int): String? {
        // The number itself is one or more words ("1 900" is two); the window starts after it.
        var after = at
        while (after < words.size && words[after].range.first <= numberEnd) after++
        var best: Pair<Int, String>? = null
        fun consider(i: Int, distance: Int) {
            val w = words.getOrNull(i)?.value?.lowercase() ?: return
            val group = COUNT_KEYWORDS.entries.firstOrNull { (_, words) -> words.matches(w) }?.key ?: return
            // A tie goes to layoffs: "звільнила 120 працівників" counts the laid-off, not the staff.
            val b = best
            if (b == null || distance < b.first || (distance == b.first && group == UNIT_LAYOFFS)) best = distance to group
        }
        for (d in 1..COUNT_WINDOW) {
            consider(after + d - 1, d)
            consider(at - d, d)
        }
        return best?.second
    }

    const val UNIT_DATE = "date"
    const val UNIT_LAYOFFS = "layoffs"
    const val UNIT_STAFF = "staff"
}
