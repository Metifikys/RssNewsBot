package metifikys.story

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.NewsDatabase
import metifikys.digest.VectorMath
import metifikys.model.Article
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * The IO side of the story lines: loads the clusters of a set of stories from `event_articles`
 * in one query, plus what the pure analyses need — member texts (the batch in hand first, the DB
 * for the rest) and stored article vectors. Callers catch nothing: any failure yields an empty map
 * and a warning, and the post goes out without the lines.
 */
class StoryClusterLoader(private val config: AppConfig, private val db: NewsDatabase) {

    /** One story's cluster and the text / vector material to judge its members. */
    class Loaded(
        val cluster: EventCluster,
        val texts: Map<String, String>,
        val vectors: Map<Int, FloatArray>
    )

    /**
     * @param stories story id → the event keys whose members make it up (its own key first, then
     *        e.g. an update's `relatedPreviousEventKey`, or the weekly cluster's other keys)
     * @param batch articles already in memory (the digest's batch), used before any DB read for texts
     * @param withVectors load stored vectors (only needed by the reprint-similarity rule)
     */
    fun load(
        category: String,
        stories: Map<String, List<String>>,
        batch: List<Article> = emptyList(),
        withVectors: Boolean = true
    ): Map<String, Loaded> {
        if (stories.isEmpty()) return emptyMap()
        return try {
            val rows = db.fetchEventClusters(category, stories.values.flatten().toSet())
            if (rows.isEmpty()) return emptyMap()
            val aliases = config.storyIntel.domainAliases
            val clusters = stories.mapNotNull { (id, keys) ->
                val first = keys.firstOrNull() ?: return@mapNotNull null
                val c = EventCluster.of(first, rows, keys.drop(1), aliases)
                if (c.members.isEmpty()) null else id to c
            }.toMap()
            if (clusters.isEmpty()) return emptyMap()

            val members = clusters.values.flatMap { it.members }
            val byLink = batch.associateBy { it.link }.toMutableMap()
            val missing = members.map { it.link }.filter { it !in byLink }.distinct()
            if (missing.isNotEmpty()) db.fetchArticlesByLinks(missing).forEach { byLink[it.link] = it }
            val texts = members.mapNotNull { m -> byLink[m.link]?.let { m.link to textOf(it) } }.toMap()

            val model = config.categories[category]?.semanticDedup?.model
            val vectors: Map<Int, FloatArray> = if (withVectors && model != null) {
                db.fetchEmbeddingsByArticleIds(members.map { it.articleId }.toSet(), model)
                    .mapNotNull { (id, blob) -> runCatching { VectorMath.decode(blob) }.getOrNull()?.let { id to it } }
                    .toMap()
            } else emptyMap()

            clusters.mapValues { (_, c) -> Loaded(c, texts, vectors) }
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][Story] cluster lookup failed — sending without story lines" }
            emptyMap()
        }
    }

    /**
     * [SourceLeaderboard] over the clusters of [category] recorded in the last [lookbackDays].
     * With [withReprints] the member texts are read too, so each outlet's retelling share is known
     * (attribution only — no vectors for a month of articles). Throws on a DB failure; callers
     * decide what a missing ranking means.
     */
    fun leaderboard(
        category: String,
        lookbackDays: Long,
        minEvents: Int,
        withReprints: Boolean = false,
        now: LocalDateTime = LocalDateTime.now()
    ): List<SourceLeaderboard.Row> {
        val aliases = config.storyIntel.domainAliases
        val clusters = db.fetchEventArticlesSince(category, now.minusDays(lookbackDays))
            .groupBy { it.eventKey }
            .map { (key, rows) -> EventCluster(key, rows, aliases) }
            .filter { it.outlets >= LEADERBOARD_MIN_OUTLETS }
        val reprints = if (withReprints) {
            val links = clusters.flatMap { c -> c.members.map { it.link } }.distinct()
            val texts = links.chunked(TEXT_CHUNK).flatMap { db.fetchArticlesByLinks(it) }.associate { it.link to textOf(it) }
            clusters.flatMap { Hype.analyze(it, texts, emptyMap(), 1.0).reprints }.toSet()
        } else null
        return SourceLeaderboard.compute(clusters, LEADERBOARD_MIN_OUTLETS, minEvents, reprints)
    }

    private fun textOf(a: Article): String = a.title + "\n" + a.promptText()

    companion object {
        /** Stories with fewer outlets say little about who is fast. */
        const val LEADERBOARD_MIN_OUTLETS = 3
        private const val TEXT_CHUNK = 500
    }
}
