package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * First-mention memory for the Wikipedia intro line: one row per (category, entity key) —
 * the normalized franchise / subject — recorded whether the lookup found an article
 * (`extract` set) or not (`extract` null), so a name is neither introduced twice nor searched
 * again every digest. Long-lived on purpose: `covered_events` retention (~90 days) would make
 * "first mention" a quarterly event.
 */
object EntityIntrosTable : Table("entity_intros") {
    val category = varchar("category", 100)
    val entityKey = varchar("entity_key", 200)
    val title = text("title").nullable()
    val extract = text("extract").nullable()
    val url = text("url").nullable()
    val lang = varchar("lang", 8).nullable()
    val introducedAt = datetime("introduced_at").index()

    override val primaryKey = PrimaryKey(category, entityKey)
}

/** One (category, entity) first-mention record; `extract == null` means "looked up, nothing found". */
data class EntityIntroRow(
    val category: String,
    val entityKey: String,
    val title: String?,
    val extract: String?,
    val url: String?,
    val lang: String?,
    val introducedAt: LocalDateTime
)

interface EntityIntroStore {
    fun getEntityIntro(category: String, entityKey: String): EntityIntroRow?

    /** Upserts the record for (category, entity key). */
    fun putEntityIntro(row: EntityIntroRow)

    fun deleteOldEntityIntros(days: Long = 730)
}

internal class EntityIntroRepository : EntityIntroStore {

    override fun getEntityIntro(category: String, entityKey: String): EntityIntroRow? = transaction {
        EntityIntrosTable
            .selectAll()
            .where { (EntityIntrosTable.category eq category) and (EntityIntrosTable.entityKey eq entityKey) }
            .firstOrNull()
            ?.let {
                EntityIntroRow(
                    category = it[EntityIntrosTable.category],
                    entityKey = it[EntityIntrosTable.entityKey],
                    title = it[EntityIntrosTable.title],
                    extract = it[EntityIntrosTable.extract],
                    url = it[EntityIntrosTable.url],
                    lang = it[EntityIntrosTable.lang],
                    introducedAt = it[EntityIntrosTable.introducedAt]
                )
            }
    }

    override fun putEntityIntro(row: EntityIntroRow) {
        transaction {
            val updated = EntityIntrosTable.update(
                { (EntityIntrosTable.category eq row.category) and (EntityIntrosTable.entityKey eq row.entityKey) }
            ) {
                it[title] = row.title
                it[extract] = row.extract
                it[url] = row.url
                it[lang] = row.lang
                it[introducedAt] = row.introducedAt
            }
            if (updated == 0) {
                EntityIntrosTable.insertIgnore {
                    it[category] = row.category
                    it[entityKey] = row.entityKey
                    it[title] = row.title
                    it[extract] = row.extract
                    it[url] = row.url
                    it[lang] = row.lang
                    it[introducedAt] = row.introducedAt
                }
            }
        }
    }

    override fun deleteOldEntityIntros(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            EntityIntrosTable.deleteWhere { introducedAt less cutoff }
        }
    }
}
