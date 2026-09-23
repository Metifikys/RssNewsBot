package metifikys.db

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.model.Article
import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.math.ceil

private val logger = KotlinLogging.logger {}

class NewsDatabase(dbPath: String) :
    ArticleStore by ArticleRepository(),
    BatchStore by BatchRepository(),
    SummaryStore by SummaryRepository(),
    CoveredEventStore by CoveredEventRepository(),
    RejectedEventStore by RejectedEventRepository(),
    LlmCallStore by LlmCallRepository(),
    StatusQueryStore by StatusQueryRepository(),
    EmbeddingStore by EmbeddingRepository(),
    DigestMessageStore by DigestMessageRepository(),
    AffinityStore by AffinityRepository(),
    BadgeCacheStore by BadgeCacheRepository(),
    TrackerStore by TrackerRepository(),
    EntityIntroStore by EntityIntroRepository(),
    BotStateStore by BotStateRepository() {

    /** Canonical path of the DB file; side jobs (e.g. [RetentionArchiver]) place their files next to it. */
    val canonicalPath: String

    /** JDBC URL of the main DB (WAL + busy_timeout), for raw-JDBC side jobs such as [RetentionArchiver]. */
    val jdbcUrl: String

    init {
        // Security: reject path traversal and resolve to a canonical absolute path
        require(dbPath.isNotBlank()) { "Database path must not be blank" }
        require(!dbPath.contains("..")) { "Database path must not contain '..' (path traversal): $dbPath" }
        canonicalPath = java.io.File(dbPath).canonicalPath

        // Concurrency hardening for the multi-writer workload (category workers, reaction poller,
        // batch callbacks, weekly digest). Xerial parses these query params into SQLiteConfig and
        // applies them per-connection, so every Exposed `transaction { }` (each opens its own
        // connection) is covered:
        //   journal_mode=WAL   — readers no longer block the single writer; kills most
        //                        "database is locked". WAL is a persistent property of the db file.
        //   synchronous=NORMAL — SQLite-recommended pairing with WAL; holds write locks for less
        //                        time. Only the last transaction(s) can be lost on an OS/power
        //                        crash (not on a normal app crash) — fine for a news bot.
        //   busy_timeout=15000 — a writer that still hits a held write lock waits-and-retries up
        //                        to 15s instead of failing fast with SQLITE_BUSY.
        jdbcUrl = "jdbc:sqlite:$canonicalPath" +
            "?journal_mode=WAL" +
            "&synchronous=NORMAL" +
            "&busy_timeout=15000"
        Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                ArticlesTable, PendingBatchesTable, SummariesTable, CoveredEventsTable, RejectedEventsTable,
                LlmCallsTable, ArticleEmbeddingsTable, EventEmbeddingsTable,
                DigestMessagesTable, ReactionCountsTable, AudienceAffinityTable, BotStateTable,
                BadgeCacheTable, EntityIntrosTable, TrackerEntriesTable
            )
        }
        migrateArticleStatuses(jdbcUrl)
    }

    private fun migrateArticleStatuses(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                // Confirm WAL actually engaged. SQLite silently falls back to a rollback journal
                // when the db file is on a network share; production is local disk, so expect "wal".
                statement.executeQuery("PRAGMA journal_mode=WAL").use { rs ->
                    val mode = if (rs.next()) rs.getString(1) else "?"
                    logger.info { "SQLite journal_mode=$mode" }
                }

                val hasProcessedColumn = statement.executeQuery("PRAGMA table_info(articles)").use { rs ->
                    var exists = false
                    while (rs.next()) {
                        if (rs.getString("name").equals("processed", ignoreCase = true)) {
                            exists = true
                            break
                        }
                    }
                    exists
                }

                if (hasProcessedColumn) {
                    statement.executeUpdate(
                        """
                        UPDATE articles
                        SET status = '${ArticleStatus.PROCESSED.name}'
                        WHERE processed = 1
                          AND status = '${ArticleStatus.UNPROCESSED.name}'
                        """.trimIndent()
                    )
                }

                // BUG-025: only touch rows that actually carry a stray timestamp, so the returned
                // count means "rows that violated the invariant" rather than "all non-PROCESSING
                // rows". A non-zero count signals a state-transition that failed to clear it at the
                // source (markProcessed/markUnprocessed both null it now) — worth surfacing.
                val fixed = statement.executeUpdate(
                    """
                    UPDATE articles
                    SET processing_started_at = NULL
                    WHERE status != '${ArticleStatus.PROCESSING.name}'
                      AND processing_started_at IS NOT NULL
                    """.trimIndent()
                )
                if (fixed > 0) {
                    logger.warn {
                        "[Migrate] Cleared a stray processing_started_at on $fixed non-PROCESSING row(s) — " +
                            "a state transition left the invariant broken (safety net caught it)."
                    }
                }

                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_articles_status ON articles(status)")
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_articles_processing_started_at ON articles(processing_started_at)"
                )
            }
        }
    }
}
