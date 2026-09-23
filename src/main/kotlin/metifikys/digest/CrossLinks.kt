package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.config.CrossLinksConfig
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.model.ShortlistItem
import metifikys.telegram.TelegramLinks
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * `post.crossLinks`: for each event of a digest, the post about the same story that another
 * category's channel already carries — eventKey → `🔗 У 🖥 Tech: [<subject>](https://t.me/…)`.
 * Reads the vectors Layer 3.5 stored before delivery (`event_embeddings`) and `digest_messages`;
 * no LLM and no embedding call. One vector lookup for the digest, one scan and one
 * `digest_messages` query per listed category. Fail-open: any failure yields no lines.
 */
class CrossLinks(private val config: AppConfig, private val db: NewsDatabase) {

    fun resolve(category: String, shortlist: List<ShortlistItem>?, cfg: CrossLinksConfig?): Map<String, String> {
        if (cfg == null || !cfg.enabled || shortlist.isNullOrEmpty()) return emptyMap()
        return try {
            resolveOrThrow(category, shortlist, cfg)
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][CrossLink] lookup failed — sending without cross-channel links" }
            emptyMap()
        }
    }

    private fun resolveOrThrow(category: String, shortlist: List<ShortlistItem>, cfg: CrossLinksConfig): Map<String, String> {
        val ownChannel = config.categories[category]?.channelId?.trim() ?: return emptyMap()
        val targets = cfg.categories.mapNotNull { name ->
            config.categories[name]?.takeIf { name != category && it.channelId.trim() != ownChannel }?.let { name to it }
        }
        if (targets.isEmpty()) return emptyMap()
        val own = db.fetchEventEmbeddings(category, shortlist.map { it.eventKey })
        if (own.isEmpty()) return emptyMap()

        val candidates = targets.flatMap { (name, _) ->
            db.fetchRecentEventEmbeddings(name, cfg.maxAgeDays, MAX_CANDIDATES).mapNotNull { row ->
                val vec = runCatching { VectorMath.decode(row.vector) }.getOrNull() ?: return@mapNotNull null
                Candidate(name, row.eventKey, row.subject, row.model, vec)
            }
        }
        if (candidates.isEmpty()) return emptyMap()

        // eventKey → its candidates at or above the threshold, best first.
        val ranked = LinkedHashMap<String, List<Pair<Candidate, Double>>>()
        for (item in shortlist.distinctBy { it.eventKey }) {
            val row = own[item.eventKey] ?: continue
            val vec = runCatching { VectorMath.decode(row.vector) }.getOrNull() ?: continue
            val all = rank(vec, row.model, candidates)
            val top = all.firstOrNull() ?: continue
            if (top.second < cfg.minSimilarity) {
                logger.debug { "[CrossLink] ${item.eventKey} ~ ${top.first.category}:${top.first.eventKey} sim=${"%.4f".format(top.second)} (below ${cfg.minSimilarity})" }
                continue
            }
            ranked[item.eventKey] = all.takeWhile { it.second >= cfg.minSimilarity }
        }
        if (ranked.isEmpty()) return emptyMap()

        // Only what actually reached the other channel: one digest_messages query per category.
        val since = LocalDateTime.now().minusDays(cfg.maxAgeDays)
        val posted = targets.associate { (name, _) ->
            val keys = ranked.values.flatten().filter { it.first.category == name }.map { it.first.eventKey }.toSet()
            name to if (keys.isEmpty()) emptyMap() else db.fetchLatestDigestMessagesByEventKeys(name, keys, since)
        }
        val targetCfg = targets.toMap()

        val out = LinkedHashMap<String, String>()
        for ((key, matches) in ranked) {
            val hit = matches.firstNotNullOfOrNull { (c, sim) ->
                val msg = posted[c.category]?.get(c.eventKey)?.takeIf { sameChat(targetCfg.getValue(c.category).channelId, it) }
                msg?.let { Triple(c, sim, it) }
            }
            if (hit == null) {
                val (c, sim) = matches.first()
                logger.info { "[CrossLink] $key ~ ${c.category}:${c.eventKey} sim=${"%.4f".format(sim)} — not posted there, no link" }
                continue
            }
            val (c, sim, msg) = hit
            val target = targetCfg.getValue(c.category)
            val url = TelegramLinks.messageUrl(target.channelId, msg.chatId, msg.messageId)
            logger.info {
                "[CrossLink] $key ~ ${c.category}:${c.eventKey} sim=${"%.4f".format(sim)} msg=${msg.messageId}" +
                    (if (url == null) " — no public link for channel ${target.channelId}" else "") +
                    (if (cfg.logOnly) " (logOnly)" else "")
            }
            if (url == null || cfg.logOnly) continue
            out[key] = line(target.emoji, c.category, c.subject, url)
        }
        return out
    }

    /** A digest_messages row of the other channel: for a numeric channel id, the chat must match. */
    private fun sameChat(channelId: String, row: DigestMessageRow): Boolean {
        val numeric = channelId.trim().toLongOrNull()
        return numeric == null || row.chatId == numeric
    }

    /** An event vector of another category. */
    data class Candidate(
        val category: String,
        val eventKey: String,
        val subject: String,
        val model: String,
        val vector: FloatArray
    )

    companion object {
        /** Cap on the other category's recent vectors scanned per digest (a few days is a few hundred events). */
        private const val MAX_CANDIDATES = 1000
        private const val MAX_SUBJECT_CHARS = 80

        /** [candidates] made with [model] (and of the same size), by cosine to [vector], best first. */
        fun rank(vector: FloatArray, model: String, candidates: List<Candidate>): List<Pair<Candidate, Double>> =
            candidates.asSequence()
                .filter { it.model == model && it.vector.size == vector.size }
                .map { it to VectorMath.cosine(vector, it.vector) }
                .sortedByDescending { it.second }
                .toList()

        /**
         * `🔗 У 🖥 Tech: [<subject>](<url>)` — the category's emoji and capitalised name; the subject
         * loses brackets and Markdown markers and is cut to [MAX_SUBJECT_CHARS] (blank → `пост`).
         */
        fun line(emoji: String, category: String, subject: String, url: String): String {
            val clean = subject.replace('[', '(').replace(']', ')').replace("*", "").replace("`", "")
                .replace(Regex("\\s+"), " ").trim()
            val label = when {
                clean.isEmpty() -> "пост"
                clean.length > MAX_SUBJECT_CHARS -> clean.take(MAX_SUBJECT_CHARS - 1).trimEnd() + "…"
                else -> clean
            }
            val name = category.replaceFirstChar { it.uppercase() }
            return "🔗 У ${emoji.trim()} $name: [$label]($url)".replace("  ", " ")
        }
    }
}
