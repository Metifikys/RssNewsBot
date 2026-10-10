package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * A link that a feed brought AFTER another feed had already brought it (`articles.feed_url` keeps
 * only the first). Layer 1's unique-link insert otherwise hides that the story arrived twice — the
 * "echo" half of the feed-quality "who was first" metric. A feed re-reads its own old links every
 * tick; those are never recorded, so the table stays small.
 */
object FeedSightingsTable : Table("feed_sightings") {
    val link = varchar("link", 2000)
    val feedUrl = varchar("feed_url", 2000)
    val category = varchar("category", 100)
    val seenAt = datetime("seen_at").index()

    override val primaryKey = PrimaryKey(link, feedUrl)
}

/**
 * Fetch health per configured feed (`feedHealth.enabled`): what [metifikys.fetch.FeedHealthRecorder]
 * collected in memory, flushed on the maintenance tick. One row per feed `url` — a changed URL is a
 * new feed. `fail_streak` counts failed passes since the last success (a 304 is a success).
 */
object FeedStatsTable : Table("feed_stats") {
    val feedUrl = varchar("feed_url", 2000)
    val category = varchar("category", 100)
    val lastAttemptAt = datetime("last_attempt_at")
    val lastSuccessAt = datetime("last_success_at").nullable()
    val failStreak = integer("fail_streak").default(0)
    val fetchesTotal = integer("fetches_total").default(0)
    val failuresTotal = integer("failures_total").default(0)
    val lastError = varchar("last_error", 300).nullable()
    val lastEntries = integer("last_entries").nullable()

    override val primaryKey = PrimaryKey(feedUrl)
}

/** What one feed did since the previous flush (see [metifikys.fetch.FeedHealthRecorder]). */
data class FeedStatsDelta(
    val feedUrl: String,
    val category: String,
    val passes: Int,
    val failures: Int,
    val lastAttemptAt: LocalDateTime,
    /** Newest success in this delta; null = none, so the streak continues from the stored one. */
    val lastSuccessAt: LocalDateTime?,
    val failuresSinceSuccess: Int,
    val lastError: String?,
    val lastEntries: Int?
)

/** One `feed_stats` row. */
data class FeedStatsRow(
    val feedUrl: String,
    val category: String,
    val lastAttemptAt: LocalDateTime,
    val lastSuccessAt: LocalDateTime?,
    val failStreak: Int,
    val fetchesTotal: Int,
    val failuresTotal: Int,
    val lastError: String?,
    val lastEntries: Int?
)

/**
 * One article in the feed-quality window, with everything that decides its outcome: `status`,
 * `drop_reason`, the L2 `duplicate_of` pointer and the Step 1 verdict from `rejected_events`
 * (`rejected` / `duplicate`, null when Step 1 kept or never saw it).
 */
data class FeedArticleOutcome(
    val id: Int,
    val feedUrl: String,
    val category: String,
    val link: String,
    val status: String,
    val dropReason: String?,
    val fetchedAt: LocalDateTime,
    val duplicateOf: Int?,
    val step1: String?,
    /** Junk-classifier score when that column exists (see [FeedStore.JUNK_SCORE_COLUMN]); null otherwise. */
    val junkScore: Double? = null
)

/** One `feed_sightings` row. */
data class FeedSighting(
    val link: String,
    val feedUrl: String,
    val category: String,
    val seenAt: LocalDateTime
)

/** Per-feed bookkeeping for the feed-quality metrics (`feed_sightings`). */
interface FeedStore {
    /** Records second-feed sightings; the first sighting of a `(link, feed)` pair wins. */
    fun insertFeedSightings(rows: List<FeedSighting>)

    /** Sightings seen at or after [since]. */
    fun fetchFeedSightingsSince(since: LocalDateTime): List<FeedSighting>

    /** Retention: drops sightings older than [days] (the same window as the articles). */
    fun deleteOldFeedSightings(days: Long)

    /**
     * The subset of [feedUrls] that ever brought a link: an `articles` row or a `feed_sightings`
     * row under that `feed_url`. Any other configured feed has never been polled successfully.
     */
    fun findKnownFeedUrls(feedUrls: Collection<String>): Set<String>

    /** Merges one flush of fetch outcomes into `feed_stats`. */
    fun applyFeedStats(deltas: List<FeedStatsDelta>)

    fun fetchFeedStats(): List<FeedStatsRow>

    /**
     * Articles with a `feed_url` first ingested at or after [since], each with its Step 1 verdict
     * (a `rejected_events` row of the same category and url) and, when the column exists, the
     * junk-classifier score.
     */
    fun fetchFeedArticleOutcomes(since: LocalDateTime): List<FeedArticleOutcome>

    /** Newest `fetched_at` per `feed_url` over the whole table: when each feed last brought a new link. */
    fun fetchFeedLastNew(): Map<String, LocalDateTime>

    /** Oldest `fetched_at` in the table: how long feeds have been tracked at all (null = not yet). */
    fun fetchFeedTrackingSince(): LocalDateTime?

    companion object {
        /**
         * Column the junk classifier (plan item 21, built elsewhere) is expected to add to `articles`.
         * Read only when present (`PRAGMA table_info`); the contract is not fixed yet — rename here.
         */
        const val JUNK_SCORE_COLUMN = "junk_score"
    }
}

internal class FeedRepository : FeedStore {

    override fun insertFeedSightings(rows: List<FeedSighting>) {
        if (rows.isEmpty()) return
        transaction {
            for (r in rows) {
                FeedSightingsTable.insertIgnore {
                    it[link] = r.link
                    it[feedUrl] = r.feedUrl
                    it[category] = r.category
                    it[seenAt] = r.seenAt
                }
            }
        }
    }

    override fun fetchFeedSightingsSince(since: LocalDateTime): List<FeedSighting> = transaction {
        FeedSightingsTable.selectAll()
            .where { FeedSightingsTable.seenAt greaterEq since }
            .map {
                FeedSighting(
                    link = it[FeedSightingsTable.link],
                    feedUrl = it[FeedSightingsTable.feedUrl],
                    category = it[FeedSightingsTable.category],
                    seenAt = it[FeedSightingsTable.seenAt]
                )
            }
    }

    override fun deleteOldFeedSightings(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            FeedSightingsTable.deleteWhere { seenAt less cutoff }
        }
    }

    override fun findKnownFeedUrls(feedUrls: Collection<String>): Set<String> {
        if (feedUrls.isEmpty()) return emptySet()
        val urls = feedUrls.toList()
        return transaction {
            val inArticles = ArticlesTable.select(ArticlesTable.feedUrl)
                .where { ArticlesTable.feedUrl inList urls }
                .withDistinct()
                .mapNotNull { it[ArticlesTable.feedUrl] }
            val rest = urls - inArticles.toSet()
            val inSightings = if (rest.isEmpty()) emptyList() else FeedSightingsTable.select(FeedSightingsTable.feedUrl)
                .where { FeedSightingsTable.feedUrl inList rest }
                .withDistinct()
                .map { it[FeedSightingsTable.feedUrl] }
            (inArticles + inSightings).toSet()
        }
    }

    override fun applyFeedStats(deltas: List<FeedStatsDelta>) {
        if (deltas.isEmpty()) return
        transaction {
            for (d in deltas) {
                val old = FeedStatsTable.selectAll().where { FeedStatsTable.feedUrl eq d.feedUrl }.firstOrNull()?.toFeedStats()
                val streak = if (d.lastSuccessAt != null) d.failuresSinceSuccess else (old?.failStreak ?: 0) + d.failures
                if (old == null) {
                    FeedStatsTable.insert {
                        it[feedUrl] = d.feedUrl
                        it[category] = d.category
                        it[lastAttemptAt] = d.lastAttemptAt
                        it[lastSuccessAt] = d.lastSuccessAt
                        it[failStreak] = streak
                        it[fetchesTotal] = d.passes
                        it[failuresTotal] = d.failures
                        it[lastError] = d.lastError
                        it[lastEntries] = d.lastEntries
                    }
                } else {
                    FeedStatsTable.update({ FeedStatsTable.feedUrl eq d.feedUrl }) {
                        it[category] = d.category
                        it[lastAttemptAt] = d.lastAttemptAt
                        it[lastSuccessAt] = d.lastSuccessAt ?: old.lastSuccessAt
                        it[failStreak] = streak
                        it[fetchesTotal] = old.fetchesTotal + d.passes
                        it[failuresTotal] = old.failuresTotal + d.failures
                        it[lastError] = d.lastError ?: old.lastError
                        it[lastEntries] = d.lastEntries ?: old.lastEntries
                    }
                }
            }
        }
    }

    override fun fetchFeedStats(): List<FeedStatsRow> = transaction {
        FeedStatsTable.selectAll().map { it.toFeedStats() }
    }

    private fun ResultRow.toFeedStats() = FeedStatsRow(
        feedUrl = this[FeedStatsTable.feedUrl],
        category = this[FeedStatsTable.category],
        lastAttemptAt = this[FeedStatsTable.lastAttemptAt],
        lastSuccessAt = this[FeedStatsTable.lastSuccessAt],
        failStreak = this[FeedStatsTable.failStreak],
        fetchesTotal = this[FeedStatsTable.fetchesTotal],
        failuresTotal = this[FeedStatsTable.failuresTotal],
        lastError = this[FeedStatsTable.lastError],
        lastEntries = this[FeedStatsTable.lastEntries]
    )

    override fun fetchFeedArticleOutcomes(since: LocalDateTime): List<FeedArticleOutcome> = transaction {
        val a = ArticlesTable
        val rows = a.select(a.id, a.feedUrl, a.category, a.link, a.status, a.dropReason, a.fetchedAt, a.duplicateOf)
            // A new feed's first-poll backlog was never a candidate: it would read as "unselected" junk.
            .where {
                a.feedUrl.isNotNull() and (a.fetchedAt greaterEq since) and
                    (a.dropReason.isNull() or (a.dropReason neq DropReason.NEW_FEED))
            }
            .toList()
        if (rows.isEmpty()) return@transaction emptyList()
        // Step 1 verdicts, joined in memory: rejected_events.url has no index, and the window is
        // bounded by the same 30-day retention anyway.
        val r = RejectedEventsTable
        val verdicts = HashMap<Pair<String, String>, String>()
        r.select(r.category, r.url, r.status)
            .where { r.extractedAt greaterEq since.minusDays(1) }
            .forEach { row ->
                val key = row[r.category] to row[r.url]
                // A link both rejected and flagged duplicate (two extractions) reads as rejected.
                if (verdicts[key] != "rejected") verdicts[key] = if (row[r.status] == "duplicate") "duplicate" else "rejected"
            }
        val junk = junkScores(since)
        rows.map { row ->
            val link = row[a.link]
            FeedArticleOutcome(
                id = row[a.id],
                feedUrl = row[a.feedUrl]!!,
                category = row[a.category],
                link = link,
                status = row[a.status],
                dropReason = row[a.dropReason],
                fetchedAt = row[a.fetchedAt]!!,
                duplicateOf = row[a.duplicateOf],
                step1 = verdicts[row[a.category] to link],
                junkScore = junk[link]
            )
        }
    }

    /**
     * The junk-classifier hook: `link → score` when `articles` has [FeedStore.JUNK_SCORE_COLUMN],
     * empty otherwise. Raw JDBC because the column is not part of [ArticlesTable].
     */
    private fun Transaction.junkScores(since: LocalDateTime): Map<String, Double> {
        val jdbc = connection.connection as java.sql.Connection
        val col = FeedStore.JUNK_SCORE_COLUMN
        val present = jdbc.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(articles)").use { rs ->
                var found = false
                while (rs.next()) if (rs.getString("name").equals(col, ignoreCase = true)) found = true
                found
            }
        }
        if (!present) return emptyMap()
        // Timestamps are stored as `yyyy-MM-dd HH:mm:ss.SSS` text, so a text bound compares correctly.
        val bound = since.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return jdbc.prepareStatement(
            "SELECT link, $col FROM articles WHERE $col IS NOT NULL AND feed_url IS NOT NULL AND fetched_at >= ?"
        ).use { ps ->
            ps.setString(1, bound)
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getDouble(2)) }
            }
        }
    }

    override fun fetchFeedLastNew(): Map<String, LocalDateTime> = transaction {
        val a = ArticlesTable
        val newest = a.fetchedAt.max()
        a.select(a.feedUrl, newest)
            .where { a.feedUrl.isNotNull() }
            .groupBy(a.feedUrl)
            .mapNotNull { row -> row[newest]?.let { row[a.feedUrl]!! to it } }
            .toMap()
    }

    override fun fetchFeedTrackingSince(): LocalDateTime? = transaction {
        val oldest = ArticlesTable.fetchedAt.min()
        ArticlesTable.select(oldest).firstOrNull()?.get(oldest)
    }
}
