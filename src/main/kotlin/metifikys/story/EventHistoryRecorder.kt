package metifikys.story

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.EventHistoryRow
import metifikys.db.NewsDatabase
import metifikys.digest.TrackerDates
import metifikys.format.Hashtags
import metifikys.model.ShortlistItem
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * Writes `event_history` (`storyIntel.enabled`): the events that reached a channel, first
 * appearance only, kept for years. Fail-open — a write failure is a warning, never a failed digest.
 */
class EventHistoryRecorder(private val config: AppConfig, private val db: NewsDatabase) {

    /** Records the delivered [items] of [category]; a no-op unless `storyIntel.enabled`. */
    fun record(category: String, items: List<ShortlistItem>, at: LocalDateTime = LocalDateTime.now()) {
        if (!config.storyIntel.enabled || items.isEmpty()) return
        try {
            db.insertEventHistory(items.map { rowOf(category, it, at) })
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][StoryIntel] event_history write failed" }
        }
    }

    /**
     * One-off warm-up when `event_history` is still empty: the delivered events `covered_events`
     * remembers (14–90 days) plus the later `duplicate` sightings in `rejected_events`, oldest
     * first, so the counters do not start from zero. Returns the rows offered (insert-ignore, so
     * a rerun adds nothing). Never throws.
     */
    fun backfillIfEmpty(): Int {
        if (!config.storyIntel.enabled) return 0
        return try {
            if (!db.isEventHistoryEmpty()) return 0
            val rows = config.categories.filterValues { it.dedup != null }.keys.flatMap { category ->
                val covered = db.fetchRecentEvents(category, BACKFILL_DAYS, BACKFILL_LIMIT).map {
                    row(category, it.eventKey, it.subject, it.franchise, it.eventType, it.coreFact, null, it.newsworthiness, it.coveredAt)
                }
                val duplicates = db.fetchRecentRejectedEvents(category, BACKFILL_DAYS, BACKFILL_LIMIT).map {
                    row(category, it.eventKey, it.subject, it.franchise, it.eventType, it.coreFact, null, it.newsworthiness, it.extractedAt)
                }
                covered + duplicates
            }.sortedBy { it.coveredAt }
            db.insertEventHistory(rows)
            if (rows.isNotEmpty()) logger.info { "[StoryIntel] event_history warmed up with ${rows.size} row(s) from covered_events / rejected_events" }
            rows.size
        } catch (e: Exception) {
            logger.warn(e) { "[StoryIntel] event_history warm-up failed" }
            0
        }
    }

    companion object {
        private const val BACKFILL_DAYS = 3650L
        private const val BACKFILL_LIMIT = 100_000

        /**
         * The entity an event is about: the subject, or the franchise when there is none (as the
         * trackers pick it), without the trailing parenthetical Step 1 adds to describe the event —
         * "Ace Attorney 7 (чутки про розробку)" and "Ace Attorney 7 (анонс)" are one entity.
         */
        fun entityKeyOf(subject: String, franchise: String): String {
            val name = subject.trim().ifEmpty { franchise.trim() }
            return Hashtags.entityKey(TRAILING_PAREN.replace(name, "").ifBlank { name }) ?: ""
        }

        private val TRAILING_PAREN = Regex("""\s*\([^()]*\)\s*$""")

        fun rowOf(category: String, item: ShortlistItem, at: LocalDateTime): EventHistoryRow =
            row(
                category, item.eventKey, item.subject, item.franchise, item.eventType, item.coreFact,
                TrackerDates.normalize(item.eventDate), item.newsworthiness, at
            )

        private fun row(
            category: String,
            eventKey: String,
            subject: String,
            franchise: String,
            eventType: String,
            coreFact: String,
            eventDate: String?,
            newsworthiness: Int,
            at: LocalDateTime
        ) = EventHistoryRow(
            category = category,
            eventKey = eventKey,
            entityKey = entityKeyOf(subject, franchise),
            subject = subject,
            franchise = franchise,
            eventType = eventType,
            coreFact = coreFact,
            eventDate = eventDate,
            newsworthiness = newsworthiness,
            coveredAt = at
        )
    }
}
