package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.config.TrackerConfig
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.db.TrackerEntryRow
import metifikys.format.Hashtags
import metifikys.model.ShortlistItem
import metifikys.telegram.TelegramLinks
import metifikys.telegram.TelegramSender
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Maintains the living tracker messages (`categories.<name>.trackers`) after every delivered
 * digest: upserts one entry per shortlisted event of a tracked type, re-renders the message,
 * and edits it in place when the body changed — or posts (and pins) a fresh one when the old
 * message is gone. Never throws into the delivery path.
 *
 * The rendered body is hashed into `bot_state` next to the message id, so a digest that
 * changes nothing costs no Telegram call, and the "updated at" stamp in the header does not
 * count as a change.
 */
class TrackerUpdater(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val sender: TelegramSender,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() }
) {
    companion object {
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_DELAYED = "delayed"
        const val STATUS_TBA = "tba"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

        fun stateKey(category: String, trackerId: String) = "tracker:$category:$trackerId"
    }

    /**
     * Called by [DigestDeliverer] once per delivery with the shortlist items whose topics went
     * out and the `digest_messages` rows written for them.
     */
    fun afterDigest(category: String, sentItems: List<ShortlistItem>, sentRows: List<DigestMessageRow>) {
        val cat = config.categories[category] ?: return
        for (tracker in cat.trackers) {
            try {
                update(category, cat.channelId, tracker, sentItems, sentRows)
            } catch (e: Exception) {
                logger.warn(e) { "[Category:$category][Tracker:${tracker.id}] update failed — will retry after the next digest" }
            }
        }
    }

    private fun update(
        category: String,
        channelId: String,
        tracker: TrackerConfig,
        sentItems: List<ShortlistItem>,
        sentRows: List<DigestMessageRow>
    ) {
        val now = clock()
        val types = tracker.eventTypes.map { it.lowercase() }.toSet()
        val relevant = sentItems.filter { it.eventType.lowercase() in types }
        val existing = db.fetchTrackerEntries(category, tracker.id).associateBy { it.entityKey }.toMutableMap()
        var changed = 0
        for (item in relevant) {
            val name = item.subject.trim().ifEmpty { item.franchise.trim() }
            val key = Hashtags.entityKey(name) ?: continue
            val old = existing[key]
            val newDate = TrackerDates.normalize(item.eventDate)
            val date = newDate ?: old?.eventDate
            val moved = old?.eventDate != null && newDate != null && newDate != old.eventDate
            val prevDate = when {
                moved -> old!!.eventDate
                else -> old?.prevDate
            }
            val status = when {
                date == null -> STATUS_TBA
                moved && (TrackerDates.start(newDate) ?: LocalDate.MIN) > (TrackerDates.start(old!!.eventDate) ?: LocalDate.MIN) -> STATUS_DELAYED
                moved -> STATUS_SCHEDULED
                old?.status == STATUS_DELAYED && newDate == null -> STATUS_DELAYED
                else -> STATUS_SCHEDULED
            }
            val post = sentRows.firstOrNull { it.eventKey == item.eventKey }
            val row = TrackerEntryRow(
                category = category,
                trackerId = tracker.id,
                entityKey = key,
                title = name,
                eventDate = date,
                prevDate = prevDate,
                status = status,
                eventKey = item.eventKey,
                chatId = post?.chatId ?: old?.chatId,
                messageId = post?.messageId ?: old?.messageId,
                updatedAt = now
            )
            db.upsertTrackerEntry(row)
            existing[key] = row
            changed++
        }
        if (existing.isEmpty()) return   // nothing to show yet — never post an empty tracker

        val today = now.toLocalDate()
        val body = renderBody(tracker, channelId, existing.values, today)
        val hash = Integer.toHexString(body.hashCode())
        val key = stateKey(category, tracker.id)
        val state = db.getState(key)?.split('|')?.takeIf { it.size == 3 }
        if (state != null && state[2] == hash) {
            logger.debug { "[Category:$category][Tracker:${tracker.id}] unchanged ($changed item(s) touched)" }
            return
        }
        val text = "**${tracker.title}** · оновлено ${now.format(STAMP)}\n\n$body"

        if (state != null) {
            val messageId = state[1].toLongOrNull()
            if (messageId != null) {
                when (sender.editMessageText(channelId, messageId, text)) {
                    TelegramSender.EditResult.OK -> {
                        db.setState(key, "${state[0]}|$messageId|$hash")
                        logger.info { "[Category:$category][Tracker:${tracker.id}] edited msg $messageId ($changed item(s) touched)" }
                        return
                    }
                    TelegramSender.EditResult.FAILED -> {
                        logger.warn { "[Category:$category][Tracker:${tracker.id}] edit of msg $messageId failed — retrying after the next digest" }
                        return
                    }
                    TelegramSender.EditResult.NOT_FOUND ->
                        logger.info { "[Category:$category][Tracker:${tracker.id}] msg $messageId is gone — posting a fresh one" }
                }
            }
        }
        val ref = sender.sendToChannel(channelId, text, disablePreview = true).firstOrNull()
        if (ref == null) {
            logger.warn { "[Category:$category][Tracker:${tracker.id}] post failed — retrying after the next digest" }
            return
        }
        if (tracker.pinned) {
            state?.get(1)?.toLongOrNull()?.let { sender.unpinChatMessage(channelId, it) }
            if (!sender.pinChatMessage(channelId, ref.messageId)) {
                logger.warn { "[Category:$category][Tracker:${tracker.id}] pin failed — does the bot have the edit-messages right?" }
            }
        }
        db.setState(key, "${ref.chatId}|${ref.messageId}|$hash")
        logger.info { "[Category:$category][Tracker:${tracker.id}] posted msg ${ref.messageId} (${existing.size} entries)" }
    }

    /** The message minus its header stamp — what the change detection hashes. */
    internal fun renderBody(tracker: TrackerConfig, channelId: String, entries: Collection<TrackerEntryRow>, today: LocalDate): String {
        val horizon = today.plusDays(tracker.horizonDays)
        val upcoming = entries
            .filter { e -> val end = TrackerDates.end(e.eventDate); end == null || !end.isBefore(today) }
            .filter { e -> val start = TrackerDates.start(e.eventDate); start == null || !start.isAfter(horizon) }
            .sortedWith(
                compareBy<TrackerEntryRow>({ TrackerDates.start(it.eventDate) == null }, { TrackerDates.start(it.eventDate) ?: LocalDate.MAX })
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.title.lowercase() }
            )
            .take(tracker.maxEntries)
        val released = entries
            .filter { e ->
                val end = TrackerDates.end(e.eventDate) ?: return@filter false
                end.isBefore(today) && !end.isBefore(today.minusDays(tracker.releasedRetentionDays))
            }
            .sortedByDescending { TrackerDates.end(it.eventDate) }
        return buildString {
            if (upcoming.isEmpty()) appendLine("Поки що порожньо.")
            for (e in upcoming) appendLine(line(e, channelId))
            if (released.isNotEmpty()) {
                appendLine()
                appendLine("**Вийшло за тиждень**")
                for (e in released) appendLine(line(e, channelId))
            }
        }.trimEnd()
    }

    private fun line(e: TrackerEntryRow, channelId: String): String {
        val sb = StringBuilder()
        sb.append(TrackerDates.display(e.eventDate)).append(" · ").append(e.title)
        e.prevDate?.let { prev ->
            val arrow = if (e.status == STATUS_DELAYED) "⏳" else "⏩"
            sb.append(" $arrow було ").append(TrackerDates.display(prev))
        }
        val messageId = e.messageId
        if (messageId != null) {
            TelegramLinks.messageUrl(channelId, e.chatId, messageId)?.let { sb.append(" · [пост](").append(it).append(")") }
        }
        return sb.toString()
    }
}
