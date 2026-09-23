package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.BillingException
import metifikys.ai.Embedder
import metifikys.config.AppConfig
import metifikys.config.SemanticDedupConfig
import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import metifikys.db.NewsDatabase
import metifikys.feedback.AffinityMath
import metifikys.model.Article
import metifikys.model.ArticleStatus
import metifikys.story.ConflictDetector
import metifikys.story.StoryClusterLoader

private val logger = KotlinLogging.logger {}

/**
 * Embedding-based dedup detector with an optional hard filter.
 *
 * For each category whose [SemanticDedupConfig.enabled] is true, takes the just-
 * inserted articles, embeds `title + (summary | description)` via [Embedder],
 * persists the L2-normalized vector, and brute-force scans recent vectors in
 * the same category looking for near-duplicates.
 *
 * Behaviour is driven by two thresholds:
 *  - [SemanticDedupConfig.threshold] — anything ≥ this is logged as `[SemanticDedup][HIT]`.
 *    Side-effect-free; used for stat collection.
 *  - [SemanticDedupConfig.hardThreshold] (optional) — anything ≥ this AND whose top-1
 *    neighbour is already in status [ArticleStatus.PROCESSED] gets marked
 *    [ArticleStatus.DUPLICATE] with `duplicate_of` set, logged as `[SemanticDedup][REJECT]`,
 *    and never reaches the LLM. Matches against UNPROCESSED / PROCESSING / DUPLICATE
 *    neighbours are *not* rejected — the Step 1 event extractor still owns within-batch
 *    dedup of unsent articles.
 *
 * Never throws into the cycle; all failures are caught and logged.
 */
class SemanticDedupDetector(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val embedder: Embedder
) {

    /**
     * Embeds new articles, persists the vectors, and logs candidate duplicates.
     * Safe to call with an empty list. Catches and logs all exceptions to keep
     * digest cycles unbreakable by the detector.
     */
    fun detectAndLog(newArticles: List<Article>) {
        if (newArticles.isEmpty()) return
        try {
            val byCategory = newArticles.groupBy { it.category }
            for ((categoryName, articlesInCat) in byCategory) {
                val catCfg = config.categories[categoryName] ?: continue
                val sd = catCfg.semanticDedup ?: continue
                if (!sd.enabled) continue
                processCategory(categoryName, sd, articlesInCat)
            }
        } catch (e: BillingException) {
            logger.warn { "[SemanticDedup] billing limit reached — detector skipped: ${e.message}" }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn { "[SemanticDedup] cancelled (cycle deadline / shutdown) — detector skipped" }
        } catch (e: Exception) {
            logger.warn(e) { "[SemanticDedup] detector failed; cycle continues" }
        }
    }

    private fun processCategory(
        categoryName: String,
        sd: SemanticDedupConfig,
        articles: List<Article>
    ) {
        // Resolve article ids — only proceed for articles that made it into the DB
        val links = articles.map { it.link }
        val idByLink = db.fetchArticleIdsByLinks(links)
        val resolved = articles.mapNotNull { a -> idByLink[a.link]?.let { id -> id to a } }
        if (resolved.isEmpty()) {
            logger.info { "[SemanticDedup] cat=$categoryName: no inserted articles to embed" }
            return
        }

        val texts = resolved.map { (_, a) -> embedText(a) }
        val vectors: List<FloatArray> = try {
            embedder.embed(texts, sd.model)
        } catch (e: BillingException) {
            // Surface to the outer catch — billing is a global concern, no point retrying other categories
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn { "[SemanticDedup] cat=$categoryName: cancelled (cycle deadline / shutdown) — skipping" }
            return
        } catch (e: Exception) {
            // okhttp translates an interrupt into InterruptedIOException (flag stays set) —
            // that's cancellation, not an embedding failure worth a stack trace.
            if (Thread.currentThread().isInterrupted()) {
                logger.warn { "[SemanticDedup] cat=$categoryName: cancelled (cycle deadline / shutdown) — skipping" }
            } else {
                logger.warn(e) { "[SemanticDedup] cat=$categoryName: embed call failed; skipping category" }
            }
            return
        }
        if (vectors.size != resolved.size) {
            logger.warn {
                "[SemanticDedup] cat=$categoryName: expected ${resolved.size} vectors, got ${vectors.size} — skipping"
            }
            return
        }

        // Persist new vectors (L2-normalized so cosine ≡ dot product on retrieval)
        val newVectors = mutableListOf<Triple<Int, String, FloatArray>>()  // (articleId, link, normalizedVec)
        for (i in resolved.indices) {
            val (articleId, article) = resolved[i]
            val normalized = VectorMath.l2Normalize(vectors[i])
            try {
                db.saveEmbedding(articleId, sd.model, VectorMath.encode(normalized))
            } catch (e: Exception) {
                logger.warn(e) { "[SemanticDedup] cat=$categoryName: failed to persist embedding for ${article.link}" }
            }
            newVectors.add(Triple(articleId, article.link, normalized))
        }

        // Pull candidate neighbours: recent rows in the same category, excluding self.
        val recent = db.fetchRecentEmbeddings(categoryName, sd.windowDays, sd.maxRecent)
            .filter { it.model == sd.model && it.articleId !in resolved.map { (id, _) -> id }.toSet() }
            .map { row ->
                val vec = try {
                    VectorMath.decode(row.vector)
                } catch (e: Exception) {
                    logger.warn(e) { "[SemanticDedup] cat=$categoryName: bad blob for id=${row.articleId}" }
                    null
                }
                if (vec != null) row to vec else null
            }
            .filterNotNull()

        if (recent.isEmpty()) {
            for ((_, link, _) in newVectors) {
                logger.info { "[SemanticDedup] cat=$categoryName new=$link no recent candidates (window=${sd.windowDays}d)" }
            }
            return
        }

        val rejects = mutableListOf<LateReprint>()
        for ((articleId, link, vec) in newVectors) {
            val sortedAll = recent
                .map { (row, candidateVec) -> row to VectorMath.cosine(vec, candidateVec) }
                .sortedByDescending { it.second }
            val ranked = sortedAll.take(sd.topK)

            val top = ranked.firstOrNull()
            if (top == null) {
                logger.info { "[SemanticDedup] cat=$categoryName new=$link no candidates" }
                continue
            }
            val (topRow, topSim) = top
            val above = topSim >= sd.threshold

            // Hard-filter: only rejects against PROCESSED neighbours so the LLM keeps
            // owning within-batch dedup against UNPROCESSED / PROCESSING peers.
            // The anchor is the closest PROCESSED neighbour, not top-1: a DUPLICATE row that
            // was itself rejected against the canonical article usually sits closer to the
            // next rewrite than the canonical does, and used to shadow it (2026-09-02 audit:
            // 2 of 3 DOOM crunch re-posts had a DUPLICATE at #1 and a PROCESSED ≥ hardThreshold at #2).
            val hardThr = sd.hardThreshold
            val anchor = if (hardThr != null) {
                sortedAll.firstOrNull { (row, sim) -> row.status == ArticleStatus.PROCESSED.name && sim >= hardThr }
            } else null
            val rejected = anchor != null
            if (anchor != null) {
                rejects += LateReprint(articleId, link, anchor.first.articleId, anchor.second)
                try {
                    db.markDuplicate(articleId, anchor.first.articleId)
                } catch (e: Exception) {
                    logger.warn(e) {
                        "[SemanticDedup] cat=$categoryName: failed to mark id=$articleId as DUPLICATE; cycle continues"
                    }
                }
            }

            val marker = when {
                rejected -> "[SemanticDedup][REJECT]"
                above -> "[SemanticDedup][HIT]"
                else -> "[SemanticDedup]"
            }
            logger.info {
                val simStr = "%.4f".format(topSim)
                val thrTail = hardThr?.let { " hardThreshold=$it" } ?: ""
                val anchorTail = anchor
                    ?.takeIf { it.first.articleId != topRow.articleId }
                    ?.let { (row, sim) -> " anchor=[id=${row.articleId} sim=${"%.4f".format(sim)} title='${row.title.take(80)}']" }
                    ?: ""
                "$marker cat=$categoryName new=$link (id=$articleId) " +
                    "top=[id=${topRow.articleId} status=${topRow.status} sim=$simStr title='${topRow.title.take(80)}']$anchorTail " +
                    "threshold=${sd.threshold}$thrTail"
            }
            if (ranked.size > 1) {
                val rest = ranked.drop(1).joinToString(", ") { (r, s) ->
                    "id=${r.articleId} sim=%.4f".format(s)
                }
                logger.debug { "[SemanticDedup] cat=$categoryName new=$link other_candidates: $rest" }
            }
        }
        if (config.storyIntel.enabled) recordLateReprints(categoryName, rejects, articles)
    }

    /** A hard-rejected article, the PROCESSED article it was rejected against, and their cosine. */
    private data class LateReprint(val articleId: Int, val link: String, val anchorId: Int, val sim: Double)

    /**
     * `storyIntel`: a late reprint joins the event of its anchor article (`role = layer2`, with the
     * cosine). An anchor without an `event_articles` row — older than the feature, or a legacy
     * category — leaves the reprint unattached. A failure only logs.
     */
    private fun recordLateReprints(categoryName: String, rejects: List<LateReprint>, articles: List<Article>) {
        if (rejects.isEmpty()) return
        try {
            val keys = db.fetchEventKeysByArticleIds(categoryName, rejects.map { it.anchorId })
            val stamps = db.fetchArticleStamps(rejects.map { it.link })
            val now = java.time.LocalDateTime.now()
            val rows = rejects.mapNotNull { r ->
                val key = keys[r.anchorId]
                val stamp = stamps[r.link]
                if (key == null || stamp == null) {
                    logger.debug { "[SemanticDedup][StoryIntel] cat=$categoryName ${r.link}: anchor id=${r.anchorId} has no event — not attached" }
                    return@mapNotNull null
                }
                EventArticleRow(
                    category = categoryName,
                    articleId = r.articleId,
                    eventKey = key,
                    link = r.link,
                    domain = AffinityMath.sourceDomain(r.link),
                    pubDate = stamp.pubDate,
                    seenAt = stamp.fetchedAt,
                    role = EventArticleRole.LAYER2,
                    sim = r.sim,
                    recordedAt = now
                )
            }
            db.upsertEventArticles(rows)
            if (rows.isNotEmpty()) logger.debug { "[SemanticDedup][StoryIntel] cat=$categoryName attached ${rows.size} late reprint(s) to their events" }
            logLateConflicts(categoryName, rows, articles)
        } catch (e: Exception) {
            logger.warn(e) { "[SemanticDedup][StoryIntel] cat=$categoryName recording late reprints failed" }
        }
    }

    private val storyClusters = StoryClusterLoader(config, db)

    /**
     * `post.storyLines.conflicts` audit: a late reprint that states a date / price / count clashing
     * with everything its event's cluster says is logged as `[StoryConflict][late]` — a candidate
     * for a `meaningful_update` the hard filter swallowed. Log only; [rows] are already attached.
     */
    private fun logLateConflicts(categoryName: String, rows: List<EventArticleRow>, articles: List<Article>) {
        val cfg = config.categories[categoryName]?.post?.storyLines?.conflicts?.takeIf { it.enabled } ?: return
        if (rows.isEmpty()) return
        val loaded = storyClusters.load(categoryName, rows.map { it.eventKey }.distinct().associateWith { listOf(it) }, articles, withVectors = false)
        for (row in rows) {
            val l = loaded[row.eventKey] ?: continue
            for ((late, known) in ConflictDetector.late(l.cluster, l.texts, row.articleId, cfg.dateToleranceDays)) {
                logger.info {
                    "[StoryConflict][late] cat=$categoryName ${row.eventKey} ${row.link} " +
                        ConflictDetector.describe(late.fact.slot, late.fact.unit, listOf(late) + known)
                }
            }
        }
    }

    /**
     * Builds the text passed to the embedding model: title plus the cleanest body
     * we have (LLM summary if present, otherwise raw description). Mirrors
     * [Article.promptText]'s preference order.
     */
    private fun embedText(article: Article): String {
        val body = article.summary?.takeIf { it.isNotBlank() } ?: article.description
        val full = buildString {
            append(article.title.trim())
            if (body.isNotBlank()) {
                append("\n\n")
                append(body.trim())
            }
        }
        // Cap per-input length: OpenAI's 8192-token limit rejects the whole batch if any input exceeds it.
        return if (full.length <= MAX_EMBED_INPUT_CHARS) full else full.take(MAX_EMBED_INPUT_CHARS)
    }

    companion object {
        private const val MAX_EMBED_INPUT_CHARS = 6000
    }
}
