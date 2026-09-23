package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * The telegra.ph pages the long-form posts published (`post.longForm.telegraph`), one row per
 * page. Pages are public and cannot be deleted through the API, so this is also the record of
 * what is out there; a later `editPage` (an update section for a `meaningful_update`) needs the
 * [path] of the event's page.
 */
object TelegraphPagesTable : Table("telegraph_pages") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100)
    val eventKey = varchar("event_key", 3000)
    val path = varchar("path", 300)
    val url = varchar("url", 500)
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_telegraph_pages_cat_event", false, category, eventKey)
    }
}

data class TelegraphPageRow(
    val category: String,
    val eventKey: String,
    val path: String,
    val url: String,
    val createdAt: LocalDateTime
)

interface TelegraphPageStore {
    fun insertTelegraphPage(row: TelegraphPageRow)

    /** The newest page published for (category, event key), or null. */
    fun fetchLatestTelegraphPage(category: String, eventKey: String): TelegraphPageRow?
}

internal class TelegraphPageRepository : TelegraphPageStore {

    override fun insertTelegraphPage(row: TelegraphPageRow) {
        transaction {
            TelegraphPagesTable.insert {
                it[category] = row.category
                it[eventKey] = row.eventKey
                it[path] = row.path
                it[url] = row.url
                it[createdAt] = row.createdAt
            }
        }
    }

    override fun fetchLatestTelegraphPage(category: String, eventKey: String): TelegraphPageRow? = transaction {
        TelegraphPagesTable
            .selectAll()
            .where { (TelegraphPagesTable.category eq category) and (TelegraphPagesTable.eventKey eq eventKey) }
            .orderBy(TelegraphPagesTable.createdAt to SortOrder.DESC, TelegraphPagesTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let {
                TelegraphPageRow(
                    category = it[TelegraphPagesTable.category],
                    eventKey = it[TelegraphPagesTable.eventKey],
                    path = it[TelegraphPagesTable.path],
                    url = it[TelegraphPagesTable.url],
                    createdAt = it[TelegraphPagesTable.createdAt]
                )
            }
    }
}
