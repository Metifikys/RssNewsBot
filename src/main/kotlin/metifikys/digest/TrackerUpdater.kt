package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.config.TrackerConfig
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.db.TrackerEntryRow
import metifikys.format.Hashtags
import metifikys.format.RichHtml
import metifikys.format.TopicFormatter
import metifikys.model.ShortlistItem
import metifikys.telegram.TelegramLinks
import metifikys.telegram.TelegramSender
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

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
    /** The zone the rich tracker's dates are anchored in (the bot's own, like every other date it shows). */
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> LocalDateTime = { LocalDateTime.now() }
) {
    companion object {
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_DELAYED = "delayed"
        const val STATUS_TBA = "tba"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
        private const val DAY_LENGTH = 10

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
        // trackers[].rich: the same entries as rich HTML; hashed with a prefix so switching the flag edits the message.
        val richBody = if (tracker.rich) renderRich(tracker, channelId, existing.values, today) else null
        val hash = Integer.toHexString((richBody?.let { "rich:$it" } ?: body).hashCode())
        val key = stateKey(category, tracker.id)
        val state = db.getState(key)?.split('|')?.takeIf { it.size == 3 }
        if (state != null && state[2] == hash) {
            logger.debug { "[Category:$category][Tracker:${tracker.id}] unchanged ($changed item(s) touched)" }
            return
        }
        val text = "**${tracker.title}** · оновлено ${now.format(STAMP)}\n\n$body"
        val richText = richBody?.let {
            RichHtml.heading(3, tracker.title) + RichHtml.paragraphHtml("<i>Оновлено ${RichHtml.time(now, "r", zone)}</i>") + it
        }

        if (state != null) {
            val messageId = state[1].toLongOrNull()
            if (messageId != null) {
                val result = if (richText == null) {
                    sender.editMessageText(channelId, messageId, text)
                } else {
                    when (val rich = sender.editRichMessage(channelId, messageId, richText)) {
                        TelegramSender.EditResult.REJECTED -> {
                            logger.warn { "[Category:$category][Tracker:${tracker.id}] Telegram refused the rich tracker — editing it as text" }
                            sender.editMessageText(channelId, messageId, text)
                        }
                        else -> rich
                    }
                }
                when (result) {
                    TelegramSender.EditResult.OK -> {
                        db.setState(key, "${state[0]}|$messageId|$hash")
                        logger.info { "[Category:$category][Tracker:${tracker.id}] edited msg $messageId ($changed item(s) touched)" }
                        return
                    }
                    TelegramSender.EditResult.FAILED, TelegramSender.EditResult.REJECTED -> {
                        logger.warn { "[Category:$category][Tracker:${tracker.id}] edit of msg $messageId failed — retrying after the next digest" }
                        return
                    }
                    TelegramSender.EditResult.NOT_FOUND ->
                        logger.info { "[Category:$category][Tracker:${tracker.id}] msg $messageId is gone — posting a fresh one" }
                }
            }
        }
        val richRef = richText?.let { sender.sendRichMessage(channelId, it) }
        if (richText != null && richRef == null) {
            logger.warn { "[Category:$category][Tracker:${tracker.id}] Telegram refused the rich tracker — posting it as text" }
        }
        val ref = richRef ?: sender.sendToChannel(channelId, text, disablePreview = true).firstOrNull()
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

    /** The entries a tracker shows: upcoming within the horizon (dated first, by date) and recently released. */
    private class Selection(val upcoming: List<TrackerEntryRow>, val released: List<TrackerEntryRow>)

    private fun select(tracker: TrackerConfig, entries: Collection<TrackerEntryRow>, today: LocalDate): Selection {
        val horizon = today.plusDays(tracker.horizonDays)
        val upcoming = entries
            .filter { e -> tracker.showUndated || e.eventDate != null }
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
        return Selection(upcoming, released)
    }

    /**
     * `trackers[].rich`: the rich HTML under the header — a table per section (this week and next
     * week by day-precise date, then everything later, then the released), the undated entries
     * collapsed. A day-precise date is a date-time entity (`wd`), with a self-updating countdown
     * (`r`); a month / quarter / year stays text. Hashed for change detection, like [renderBody].
     */
    internal fun renderRich(tracker: TrackerConfig, channelId: String, entries: Collection<TrackerEntryRow>, today: LocalDate): String {
        val s = select(tracker, entries, today)
        val thisWeekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val nextWeekEnd = thisWeekEnd.plusWeeks(1)
        val dated = s.upcoming.filter { it.eventDate != null }
        val thisWeek = dated.filter { day(it)?.let { d -> !d.isAfter(thisWeekEnd) } == true }
        val nextWeek = dated.filter { day(it)?.let { d -> d.isAfter(thisWeekEnd) && !d.isAfter(nextWeekEnd) } == true }
        val later = dated - thisWeek.toSet() - nextWeek.toSet()
        val undated = s.upcoming.filter { it.eventDate == null }
        val header = listOf("Коли", "Що", "")
        return buildString {
            if (s.upcoming.isEmpty() && s.released.isEmpty()) append(RichHtml.paragraph("Поки що порожньо."))
            for ((name, rows) in listOf("Цього тижня" to thisWeek, "Наступного тижня" to nextWeek, "Далі" to later)) {
                if (rows.isEmpty()) continue
                append(RichHtml.heading(4, name))
                append(RichHtml.table(header, rows.map { e -> listOf(whenCell(e), whatCell(e, channelId), countdown(e, today)) }))
            }
            if (s.released.isNotEmpty()) {
                append(RichHtml.heading(4, "Вийшло за тиждень"))
                append(RichHtml.table(listOf("Коли", "Що"), s.released.map { e -> listOf(whenCell(e), whatCell(e, channelId)) }))
            }
            if (undated.isNotEmpty()) {
                append(RichHtml.details("Без дати · ${undated.size}", RichHtml.list(undated.map { whatCell(it, channelId) }, ordered = false)))
            }
        }
    }

    /** The entry's date when it is a single day (not a month / quarter / year). */
    private fun day(e: TrackerEntryRow): LocalDate? =
        TrackerDates.normalize(e.eventDate)?.takeIf { it.length == DAY_LENGTH }?.let { LocalDate.parse(it) }

    private fun whenCell(e: TrackerEntryRow): String =
        day(e)?.let { RichHtml.time(it, "wd", zone) } ?: TopicFormatter.escapeHtml(TrackerDates.display(e.eventDate))

    private fun countdown(e: TrackerEntryRow, today: LocalDate): String =
        day(e)?.takeIf { !it.isBefore(today) }?.let { RichHtml.time(it, "r", zone) }.orEmpty()

    /** The title, linked to its latest post, with `⏳ було 14.10.2026` after a move. */
    private fun whatCell(e: TrackerEntryRow, channelId: String): String {
        val url = e.messageId?.let { TelegramLinks.messageUrl(channelId, e.chatId, it) }
        val title = if (url != null) RichHtml.link(RichHtml.Link(e.title, url)) else TopicFormatter.escapeHtml(e.title)
        val moved = e.prevDate?.let { prev -> " " + (if (e.status == STATUS_DELAYED) "⏳" else "⏩") + " було " + TrackerDates.display(prev) }
        return title + TopicFormatter.escapeHtml(moved.orEmpty())
    }

    /** The message minus its header stamp — what the change detection hashes. */
    internal fun renderBody(tracker: TrackerConfig, channelId: String, entries: Collection<TrackerEntryRow>, today: LocalDate): String {
        val selection = select(tracker, entries, today)
        val upcoming = selection.upcoming
        val released = selection.released
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
