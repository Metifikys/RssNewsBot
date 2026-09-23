package metifikys.db

import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object ArticlesTable : Table("articles") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100)
    val title = varchar("title", 1000)
    val link = varchar("link", 2000).uniqueIndex()
    val description = text("description")
    val pubDate = datetime("pub_date")
    val status = varchar("status", 20).default(ArticleStatus.UNPROCESSED.name).index()
    val processingStartedAt = datetime("processing_started_at").nullable().index()
    val imageUrl = text("image_url").nullable()
    val summary = text("summary").nullable()
    /**
     * Canonical article id when this row was marked [ArticleStatus.DUPLICATE] by the
     * semantic-dedup detector. Null otherwise. Soft pointer — no FK constraint, since
     * the canonical row may be pruned by `deleteOlderThan` while the duplicate row's
     * own `pubDate` is still inside the retention window.
     */
    val duplicateOf = integer("duplicate_of").nullable()
    /** Source-specific signals captured at ingestion (JSON, see [metifikys.model.ArticleMeta]); null for most feeds. */
    val metaJson = text("meta_json").nullable()

    override val primaryKey = PrimaryKey(id)
}
