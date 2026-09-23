package metifikys.story

import metifikys.db.EventArticleRow
import metifikys.digest.TrackerDates
import metifikys.story.FactSlots.Fact
import metifikys.story.FactSlots.Slot
import java.math.BigDecimal
import java.math.MathContext

/**
 * "⚖️ Джерела розходяться" (plan item 6, v1): the members of one story cluster that state different
 * values for the same fact slot ([FactSlots]). Pure and deterministic.
 *
 * Per article and slot (a slot is the fact kind plus its unit — a currency, a count keyword group),
 * one value: an article whose values in a slot disagree among themselves (standard vs deluxe price,
 * two regions' dates) says nothing about that slot; mutually compatible ones collapse to the most
 * precise. A value's support is the number of distinct outlets stating it. A conflict is two or more
 * values, each with at least `minSupport` outlets, that are pairwise incompatible:
 * - dates: their periods (a day, a month, a quarter) do not overlap even when widened by
 *   `dateToleranceDays` — `Q4 2026` and `2026-10-14` agree, `14.10` and `21.10` do not;
 * - prices (one currency; different currencies are never compared) and counts: more than 1% apart.
 */
object ConflictDetector {

    /** One value of a slot and the outlets that state it, in order of their first article. */
    data class Value(val fact: Fact, val domains: List<String>)

    data class Conflict(val slot: Slot, val unit: String, val values: List<Value>)

    /** Relative gap under which two prices / counts are the same value (rounding, conversions). */
    private val SAME_AMOUNT = BigDecimal("0.01")

    fun detect(cluster: EventCluster, texts: Map<String, String>, minSupport: Int, dateToleranceDays: Int): List<Conflict> =
        conflicts(supports(cluster, texts, dateToleranceDays, skipArticle = null), minSupport, dateToleranceDays)

    /**
     * The values of the late article [lateArticleId] (a Layer 2 reprint) that clash with every value
     * the rest of [cluster] states in that slot — a candidate for a missed `meaningful_update`.
     * Returns (the late value, the cluster's values) per clashing slot.
     */
    fun late(
        cluster: EventCluster,
        texts: Map<String, String>,
        lateArticleId: Int,
        dateToleranceDays: Int
    ): List<Pair<Value, List<Value>>> {
        val late = cluster.members.firstOrNull { it.articleId == lateArticleId } ?: return emptyList()
        val lateFacts = factsOf(late, texts, dateToleranceDays)
        if (lateFacts.isEmpty()) return emptyList()
        val rest = supports(cluster, texts, dateToleranceDays, skipArticle = lateArticleId)
        val out = mutableListOf<Pair<Value, List<Value>>>()
        for ((slotKey, fact) in lateFacts) {
            val known = rest[slotKey].orEmpty()
            if (known.isEmpty()) continue
            if (known.none { compatible(it.fact, fact, dateToleranceDays) }) {
                out += Value(fact, listOf(cluster.domainOf(late))) to known
            }
        }
        return out
    }

    fun compatible(a: Fact, b: Fact, dateToleranceDays: Int): Boolean {
        if (a.slot != b.slot || a.unit != b.unit) return true
        if (a.value == b.value) return true
        return when (a.slot) {
            Slot.DATE -> {
                val aStart = TrackerDates.start(a.value) ?: return true
                val aEnd = TrackerDates.end(a.value) ?: return true
                val bStart = TrackerDates.start(b.value) ?: return true
                val bEnd = TrackerDates.end(b.value) ?: return true
                val tol = dateToleranceDays.toLong()
                !aStart.minusDays(tol).isAfter(bEnd) && !bStart.minusDays(tol).isAfter(aEnd)
            }
            Slot.PRICE, Slot.COUNT -> {
                val x = a.value.toBigDecimalOrNull() ?: return true
                val y = b.value.toBigDecimalOrNull() ?: return true
                val max = x.max(y)
                if (max.signum() == 0) return true
                (x - y).abs().divide(max, MathContext.DECIMAL64) <= SAME_AMOUNT
            }
        }
    }

    /** slot key → values with their supporting outlets, over every member except [skipArticle]. */
    private fun supports(
        cluster: EventCluster,
        texts: Map<String, String>,
        dateToleranceDays: Int,
        skipArticle: Int?
    ): Map<String, List<Value>> {
        // slot key → value → (a representative fact, outlets in first-seen order)
        val acc = LinkedHashMap<String, LinkedHashMap<String, Pair<Fact, LinkedHashSet<String>>>>()
        for (m in cluster.members.sortedBy { EventCluster.timeOf(it) }) {
            if (m.articleId == skipArticle) continue
            val domain = cluster.domainOf(m)
            if (domain.isEmpty()) continue
            for ((slotKey, fact) in factsOf(m, texts, dateToleranceDays)) {
                val byValue = acc.getOrPut(slotKey) { LinkedHashMap() }
                byValue.getOrPut(fact.value) { fact to LinkedHashSet() }.second += domain
            }
        }
        return acc.mapValues { (_, byValue) -> byValue.values.map { (f, d) -> Value(f, d.toList()) } }
    }

    private fun conflicts(bySlot: Map<String, List<Value>>, minSupport: Int, dateToleranceDays: Int): List<Conflict> {
        val out = mutableListOf<Conflict>()
        for ((_, values) in bySlot) {
            val candidates = values.filter { it.domains.size >= minSupport }
                .sortedByDescending { it.domains.size } // stable: ties keep the earliest-stated value first
            // Each candidate in turn seeds a greedy pick of values clashing with everything picked so
            // far; the first seed that finds a clash wins. A vague value agreeing with both sides
            // ("Q4" vs 14.10 and 21.10) must not hide the clash between the two precise ones.
            val picked = candidates.asSequence().map { seed ->
                val p = mutableListOf(seed)
                for (v in candidates) {
                    if (v !== seed && p.all { !compatible(it.fact, v.fact, dateToleranceDays) }) p += v
                }
                p
            }.firstOrNull { it.size >= 2 } ?: continue
            out += Conflict(picked[0].fact.slot, picked[0].fact.unit, picked)
        }
        return out.sortedBy { it.slot.ordinal }
    }

    /** Log form: `slot=date values=2026-10-14{ign.com,eurogamer.net} | 2026-10-21{pcgamer.com}`. */
    fun describe(slot: Slot, unit: String, values: List<Value>): String {
        val kind = if (slot == Slot.DATE) "date" else "${slot.name.lowercase()}/$unit"
        return "slot=$kind values=" + values.joinToString(" | ") { "${it.fact.value}{${it.domains.joinToString(",")}}" }
    }

    /**
     * One value per slot key for member [m]: its facts grouped by slot and unit; a group whose
     * values are not all mutually compatible is dropped, a compatible one keeps its most precise value.
     */
    private fun factsOf(m: EventArticleRow, texts: Map<String, String>, dateToleranceDays: Int): Map<String, Fact> {
        val text = texts[m.link] ?: return emptyMap()
        val facts = FactSlots.extract(text, EventCluster.timeOf(m).toLocalDate())
        val out = LinkedHashMap<String, Fact>()
        for ((key, group) in facts.groupBy { "${it.slot}:${it.unit}" }) {
            val distinct = group.distinctBy { it.value }
            val agree = distinct.all { a -> distinct.all { b -> compatible(a, b, dateToleranceDays) } }
            if (!agree) continue
            out[key] = distinct.minByOrNull { precision(it) } ?: continue
        }
        return out
    }

    /** Smaller = more precise: a day before a month before a quarter; prices / counts are all exact. */
    private fun precision(f: Fact): Long {
        if (f.slot != Slot.DATE) return 0
        val start = TrackerDates.start(f.value) ?: return Long.MAX_VALUE
        val end = TrackerDates.end(f.value) ?: return Long.MAX_VALUE
        return java.time.temporal.ChronoUnit.DAYS.between(start, end)
    }
}
