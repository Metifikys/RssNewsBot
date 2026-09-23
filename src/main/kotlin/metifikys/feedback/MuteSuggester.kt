package metifikys.feedback

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.config.MuteSuggestionsConfig
import metifikys.db.AudienceAffinityRow
import metifikys.db.MuteSuggestionRow
import metifikys.db.MuteSuggestionsTable
import metifikys.db.NewsDatabase
import metifikys.digest.MuteRegistry
import metifikys.digest.TopicMuteFilter
import metifikys.telegram.IncomingCallback
import metifikys.telegram.InlineButton
import metifikys.telegram.TelegramSender
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Proposes keyword mutes to the admin chat (`admin.muteSuggestions`), replacing the hand-made
 * `mute-suggestions.yaml` whose statistics went stale the day it was written. Two sources:
 *  - **disliked**: franchise affinity `score <= maxScore` backed by `n_tone >= minTone` reacted
 *    posts (👎 is already weighed into the score, so it needs no source of its own);
 *  - **never gets through**: `>= minRejected` Step-1 rejections of a franchise in 30 days with
 *    no covered event of it in the same window (`gta / rockstar` 68/0 in the July data).
 *
 * A franchise key becomes a keyword by its first ` / ` segment, lowercased; short or generic
 * keys are dropped, and so is a keyword that matched no title or description in 30 days (an
 * LLM label that never occurs in the text would mute nothing). Already-muted keywords, pending
 * or approved proposals, and ones rejected in the last 90 days are skipped. At most `maxPerRun`
 * go out per run, one silent message each with [🔇 in category] [🌐 global] [✖ no] buttons —
 * category first, since a global short key can hit an unrelated channel (`cod`, the fish).
 * Approving writes the same DB mute as `/mute` (source `suggestion`).
 *
 * Runs from the maintenance tick every `everyDays` (last run in `bot_state`). Never throws.
 */
class MuteSuggester(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val mutes: MuteRegistry,
    private val sender: TelegramSender,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    private val cfg: MuteSuggestionsConfig = config.admin.muteSuggestions

    /** One proposal before it is stored; [reason] is the evidence line. */
    data class Candidate(
        val category: String,
        val keyword: String,
        val kind: String,
        val score: Double?,
        val reason: String
    )

    fun runIfDue() {
        if (!cfg.enabled) return
        try {
            val now = clock()
            val last = db.getState(STATE_KEY)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            if (last != null && Duration.between(last, now) < Duration.ofDays(cfg.everyDays)) return
            run(now)
            db.setState(STATE_KEY, now.toString())
        } catch (e: Exception) {
            logger.warn(e) { "[MuteSuggest] run failed — retrying on the next maintenance tick" }
        }
    }

    /** One proposal pass; returns how many proposals were posted. */
    internal fun run(now: LocalDateTime = clock()): Int {
        val since = now.minusDays(WINDOW_DAYS)
        val candidates = candidates(
            affinity = db.fetchAudienceAffinity(),
            rejected = db.countRejectedFranchisesSince(since),
            covered = db.countCoveredFranchisesSince(since),
            cfg = cfg,
            categories = config.categories.keys
        )
        val previous = db.fetchMuteSuggestions()
        val hits = HitCounter(since)
        val accepted = mutableListOf<MuteSuggestionRow>()
        for (c in candidates) {
            if (accepted.size >= cfg.maxPerRun) break
            if (wasProposed(previous, c, now)) continue
            if (mutes.covering(c.keyword, c.category) != null) continue
            val (articles, published) = hits.count(c.category, c.keyword)
            if (articles == 0) {
                logger.debug { "[MuteSuggest] ${c.category}/'${c.keyword}' matches no recent title — skipped" }
                continue
            }
            accepted += MuteSuggestionRow(
                category = c.category, keyword = c.keyword, kind = c.kind, score = c.score, reason = c.reason,
                hits30d = articles, published30d = published, createdAt = now
            )
        }
        val chatId = config.admin.statusChatId ?: return 0
        var posted = 0
        for (row in accepted) {
            val id = db.insertMuteSuggestion(row)
            val stored = row.copy(id = id)
            val messageId = sender.sendMessageWithKeyboard(chatId, render(stored), keyboard(stored), silent = true)
            if (messageId != null) {
                db.setMuteSuggestionMessage(id, chatId.toLongOrNull() ?: 0L, messageId)
                posted++
            }
        }
        logger.info {
            "[MuteSuggest] ${candidates.size} candidate(s), ${accepted.size} new, $posted posted: " +
                accepted.joinToString(", ") { "${it.category}/'${it.keyword}'" }
        }
        return posted
    }

    private fun wasProposed(previous: List<MuteSuggestionRow>, c: Candidate, now: LocalDateTime): Boolean =
        previous.any { p ->
            p.category == c.category && p.keyword.equals(c.keyword, ignoreCase = true) &&
                (p.status != MuteSuggestionsTable.STATUS_REJECTED ||
                    (p.decidedAt ?: p.createdAt).isAfter(now.minusDays(REJECT_QUIET_DAYS)))
        }

    /** 30-day article hits per category, loading each category's articles and published links once. */
    private inner class HitCounter(private val since: LocalDateTime) {
        private val articles = HashMap<String, List<metifikys.model.Article>>()
        private val published = HashMap<String, Set<String>>()

        fun count(category: String, keyword: String): Pair<Int, Int> {
            val list = articles.getOrPut(category) { db.fetchRecentArticles(category, WINDOW_DAYS, MuteRegistry.PREVIEW_LIMIT) }
            val filter = TopicMuteFilter.of(listOf(keyword))
            val hits = list.filter { filter.matches(it) }
            if (hits.isEmpty()) return 0 to 0
            val links = published.getOrPut(category) { db.fetchDigestArticleLinksSince(category, since) }
            return hits.size to hits.count { it.link in links }
        }
    }

    /** `ms:<cat|glob|no>:<id>` from the proposal's buttons. */
    fun handleCallback(cb: IncomingCallback, parts: List<String>) {
        val action = parts.getOrNull(0) ?: return
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return
        val row = db.fetchMuteSuggestion(id) ?: return
        val chatId = (cb.chatId ?: row.chatId)?.toString() ?: return
        val messageId = cb.messageId ?: row.messageId ?: return
        if (row.status != MuteSuggestionsTable.STATUS_PENDING) {
            sender.editMessageWithKeyboard(chatId, messageId, render(row) + "\n\n" + statusLine(row.status), keyboard = null)
            return
        }
        val result = when (action) {
            "cat", "glob" -> {
                val scope = if (action == "cat") row.category else null
                when (val res = mutes.add(row.keyword, scope, cb.fromId, SOURCE)) {
                    is MuteRegistry.AddResult.Added, is MuteRegistry.AddResult.AlreadyMuted -> {
                        db.decideMuteSuggestion(id, MuteSuggestionsTable.STATUS_APPROVED)
                        val already = if (res is MuteRegistry.AddResult.AlreadyMuted) " (уже було)" else ""
                        "✅ зам'ючено: ${scope ?: "глобально"}$already"
                    }
                    is MuteRegistry.AddResult.Invalid -> {
                        sender.editMessageWithKeyboard(chatId, messageId, render(row) + "\n\n⚠️ ${res.reason}", keyboard(row))
                        return
                    }
                }
            }
            "no" -> {
                db.decideMuteSuggestion(id, MuteSuggestionsTable.STATUS_REJECTED)
                statusLine(MuteSuggestionsTable.STATUS_REJECTED)
            }
            else -> return
        }
        logger.info { "[MuteSuggest] #$id ${row.category}/'${row.keyword}': $action by ${cb.fromId}" }
        sender.editMessageWithKeyboard(chatId, messageId, render(row) + "\n\n" + result, keyboard = null)
    }

    companion object {
        const val STATE_KEY = "mute_suggest:last"
        const val SOURCE = "suggestion"
        const val KIND_AFFINITY = "affinity"
        const val KIND_REJECTED = "rejected"
        const val WINDOW_DAYS = 30L
        const val REJECT_QUIET_DAYS = 90L

        /** Keys too generic (or too ambiguous across channels) to mute by a suggestion. */
        val STOP_WORDS = setOf(
            "lol", "cod", "ai", "app", "apps", "pc", "game", "games", "gaming", "news", "update", "updates",
            "the", "new", "tech", "mobile", "online", "other", "others", "unknown", "misc", "various",
            "general", "none", "n/a", "industry", "hardware", "software", "console", "consoles", "indie"
        )

        /** `gta / rockstar / take-two` → `gta`; null when the first segment is short or generic. */
        fun keywordFor(franchiseKey: String): String? {
            val kw = franchiseKey.substringBefore(" / ").trim().lowercase()
            if (kw.length < 3 || kw in STOP_WORDS) return null
            if (MuteRegistry.validate(kw) != null) return null
            return kw
        }

        /**
         * Both sources merged into one ordered list, one entry per (category, keyword): disliked
         * franchises first (most negative score first), then never-through ones (most rejections
         * first). Categories outside [categories] are ignored.
         */
        fun candidates(
            affinity: List<AudienceAffinityRow>,
            rejected: Map<Pair<String, String>, Int>,
            covered: Map<Pair<String, String>, Int>,
            cfg: MuteSuggestionsConfig,
            categories: Set<String>
        ): List<Candidate> {
            val out = LinkedHashMap<Pair<String, String>, Candidate>()
            affinity
                .filter { it.dimension == AffinityMath.DIM_FRANCHISE && it.category in categories }
                .filter { it.score <= cfg.maxScore && it.nTone >= cfg.minTone }
                .sortedBy { it.score }
                .forEach { r ->
                    val kw = keywordFor(r.key) ?: return@forEach
                    out.putIfAbsent(
                        r.category to kw,
                        Candidate(
                            r.category, kw, KIND_AFFINITY, r.score,
                            "франшиза «${r.key}» · affinity ${fmt(r.score)} (n ${"%.1f".format(Locale.ROOT, r.nTone)}/" +
                                "${"%.0f".format(Locale.ROOT, r.n)})"
                        )
                    )
                }
            rejected
                .filter { (key, n) -> key.first in categories && n >= cfg.minRejected && (covered[key] ?: 0) == 0 }
                .entries.sortedByDescending { it.value }
                .forEach { (key, n) ->
                    val kw = keywordFor(key.second) ?: return@forEach
                    out.putIfAbsent(
                        key.first to kw,
                        Candidate(key.first, kw, KIND_REJECTED, null, "франшиза «${key.second}» · $n відхилено, 0 опубліковано за $WINDOW_DAYS д")
                    )
                }
            return out.values.toList()
        }

        fun render(row: MuteSuggestionRow): String =
            "🔇 Пропозиція: «${row.keyword}» (${row.category})\n${row.reason}\n" +
                "$WINDOW_DAYS д: збігів ${row.hits30d}, з них у дайджесті ${row.published30d}"

        fun keyboard(row: MuteSuggestionRow): List<List<InlineButton>> = listOf(
            listOf(
                InlineButton("🔇 У ${row.category}", "ms:cat:${row.id}"),
                InlineButton("🌐 Глобально", "ms:glob:${row.id}"),
                InlineButton("✖ Ні", "ms:no:${row.id}")
            )
        )

        private fun statusLine(status: String): String = when (status) {
            MuteSuggestionsTable.STATUS_APPROVED -> "✅ уже зам'ючено"
            MuteSuggestionsTable.STATUS_REJECTED -> "✖ відхилено — не пропоную $REJECT_QUIET_DAYS днів"
            else -> status
        }

        private fun fmt(v: Double): String = "%+.2f".format(Locale.ROOT, v)
    }
}
