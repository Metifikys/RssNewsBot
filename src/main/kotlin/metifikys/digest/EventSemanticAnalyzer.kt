package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.BillingException
import metifikys.ai.Embedder
import metifikys.config.AppConfig
import metifikys.config.SemanticDedupConfig
import metifikys.db.NewsDatabase
import metifikys.model.ShortlistItem

private val logger = KotlinLogging.logger {}

/**
 * Log-only event-level embedding analyzer (Layer 3.5).
 *
 * Runs *after* [metifikys.ai.dedup.EventExtractor] has produced a shortlist, on the events
 * about to be rendered. For each category whose [SemanticDedupConfig.eventEnabled] is true it:
 *
 *  1. Embeds each shortlist event's `subject + coreFact` via [Embedder], keyed by `event_key`.
 *  2. Brute-force cosine-scans recent **previously-covered** event vectors in the same
 *     category (the candidate set excludes the current shortlist's own keys).
 *  3. Logs one line per event: `[EventSemanticDedup][HIT]` when the top neighbour's cosine
 *     meets [SemanticDedupConfig.eventThreshold], `[EventSemanticDedup]` otherwise.
 *  4. Persists the event's vector so it becomes a candidate for the next cycle.
 *
 * This is the event-level analog of the article-level [SemanticDedupDetector] (Layer 2), and
 * is deliberately analyze-only: it NEVER drops events, mutates state, or filters the digest —
 * a stat-collection phase to calibrate `eventThreshold` before any hard filter is added.
 *
 * Never throws into the cycle; all failures are caught and logged.
 *
 * Note: vectors are persisted for every shortlisted event regardless of whether the digest
 * is ultimately delivered to Telegram, so the candidate set is "events we shortlisted" — a
 * close-enough proxy for "previously covered" during this calibration phase.
 */
class EventSemanticAnalyzer(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val embedder: Embedder
) {

    /**
     * Embeds [shortlist] events, logs near-duplicate candidates against recent covered events,
     * and persists the new vectors. When the category's `eventHardThreshold` is set, drops
     * `status == "new"` events whose top covered-event cosine meets it (logged `[REJECT]`) and
     * returns only the kept items; otherwise returns [shortlist] unchanged (pure logging).
     *
     * Fail-open by contract: a disabled category, billing limit, or any embed/scan failure
     * returns the original [shortlist] untouched — a transient error must never empty a digest.
     */
    fun analyzeAndFilter(category: String, shortlist: List<ShortlistItem>): List<ShortlistItem> {
        if (shortlist.isEmpty()) return shortlist
        val catCfg = config.categories[category] ?: return shortlist
        val sd = catCfg.semanticDedup ?: return shortlist
        if (!sd.eventEnabled) return shortlist

        return try {
            processCategory(category, sd, shortlist)
        } catch (e: BillingException) {
            logger.warn { "[EventSemanticDedup] cat=$category billing limit reached — analyzer skipped: ${e.message}" }
            shortlist
        } catch (e: Exception) {
            logger.warn(e) { "[EventSemanticDedup] cat=$category analyzer failed; cycle continues" }
            shortlist
        }
    }

    private fun processCategory(
        category: String,
        sd: SemanticDedupConfig,
        shortlist: List<ShortlistItem>
    ): List<ShortlistItem> {
        // Deduplicate by event_key within the shortlist — Step 1 can emit the same key twice
        // (e.g. a new item plus its meaningful_update). One vector per key is enough.
        val byKey = shortlist.associateBy { it.eventKey }.values.toList()
        val texts = byKey.map { embedText(it) }
        val vectors: List<FloatArray> = try {
            embedder.embed(texts, sd.model)
        } catch (e: BillingException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[EventSemanticDedup] cat=$category: embed call failed; skipping category" }
            return shortlist  // fail-open: drop nothing on embed failure
        }
        if (vectors.size != byKey.size) {
            logger.warn {
                "[EventSemanticDedup] cat=$category: expected ${byKey.size} vectors, got ${vectors.size} — skipping"
            }
            return shortlist  // fail-open
        }

        val normalized = byKey.indices.map { byKey[it] to VectorMath.l2Normalize(vectors[it]) }
        val currentKeys = byKey.map { it.eventKey }.toSet()

        // Candidate set: recent previously-covered event vectors, excluding this shortlist's
        // own keys so an event never matches itself across cycles.
        val recent = db.fetchRecentEventEmbeddings(category, sd.windowDays, sd.maxRecent)
            .filter { it.model == sd.model && it.eventKey !in currentKeys }
            .mapNotNull { row ->
                val vec = try {
                    VectorMath.decode(row.vector)
                } catch (e: Exception) {
                    logger.warn(e) { "[EventSemanticDedup] cat=$category: bad blob for event_key=${row.eventKey}" }
                    null
                }
                if (vec != null) row to vec else null
            }

        val rejectedKeys = mutableSetOf<String>()
        for ((item, vec) in normalized) {
            if (recent.isEmpty()) {
                logger.info {
                    "[EventSemanticDedup] cat=$category new_event=${item.eventKey} no recent covered candidates " +
                        "(window=${sd.windowDays}d)"
                }
            } else {
                val ranked = recent
                    .map { (row, candidateVec) -> row to VectorMath.cosine(vec, candidateVec) }
                    .sortedByDescending { it.second }
                    .take(sd.topK)
                val (topRow, topSim) = ranked.first()

                // Hard reject: cosine-only, status=="new" only (meaningful_update follow-ups are
                // intentional re-coverage and are never dropped). No subject gate — see config KDoc.
                val hard = sd.eventHardThreshold
                val rejected = hard != null && item.status == "new" && topSim >= hard
                if (rejected) rejectedKeys += item.eventKey

                val marker = when {
                    rejected -> "[EventSemanticDedup][REJECT]"
                    topSim >= sd.eventThreshold -> "[EventSemanticDedup][HIT]"
                    else -> "[EventSemanticDedup]"
                }
                // Subject/franchise-match flags: diagnostic data on how often a match shares a
                // subject. Real duplicates often share one; boilerplate collisions between
                // different titles do not. Empty fields are treated as non-matches (never "").
                val sameSubject = matches(item.subject, topRow.subject)
                val sameFranchise = matches(item.franchise, topRow.franchise)
                logger.info {
                    val thrTail = hard?.let { " hardThreshold=$it" } ?: ""
                    "$marker cat=$category new_event=${item.eventKey} (subject='${item.subject.take(80)}') " +
                        "top=[event_key=${topRow.eventKey} sim=${"%.4f".format(topSim)} " +
                        "sameSubject=$sameSubject sameFranchise=$sameFranchise] " +
                        "status=${item.status} threshold=${sd.eventThreshold}$thrTail"
                }
                if (ranked.size > 1) {
                    val rest = ranked.drop(1).joinToString(", ") { (r, s) ->
                        "event_key=${r.eventKey} sim=%.4f".format(s)
                    }
                    logger.debug { "[EventSemanticDedup] cat=$category new_event=${item.eventKey} other_candidates: $rest" }
                }

                // Don't persist a hard-rejected event: the canonical event is already in the
                // store, and persisting the dup would pollute the candidate set for next cycle.
                if (rejected) continue
            }

            // Persist the kept event's vector so it becomes a candidate next cycle. Subject/
            // franchise are stored alongside so future scans can emit the match flags above.
            try {
                db.saveEventEmbedding(category, item.eventKey, item.subject, item.franchise, sd.model, VectorMath.encode(vec))
            } catch (e: Exception) {
                logger.warn(e) { "[EventSemanticDedup] cat=$category: failed to persist embedding for event_key=${item.eventKey}" }
            }
        }

        if (rejectedKeys.isEmpty()) return shortlist
        val kept = shortlist.filterNot { it.eventKey in rejectedKeys }
        logger.info {
            "[EventSemanticDedup] cat=$category hard-filter dropped ${rejectedKeys.size} event(s): " +
                "${rejectedKeys.joinToString()} — shortlist ${shortlist.size} -> ${kept.size}"
        }
        return kept
    }

    /**
     * Text passed to the embedding model for an event: `subject` (Step 1's short label) plus
     * `coreFact` (the one-line factual core). Mirrors [SemanticDedupDetector.embedText]'s
     * title-plus-body shape but on structured event fields rather than raw article text.
     */
    private fun embedText(item: ShortlistItem): String {
        val full = buildString {
            append(item.subject.trim())
            if (item.coreFact.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(item.coreFact.trim())
            }
        }.ifBlank { item.eventKey }
        return if (full.length <= MAX_EMBED_INPUT_CHARS) full else full.take(MAX_EMBED_INPUT_CHARS)
    }

    /**
     * Normalized equality for subject/franchise match flags: lowercased, with every run of
     * non-alphanumeric characters collapsed so cosmetic differences don't break a match
     * (e.g. "Elden Ring: Tarnished Edition" == "Elden Ring Tarnished Edition"). Blank on
     * either side is never a match — old rows written before these columns existed carry "".
     */
    private fun matches(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        return na.isNotEmpty() && na == nb
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(NON_ALNUM, " ").trim().replace(WHITESPACE, " ")

    companion object {
        private const val MAX_EMBED_INPUT_CHARS = 6000
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE = Regex("\\s+")
    }
}
