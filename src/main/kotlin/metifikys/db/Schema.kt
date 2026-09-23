package metifikys.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The main DB's table registry. [create] runs on every start and creates missing tables and
 * columns for everything in [ALL_TABLES], so a new `object … : Table` must be listed here or it
 * is never created (`SchemaTest` fails when one is missing).
 */
internal object Schema {

    val ALL_TABLES: Array<Table> = arrayOf(
        ArticlesTable, PendingBatchesTable, SummariesTable, CoveredEventsTable, RejectedEventsTable,
        LlmCallsTable, ArticleEmbeddingsTable, EventEmbeddingsTable,
        DigestMessagesTable, ReactionCountsTable, AudienceAffinityTable, BotStateTable,
        BadgeCacheTable, EntityIntrosTable, TrackerEntriesTable, AdminMutesTable
    )

    fun create() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES)
        }
    }
}
