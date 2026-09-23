package metifikys.story

import metifikys.config.HypeLineConfig

/**
 * Footer lines built from story clusters (`post.storyLines`). Pure formatting and gating; the
 * facts come from [Hype] and friends. Ukrainian, like the rest of the channel copy.
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
