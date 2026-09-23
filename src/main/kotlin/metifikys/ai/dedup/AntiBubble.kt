package metifikys.ai.dedup

import metifikys.config.AntiBubbleConfig
import metifikys.config.DigestConfig
import metifikys.model.ShortlistItem
import metifikys.model.effectiveNewsworthiness
import java.time.LocalDateTime

/**
 * Anti-bubble pick (`dedup.digest.antiBubble`, see [AntiBubbleConfig]): among the events the
 * ranker left out of this digest, the most newsworthy one the audience's taste would keep out.
 * Pure — the caller supplies the affinity scorer and the time of the last explore post.
 */
object AntiBubble {

    /** `bot_state` key holding the time of the category's last delivered explore post. */
    fun stateKey(category: String): String = "antibubble:$category"

    /** Whether [intervalDays][AntiBubbleConfig.intervalDays] have passed since [lastSentAt] (null = never). */
    fun isDue(lastSentAt: LocalDateTime?, cfg: AntiBubbleConfig, now: LocalDateTime): Boolean =
        lastSentAt == null || !now.isBefore(lastSentAt.plusDays(cfg.intervalDays))

    data class Pick(val item: ShortlistItem, val affinity: Double)

    /**
     * The candidate from [pool] (the shortlist the ranker saw) that is not in [kept] (what it
     * kept): above the digestFit floor, its subject not already in [kept] (the same game twice),
     * newsworthiness >= [AntiBubbleConfig.minNewsworthiness], affinity <= [AntiBubbleConfig.maxAffinity].
     * The highest newsworthiness wins, then the higher composite, then the lower affinity. The
     * pick is returned as it was; the caller marks its lane.
     */
    fun pick(
        pool: List<ShortlistItem>,
        kept: List<ShortlistItem>,
        digestConfig: DigestConfig,
        cfg: AntiBubbleConfig,
        affinity: (ShortlistItem) -> Double
    ): Pick? {
        val keptKeys = kept.map { it.eventKey }.toSet()
        val keptSubjects = kept.map { it.subject.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return pool.asSequence()
            .filter { it.eventKey !in keptKeys }
            .filter { ShortlistRanker.passesFloor(it, digestConfig) }
            .filter { it.subject.trim().lowercase().let { s -> s.isEmpty() || s !in keptSubjects } }
            .filter { it.effectiveNewsworthiness >= cfg.minNewsworthiness }
            .map { Pick(it, affinity(it)) }
            .filter { it.affinity <= cfg.maxAffinity }
            .sortedWith(
                compareByDescending<Pick> { it.item.effectiveNewsworthiness }
                    .thenByDescending { ShortlistRanker.compositeScore(it.item, digestConfig) }
                    .thenBy { it.affinity }
            )
            .firstOrNull()
    }
}
