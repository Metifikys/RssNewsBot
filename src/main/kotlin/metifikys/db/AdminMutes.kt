package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Keyword mutes added from Telegram (`/mute`, an approved mute suggestion, a moderation button),
 * layered over the YAML lists (`preferences.mute`, `categories.*.mute`): the effective list is
 * config ∪ this table. They live in the DB rather than in `config.yaml` because the production
 * config is synced by hand — the next sync would silently wipe a write — and the DB is what the
 * backup script already saves. `category` null = every category. `source` is `command`,
 * `suggestion` or `moderation`; `created_by` the Telegram user id.
 */
object AdminMutesTable : Table("admin_mutes") {
    val id = integer("id").autoIncrement()
    val keyword = varchar("keyword", 200)
    val category = varchar("category", 100).nullable()
    val createdAt = datetime("created_at")
    val createdBy = long("created_by").default(0)
    val origin = varchar("source", 20).default("command")

    override val primaryKey = PrimaryKey(id)
}

/** One DB mute; see [AdminMutesTable]. */
data class AdminMuteRow(
    val id: Int,
    val keyword: String,
    val category: String?,
    val createdAt: LocalDateTime,
    val createdBy: Long,
    val source: String
)

interface AdminMuteStore {
    /** Adds a mute and returns its id. The caller checks for duplicates (case-insensitive). */
    fun insertAdminMute(keyword: String, category: String?, createdBy: Long, source: String): Int

    /** Every DB mute, oldest first. */
    fun fetchAdminMutes(): List<AdminMuteRow>

    /** Removes the DB mutes whose keyword equals [keyword] (case-insensitively) in exactly [category]'s scope. */
    fun deleteAdminMutes(keyword: String, category: String?): Int
}

internal class AdminMuteRepository : AdminMuteStore {

    override fun insertAdminMute(keyword: String, category: String?, createdBy: Long, source: String): Int = transaction {
        AdminMutesTable.insert {
            it[AdminMutesTable.keyword] = keyword
            it[AdminMutesTable.category] = category
            it[createdAt] = LocalDateTime.now()
            it[AdminMutesTable.createdBy] = createdBy
            it[AdminMutesTable.origin] = source
        } get AdminMutesTable.id
    }

    override fun fetchAdminMutes(): List<AdminMuteRow> = transaction {
        AdminMutesTable.selectAll().orderBy(AdminMutesTable.id).map {
            AdminMuteRow(
                id = it[AdminMutesTable.id],
                keyword = it[AdminMutesTable.keyword],
                category = it[AdminMutesTable.category],
                createdAt = it[AdminMutesTable.createdAt],
                createdBy = it[AdminMutesTable.createdBy],
                source = it[AdminMutesTable.origin]
            )
        }
    }

    override fun deleteAdminMutes(keyword: String, category: String?): Int {
        // Matched in-process: the table holds a handful of rows, and SQLite's lower() is ASCII-only.
        val ids = fetchAdminMutes()
            .filter { it.keyword.equals(keyword, ignoreCase = true) && it.category == category }
            .map { it.id }
        if (ids.isEmpty()) return 0
        return transaction { AdminMutesTable.deleteWhere { id inList ids } }
    }
}
