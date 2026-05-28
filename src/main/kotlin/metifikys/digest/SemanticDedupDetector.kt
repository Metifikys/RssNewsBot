package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.BillingException
import metifikys.ai.Embedder
import metifikys.config.AppConfig
import metifikys.config.SemanticDedupConfig
import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.model.ArticleStatus

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
        } catch (e: Exception) {
            logger.warn(e) { "[SemanticDedup] cat=$categoryName: embed call failed; skipping category" }
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

        for ((articleId, link, vec) in newVectors) {
            val ranked = recent
                .map { (row, candidateVec) -> row to VectorMath.cosine(vec, candidateVec) }
                .sortedByDescending { it.second }
                .take(sd.topK)

            val top = ranked.firstOrNull()
            if (top == null) {
                logger.info { "[SemanticDedup] cat=$categoryName new=$link no candidates" }
                continue
            }
            val (topRow, topSim) = top
            val above = topSim >= sd.threshold

            // Hard-filter: only rejects against PROCESSED neighbours so the LLM keeps
            // owning within-batch dedup against UNPROCESSED / PROCESSING peers.
            val hardThr = sd.hardThreshold
            val rejected = hardThr != null &&
                topSim >= hardThr &&
                topRow.status == ArticleStatus.PROCESSED.name
            if (rejected) {
                try {
                    db.markDuplicate(articleId, topRow.articleId)
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
                "$marker cat=$categoryName new=$link (id=$articleId) " +
                    "top=[id=${topRow.articleId} status=${topRow.status} sim=$simStr title='${topRow.title.take(80)}'] " +
                    "threshold=${sd.threshold}$thrTail"
            }
            if (ranked.size > 1) {
                val rest = ranked.drop(1).joinToString(", ") { (r, s) ->
                    "id=${r.articleId} sim=%.4f".format(s)
                }
                logger.debug { "[SemanticDedup] cat=$categoryName new=$link other_candidates: $rest" }
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
