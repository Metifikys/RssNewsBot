package metifikys.db

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.model.ArticleStatus
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * Raw-JDBC fix-ups that run on every start, after [Schema.create]. New columns need no entry here
 * (`createMissingTablesAndColumns` adds them); this is for data rewrites and indexes Exposed
 * does not manage. Every step must be idempotent.
 */
internal object SchemaMigrations {

    fun run(jdbcUrl: String) {
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
