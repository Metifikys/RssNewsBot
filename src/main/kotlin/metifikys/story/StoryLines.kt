package metifikys.story

import metifikys.config.FirstLineConfig
import metifikys.config.HypeLineConfig
import metifikys.config.SourceLeaderboardConfig

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
