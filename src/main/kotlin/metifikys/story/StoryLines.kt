package metifikys.story

import metifikys.config.FirstLineConfig
import metifikys.config.HypeLineConfig
import metifikys.config.SourceLeaderboardConfig
import metifikys.digest.TrackerDates

/**
 * Footer lines built from story clusters (`post.storyLines`) and the weekly "who writes first"
 * block. Pure formatting and gating; the facts come from [Hype], [Genealogy] and
 * [SourceLeaderboard]. Ukrainian, like the rest of the channel copy.
 */
object StoryLines {

    /** `📢 9 видань · 1 першоджерело` — null below `minOutlets` (small stories are not "hype"). */
    fun hype(r: Hype.Result?, cfg: HypeLineConfig?): String? {
        if (r == null || cfg == null || !cfg.enabled || r.outlets < cfg.minOutlets) return null
        return outlets(r)
    }

    /**
     * The outlets fact without a gate (the weekly roundup's badge): `📢 12 видань · усі з посиланням
     * на Insider Gaming`, `📢 9 видань · 2 першоджерела`, or just `📢 5 видань` when nothing is original
     * and the credits disagree.
     */
    fun outlets(r: Hype.Result): String {
        val head = "📢 ${r.outlets} ${plural(r.outlets, "видання", "видання", "видань")}"
        return when {
            r.commonSource != null -> "$head · усі з посиланням на ${r.commonSource}"
            r.originals > 0 -> "$head · ${r.originals} ${plural(r.originals, "першоджерело", "першоджерела", "першоджерел")}"
            else -> head
        }
    }

    /**
     * The first outlet when it is worth saying: at least `minOutlets` outlets and a lead of at
     * least `minLeadMinutes` over the second one — anything closer is a tie, not a scoop.
     */
    fun first(r: Genealogy.Result?, cfg: FirstLineConfig?): Genealogy.Result? {
        if (r == null || cfg == null || !cfg.enabled || r.outlets < cfg.minOutlets) return null
        val lead = r.leadMinutes ?: return null
        return if (lead >= cfg.minLeadMinutes) r else null
    }

    /**
     * The story footer line: `📢 9 видань · 1 першоджерело · 🥇 insider-gaming.com (+2 год)` when both
     * facts pass their gates (one line saves the caption budget), else whichever passed —
     * `🥇 першим був insider-gaming.com (+2 год)` on its own — or null.
     */
    fun line(hype: Hype.Result?, hypeCfg: HypeLineConfig?, first: Genealogy.Result?, firstCfg: FirstLineConfig?): String? {
        val h = hype(hype, hypeCfg)
        val f = first(first, firstCfg)
        return when {
            h != null && f != null -> "$h · 🥇 ${f.firstDomain} (${lead(f.leadMinutes!!)})"
            h != null -> h
            f != null -> "🥇 першим був ${f.firstDomain} (${lead(f.leadMinutes!!)})"
            else -> null
        }
    }

    /** `+45 хв`, `+2 год`, `+3 дн.` — rounded down; hours up to two days. */
    fun lead(minutes: Long): String = when {
        minutes < 60 -> "+$minutes хв"
        minutes < 48 * 60 -> "+${minutes / 60} год"
        else -> "+${minutes / (24 * 60)} дн."
    }

    /**
     * The weekly block: `🥇 Хто пише першим (30 дн.): a.com — 12 з 20 · b.com — 8 з 25`, plus with
     * `showCopiers` a second line of the outlets most often behind (`🐢 Частіше доганяють: c.com
     * (+3 год)`). Null when no outlet made the cut.
     */
    fun leaderboard(rows: List<SourceLeaderboard.Row>, cfg: SourceLeaderboardConfig): String? {
        val top = rows.filter { it.firsts > 0 }.take(cfg.top)
        if (top.isEmpty()) return null
        val sb = StringBuilder("🥇 Хто пише першим (${cfg.lookbackDays} дн.): ")
        sb.append(top.joinToString(" · ") { "${it.domain} — ${it.firsts} з ${it.events}" })
        if (cfg.showCopiers) {
            val shown = top.map { it.domain }.toSet()
            val behind = rows.filter { it.domain !in shown && it.medianLagMinutes != null }
                .sortedWith(compareBy<SourceLeaderboard.Row> { it.firstRate }.thenByDescending { it.events })
                .take(cfg.top)
            if (behind.isNotEmpty()) {
                sb.append("\n🐢 Частіше доганяють: ")
                sb.append(behind.joinToString(" · ") { r ->
                    val reprints = r.reprintShare?.let { ", передруків ${Math.round(it * 100)}%" } ?: ""
                    "${r.domain} (${lead(r.medianLagMinutes!!)}$reprints)"
                })
            }
        }
        return sb.toString()
    }

    /**
     * `⚖️ Джерела розходяться: дата 14.10.2026 (ign.com, eurogamer.net) чи 21.10.2026 (pcgamer.com)` —
     * the first of [conflicts] (dates before prices before counts); null when there is none.
     */
    fun conflict(conflicts: List<ConflictDetector.Conflict>): String? {
        val c = conflicts.firstOrNull() ?: return null
        val values = c.values.take(MAX_CONFLICT_VALUES).joinToString(" чи ") { v ->
            val shown = v.domains.take(MAX_CONFLICT_DOMAINS).joinToString(", ")
            val more = if (v.domains.size > MAX_CONFLICT_DOMAINS) " +${v.domains.size - MAX_CONFLICT_DOMAINS}" else ""
            "${factValue(v.fact)} ($shown$more)"
        }
        return "⚖️ Джерела розходяться: ${slotLabel(c.slot, c.unit)} $values"
    }

    private fun slotLabel(slot: FactSlots.Slot, unit: String): String = when (slot) {
        FactSlots.Slot.DATE -> "дата"
        FactSlots.Slot.PRICE -> "ціна"
        FactSlots.Slot.COUNT -> when (unit) {
            FactSlots.UNIT_LAYOFFS -> "звільнення"
            FactSlots.UNIT_STAFF -> "штат"
            FactSlots.UNIT_LAYOFFS + "%" -> "частка звільнених"
            else -> "частка штату"
        }
    }

    /** `14.10.2026`, `$69.99`, `€1.5 млрд`, `70 CAD`, `300`, `10%`. */
    fun factValue(f: FactSlots.Fact): String = when (f.slot) {
        FactSlots.Slot.DATE -> TrackerDates.display(f.value)
        FactSlots.Slot.COUNT -> if (f.unit.endsWith("%")) "${f.value}%" else f.value
        FactSlots.Slot.PRICE -> {
            val amount = f.value.toBigDecimalOrNull()
            val shown = when {
                amount == null -> f.value
                amount >= BILLION -> "${(amount.divide(BILLION)).stripTrailingZeros().toPlainString()} млрд"
                amount >= MILLION -> "${(amount.divide(MILLION)).stripTrailingZeros().toPlainString()} млн"
                else -> f.value
            }
            when (f.unit) {
                "USD" -> "$$shown"
                "EUR" -> "€$shown"
                "GBP" -> "£$shown"
                "UAH" -> "₴$shown"
                else -> "$shown ${f.unit}"
            }
        }
    }

    private const val MAX_CONFLICT_VALUES = 3
    private const val MAX_CONFLICT_DOMAINS = 3
    private val MILLION = java.math.BigDecimal(1_000_000)
    private val BILLION = java.math.BigDecimal(1_000_000_000)

    /** `🌀 анонсовано вчетверте, дати досі немає (вперше: 03.2025)`; null without a result. */
    fun vaporware(r: Vaporware.Result?): String? {
        if (r == null) return null
        return "🌀 анонсовано ${ordinalTimes(r.number)}, дати досі немає (вперше: ${r.firstAt.format(MONTH_YEAR)})"
    }

    /** `вдруге` … `удесяте`, then `уже 11-й раз`. */
    fun ordinalTimes(n: Int): String = ORDINAL_TIMES[n] ?: "уже $n-й раз"

    private val ORDINAL_TIMES = mapOf(
        1 to "вперше", 2 to "вдруге", 3 to "втретє", 4 to "вчетверте", 5 to "вп'яте", 6 to "ушосте",
        7 to "усьоме", 8 to "увосьме", 9 to "удев'яте", 10 to "удесяте"
    )
    private val MONTH_YEAR = java.time.format.DateTimeFormatter.ofPattern("MM.yyyy")

    /** Ukrainian plural: 1 / 21 → [one], 2–4 / 22–24 → [few], else [many]. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
