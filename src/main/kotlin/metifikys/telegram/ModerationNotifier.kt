package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.AdminFeedbackRow
import metifikys.db.DigestMessageRow
import metifikys.db.ExplicitSignal
import metifikys.db.NewsDatabase
import metifikys.digest.MuteRegistry
import metifikys.feedback.AffinityAggregator
import metifikys.feedback.MuteSuggester
import metifikys.format.TopicFormatter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

private val logger = KotlinLogging.logger {}

/** One published post as its moderation message sees it: the first message of the topic. */
data class ModeratedPost(
    val category: String,
    val chatId: Long,
    val messageId: Long,
    /** Null on the legacy single-step path — no event, so no mute or more/less buttons. */
    val eventKey: String?,
    val title: String,
    /** The franchise (else subject) as a mute keyword, null when none is usable. */
    val muteKeyword: String?,
    val url: String?
)

/**
 * Post moderation from the admin chat (`admin.moderation`). The owner is the main but not the
 * only reader of the channels, so there are no buttons under channel posts; instead, after each
 * digest, every published post gets one silent compact message in the PRIVATE admin chat:
 *
 *     🎮 gaming · Hollow Knight: Silksong: реліз 14.10 · пост
 *     [🗑 Видалити] [🔇 Mute: silksong]
 *     [👍 Більше такого] [👎 Менше такого]
 *
 * - 🗑 asks for confirmation, then deletes every chunk of the post from the channel (Bot API: 48 h
 *   at most), drops their `digest_messages` rows (so replies and affinity stop pointing at a dead
 *   post), unlinks tracker lines, and records a strong negative explicit signal. `covered_events`
 *   stays, so the event is not published again.
 * - 🔇 asks in-category / global, then writes the same DB mute as `/mute` (source `moderation`).
 * - 👍 / 👎 set the post's explicit signal in `admin_feedback` (last press wins, pressing the same
 *   one again clears it) and make the next maintenance tick recompute affinity.
 *
 * Sending runs on its own [executor] after the digest has been delivered, so channel delivery
 * never waits for the admin chat; admin messages are paced per chat (≥ 1 s), which only spaces
 * this queue out (20 posts ≈ 20 s) and can delay an admin command reply behind it. Every outcome
 * is shown by editing the keyboard; the message text is never rebuilt. Fail-open throughout.
 */
class ModerationNotifier(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val mutes: MuteRegistry,
    private val sender: TelegramSender,
    private val executor: Executor,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    private val cfg = config.admin.moderation
    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun appliesTo(category: String): Boolean =
        cfg.enabled && config.admin.statusChatId != null && (cfg.categories.isEmpty() || category in cfg.categories)

    /** Queues one moderation message per post. Never throws. */
    fun afterDigest(category: String, posts: List<ModeratedPost>) {
        if (posts.isEmpty() || !appliesTo(category)) return
        try {
            executor.execute { send(posts) }
        } catch (e: RejectedExecutionException) {
            logger.warn { "[Moderation:$category] notifier stopped — ${posts.size} post(s) without a moderation message" }
        }
    }

    private fun send(posts: List<ModeratedPost>) {
        val chat = config.admin.statusChatId ?: return
        for (p in posts) {
            try {
                val emoji = config.categories[p.category]?.emoji.orEmpty()
                val state = KeyboardState(p.chatId, p.messageId, p.muteKeyword, muted = false, signal = null,
                    canDelete = true, withFeedback = p.eventKey != null)
                if (sender.sendMessageWithKeyboard(
                        chat, render(p, emoji), keyboard(state), silent = true, html = true, threadId = config.admin.threads.status
                    ) == null) {
                    logger.warn { "[Moderation:${p.category}] message for ${p.chatId}/${p.messageId} not sent" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "[Moderation:${p.category}] message for ${p.chatId}/${p.messageId} failed" }
            }
        }
    }

    /** `mod:<action>:<post chat>:<post message>` from a moderation keyboard. */
    fun handleCallback(cb: IncomingCallback, parts: List<String>) {
        val action = parts.getOrNull(0) ?: return
        val chat = parts.getOrNull(1)?.toLongOrNull() ?: return
        val msg = parts.getOrNull(2)?.toLongOrNull() ?: return
        val adminChat = cb.chatId?.toString() ?: return
        val adminMsg = cb.messageId ?: return
        fun show(keyboard: List<List<InlineButton>>) {
            sender.editMessageReplyMarkup(adminChat, adminMsg, keyboard)
        }
        if (action == "noop") return
        val row = db.fetchDigestMessage(chat, msg)
        if (row == null) {
            show(listOf(listOf(InlineButton("пост уже видалено або старий", "mod:noop:$chat:$msg"))))
            return
        }
        when (action) {
            "del" -> show(
                listOf(
                    listOf(
                        InlineButton("✅ Так, видалити", "mod:delok:$chat:$msg"),
                        InlineButton("↩ Скасувати", "mod:back:$chat:$msg")
                    )
                )
            )
            "delok" -> show(delete(row, cb.fromId))
            "back" -> show(mainKeyboard(row))
            "mute" -> {
                val kw = keywordOf(row) ?: return show(mainKeyboard(row))
                show(
                    listOf(
                        listOf(
                            InlineButton("🔇 У ${row.category}", "mod:mcat:$chat:$msg"),
                            InlineButton("🌐 Глобально", "mod:mglob:$chat:$msg"),
                            InlineButton("↩ Скасувати", "mod:back:$chat:$msg")
                        )
                    )
                )
                logger.debug { "[Moderation] mute '$kw' offered for $chat/$msg" }
            }
            "mcat", "mglob" -> {
                val kw = keywordOf(row) ?: return show(mainKeyboard(row))
                val scope = if (action == "mcat") row.category else null
                val note = when (val res = mutes.add(kw, scope, cb.fromId, SOURCE)) {
                    is MuteRegistry.AddResult.Added -> "🔇 «$kw» зам'ючено: ${scope ?: "глобально"}"
                    is MuteRegistry.AddResult.AlreadyMuted -> "🔇 «$kw» уже зам'ючено"
                    is MuteRegistry.AddResult.Invalid -> "⚠️ ${res.reason}"
                }
                show(mainKeyboard(row, note))
            }
            "up", "down" -> {
                if (row.eventKey == null) return show(mainKeyboard(row))
                val wanted = if (action == "up") ExplicitSignal.MORE else ExplicitSignal.LESS
                val current = db.fetchAdminFeedback(chat, msg)?.signal
                if (current == wanted) {
                    db.deleteAdminFeedback(chat, msg)
                } else {
                    db.upsertAdminFeedback(AdminFeedbackRow(chat, msg, row.category, row.eventKey, row.sentAt, wanted, clock()))
                }
                touchAffinity()
                logger.info { "[Moderation:${row.category}] $chat/$msg ${if (current == wanted) "cleared" else action} by ${cb.fromId}" }
                show(mainKeyboard(row))
            }
            else -> logger.warn { "[Moderation] unknown action '$action'" }
        }
    }

    /** Deletes every chunk of the post; returns the keyboard that reports the outcome. */
    private fun delete(row: DigestMessageRow, by: Long): List<List<InlineButton>> {
        if (row.sentAt.isBefore(clock().minusHours(DELETE_WINDOW_HOURS))) {
            return mainKeyboard(row, "⚠️ старше 48 год — Telegram не дає видалити, прибери вручну")
        }
        val chunks = db.fetchDigestMessagesOfPost(row).ifEmpty { listOf(row) }
        val deleted = chunks.filter { sender.deleteMessage(row.chatId.toString(), it.messageId) }
        if (deleted.isEmpty()) return mainKeyboard(row, "⚠️ не вдалося: немає права або пост уже зник")
        db.deleteDigestMessages(deleted.map { it.chatId to it.messageId })
        db.clearTrackerPostRefs(row.chatId, deleted.map { it.messageId })
        if (row.eventKey != null) {
            db.upsertAdminFeedback(AdminFeedbackRow(row.chatId, row.messageId, row.category, row.eventKey, row.sentAt, ExplicitSignal.DELETED, clock()))
            touchAffinity()
        }
        logger.info { "[Moderation:${row.category}] deleted ${deleted.size}/${chunks.size} message(s) of ${row.chatId}/${row.messageId} by $by" }
        val partial = if (deleted.size < chunks.size) " (${deleted.size}/${chunks.size})" else ""
        return listOf(listOf(InlineButton("🗑 видалено ${clock().format(hhmm)}$partial", "mod:noop:${row.chatId}:${row.messageId}")))
    }

    /** Next maintenance tick recomputes affinity instead of waiting out `recomputeHours`. */
    private fun touchAffinity() {
        runCatching { db.deleteState(AffinityAggregator.STATE_KEY) }
    }

    private fun mainKeyboard(row: DigestMessageRow, note: String? = null): List<List<InlineButton>> {
        val kw = keywordOf(row)
        val state = KeyboardState(
            chatId = row.chatId,
            messageId = row.messageId,
            muteKeyword = kw,
            muted = kw != null && mutes.covering(kw, row.category) != null,
            signal = db.fetchAdminFeedback(row.chatId, row.messageId)?.signal,
            canDelete = !row.sentAt.isBefore(clock().minusHours(DELETE_WINDOW_HOURS)),
            withFeedback = row.eventKey != null,
            note = note
        )
        return keyboard(state)
    }

    /** The post's franchise (else subject) as a mute keyword, from its covered event. */
    private fun keywordOf(row: DigestMessageRow): String? {
        val key = row.eventKey ?: return null
        val event = runCatching {
            db.fetchCoveredEventsByKeys(row.category, listOf(key), LocalDateTime.of(2000, 1, 1, 0, 0)).firstOrNull()
        }.getOrNull() ?: return null
        return muteKeywordFor(event.franchise, event.subject)
    }

    /** What a keyboard shows; everything else is derived from the post ids. */
    data class KeyboardState(
        val chatId: Long,
        val messageId: Long,
        val muteKeyword: String?,
        val muted: Boolean,
        val signal: Int?,
        val canDelete: Boolean,
        val withFeedback: Boolean,
        val note: String? = null
    )

    companion object {
        const val SOURCE = "moderation"
        const val DELETE_WINDOW_HOURS = 48L
        private const val TITLE_MAX = 80
        private const val KEYWORD_LABEL_MAX = 30

        /** Franchise first, subject as the fallback, through the same rule as mute proposals. */
        fun muteKeywordFor(franchise: String?, subject: String?): String? =
            franchise?.takeIf { it.isNotBlank() }?.let { MuteSuggester.keywordFor(it) }
                ?: subject?.takeIf { it.isNotBlank() }?.let { MuteSuggester.keywordFor(it) }

        /** First non-blank line of a topic without Markdown, cut to 80 chars. */
        fun titleOf(topic: String): String {
            val line = topic.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
            val plain = TopicFormatter.MARKDOWN_LINK.replace(line) { it.groupValues[1] }
                .replace("**", "").replace("__", "").replace("`", "").trim()
            if (plain.length <= TITLE_MAX) return plain
            var end = TITLE_MAX - 1
            if (plain[end - 1].isHighSurrogate()) end--
            return plain.substring(0, end).trimEnd() + "…"
        }

        /** `🎮 gaming · Title · <a href=…>пост</a>` in Telegram HTML. */
        fun render(p: ModeratedPost, emoji: String): String {
            val link = p.url?.let { " · <a href=\"${TopicFormatter.escapeHtml(it).replace("\"", "&quot;")}\">пост</a>" }.orEmpty()
            return "${emoji.ifEmpty { "📰" }} ${TopicFormatter.escapeHtml(p.category)} · ${TopicFormatter.escapeHtml(p.title)}$link"
        }

        fun keyboard(s: KeyboardState): List<List<InlineButton>> {
            val ids = "${s.chatId}:${s.messageId}"
            val rows = mutableListOf<List<InlineButton>>()
            val first = mutableListOf<InlineButton>()
            if (s.canDelete) first += InlineButton("🗑 Видалити", "mod:del:$ids")
            s.muteKeyword?.let { kw ->
                val label = if (kw.length > KEYWORD_LABEL_MAX) kw.take(KEYWORD_LABEL_MAX - 1) + "…" else kw
                first += if (s.muted) InlineButton("🔇 $label ✓", "mod:noop:$ids") else InlineButton("🔇 Mute: $label", "mod:mute:$ids")
            }
            if (first.isNotEmpty()) rows += first
            if (s.withFeedback) {
                rows += listOf(
                    InlineButton("👍 Більше такого" + if (s.signal == ExplicitSignal.MORE) " ✓" else "", "mod:up:$ids"),
                    InlineButton("👎 Менше такого" + if (s.signal == ExplicitSignal.LESS) " ✓" else "", "mod:down:$ids")
                )
            }
            s.note?.let { rows += listOf(InlineButton(it, "mod:noop:$ids")) }
            return rows
        }
    }
}
