package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
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
