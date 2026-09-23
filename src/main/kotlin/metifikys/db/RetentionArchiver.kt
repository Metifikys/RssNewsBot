package metifikys.db

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.ArchiveConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Copy-before-delete retention (`archive:` config). Instead of dropping rows past their retention
 * window, [archiveAndDelete] moves them into per-period SQLite files next to the main DB
 * (`archive/news-archive-2026-09.db`), so the benches can train on more than the last month.
 *
 * Mechanics, on its own raw JDBC connection to the main DB (Exposed's `transaction {}` already
 * holds a transaction, and ATTACH is refused inside one):
 *  1. partition the expired rows by the month of the ROW's timestamp (a row from August lands in
 *     the August file even when it expires in September); timestamps are Exposed's TEXT
 *     `yyyy-MM-dd HH:mm:ss.SSS`, so prefix and string comparisons are exact;
 *  2. per period file: ATTACH it, create the table from the main DDL (`sqlite_master`), add any
 *     column main gained since (schema drift), then repeat in batches of [batchSize]:
 *     `BEGIN IMMEDIATE` → `INSERT OR IGNORE` into the archive (original ids / primary keys) →
 *     snapshot the referenced `articles` rows so the archive joins on its own → `DELETE` from main
 *     by the same rowids → `COMMIT`;
 *  3. DETACH.
 *
 * With main in WAL mode SQLite commits each file atomically but not the pair: a crash between the
 * two leaves the rows in both files, and the next run's `INSERT OR IGNORE` hits the same primary
 * keys — no duplicate, no loss.
 */
class RetentionArchiver(
    private val jdbcUrl: String,
    private val archiveDir: File,
    private val config: ArchiveConfig,
    /** Free bytes in the archive directory; injectable so tests can simulate a full disk. */
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val batchSize: Int = 5000
) {

    /** What to archive for one table: its timestamp column and which `articles` rows it references. */
    private class Spec(val table: String, val tsColumn: String, val articleRef: ArticleRef? = null)

    private enum class ArticleRef { BY_ID, BY_URL }

    private val archivedTables: Set<String> =
        config.tables.toSet().let { if (config.embeddings == "none") it - "article_embeddings" else it }

    /** True when [table]'s retention should go through [archiveAndDelete] rather than a plain DELETE. */
    fun handles(table: String): Boolean = table in archivedTables && table in SPECS

    /**
     * Archives, then deletes, [table]'s rows older than [cutoff]. Returns false without touching
     * anything when the table is not archived or the archive disk is below `minFreeDiskMb` — the
     * caller then runs its plain DELETE. Throws on SQL / IO failure; the rows then stay in the main
     * DB and the next cleanup retries.
     */
    fun archiveAndDelete(table: String, cutoff: LocalDateTime): Boolean {
        val spec = SPECS[table]
        if (spec == null || !handles(table)) return false
        archiveDir.mkdirs()
        val freeMb = usableSpace(archiveDir) / (1024 * 1024)
        if (freeMb < config.minFreeDiskMb) {
            logger.warn {
                "[Archive] only ${freeMb} MB free in ${archiveDir.path} (< archive.minFreeDiskMb=${config.minFreeDiskMb}) — " +
                    "$table rows past retention are deleted WITHOUT archiving"
            }
            return false
        }

        val cutoffText = cutoff.format(TS_FORMAT)
        var archived = 0
        var deleted = 0
        val files = mutableSetOf<String>()
        DriverManager.getConnection(jdbcUrl).use { conn ->
            val months = conn.prepareStatement(
                "SELECT DISTINCT substr(${q(spec.tsColumn)}, 1, 7) FROM main.${q(table)} WHERE ${q(spec.tsColumn)} < ?"
            ).use { ps ->
                ps.setString(1, cutoffText)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) rs.getString(1)?.let { add(it) } } }
            }.filter { MONTH.matches(it) }.sorted()

            for ((period, periodMonths) in months.groupBy { periodKey(it) }) {
                val file = File(archiveDir, "news-archive-$period.db")
                files += file.name
                conn.prepareStatement("ATTACH DATABASE ? AS arch").use { it.setString(1, file.path); it.execute() }
                try {
                    conn.createStatement().use { st ->
                        ensureArchiveTable(conn, st, table)
                        if (spec.articleRef != null) ensureArchiveTable(conn, st, "articles")
                        val cols = columns(conn, "main", table).joinToString(", ") { q(it) }
                        val articleCols = if (spec.articleRef != null) {
                            columns(conn, "main", "articles").joinToString(", ") { q(it) }
                        } else ""
                        for (month in periodMonths) {
                            val upper = minOf(YearMonth.parse(month).plusMonths(1).toString(), cutoffText)
                            while (true) {
                                val moved = moveBatch(conn, st, spec, cols, articleCols, month, upper)
                                if (moved == null) break
                                archived += moved.first
                                deleted += moved.second
                            }
                        }
                    }
                } finally {
                    conn.createStatement().use { it.execute("DETACH DATABASE arch") }
                }
            }
        }
        pruneOldFiles()
        if (deleted > 0) {
            logger.info {
                "[Archive] $table: $deleted row(s) past retention removed, $archived archived → ${files.sorted().joinToString()}"
            }
        }
        return true
    }

    /**
     * One `BEGIN IMMEDIATE … COMMIT` batch of rows in `[month, upper)`. Returns (archived, deleted)
     * counts, or null when the range is empty.
     */
    private fun moveBatch(
        conn: Connection,
        st: Statement,
        spec: Spec,
        cols: String,
        articleCols: String,
        month: String,
        upper: String
    ): Pair<Int, Int>? {
        val t = q(spec.table)
        val ts = q(spec.tsColumn)
        st.execute("BEGIN IMMEDIATE")
        try {
            val rowIds = conn.prepareStatement(
                "SELECT rowid FROM main.$t WHERE $ts >= ? AND $ts < ? ORDER BY rowid LIMIT $batchSize"
            ).use { ps ->
                ps.setString(1, month)
                ps.setString(2, upper)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
            if (rowIds.isEmpty()) {
                st.execute("COMMIT")
                return null
            }
            val inBatch = "rowid IN (${rowIds.joinToString(",")})"
            val keep = if (spec.table == "article_embeddings" && config.embeddings == "labelled") " AND $LABELLED" else ""
            val archived = st.executeUpdate(
                "INSERT OR IGNORE INTO arch.$t ($cols) SELECT $cols FROM main.$t WHERE $inBatch$keep"
            )
            when (spec.articleRef) {
                ArticleRef.BY_ID -> st.executeUpdate(
                    "INSERT OR IGNORE INTO arch.articles ($articleCols) SELECT $articleCols FROM main.articles " +
                        "WHERE id IN (SELECT article_id FROM main.$t WHERE $inBatch$keep)"
                )
                ArticleRef.BY_URL -> st.executeUpdate(
                    "INSERT OR IGNORE INTO arch.articles ($articleCols) SELECT $articleCols FROM main.articles " +
                        "WHERE link IN (SELECT url FROM main.$t WHERE $inBatch AND url != '')"
                )
                null -> Unit
            }
            val deleted = st.executeUpdate("DELETE FROM main.$t WHERE $inBatch")
            st.execute("COMMIT")
            return archived to deleted
        } catch (e: Exception) {
            runCatching { st.execute("ROLLBACK") }
            throw e
        }
    }

    /**
     * Creates `arch.<table>` from main's DDL when missing, then adds any column main has that the
     * archive lacks (added nullable — an archive only ever receives rows main already validated).
     */
    private fun ensureArchiveTable(conn: Connection, st: Statement, table: String) {
        val archCols = columns(conn, "arch", table)
        if (archCols.isEmpty()) {
            val ddl = conn.prepareStatement("SELECT sql FROM main.sqlite_master WHERE type = 'table' AND name = ?").use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            } ?: error("[Archive] table '$table' not found in the main DB")
            // sqlite_master holds the normalized `CREATE TABLE name (...)`; keep the body, retarget the schema.
            st.execute("CREATE TABLE IF NOT EXISTS arch.${q(table)} ${ddl.substring(ddl.indexOf('('))}")
        } else {
            val archSet = archCols.map { it.lowercase() }.toSet()
            for ((name, type) in columnTypes(conn, "main", table)) {
                if (name.lowercase() !in archSet) {
                    st.execute("ALTER TABLE arch.${q(table)} ADD COLUMN ${q(name)} $type")
                    logger.info { "[Archive] added column $table.$name to the archive (schema drift)" }
                }
            }
        }
        for (col in ARCHIVE_INDEXES[table].orEmpty()) {
            st.execute("CREATE INDEX IF NOT EXISTS arch.${q("idx_arch_${table}_$col")} ON ${q(table)} (${q(col)})")
        }
    }

    private fun columns(conn: Connection, schema: String, table: String): List<String> =
        columnTypes(conn, schema, table).map { it.first }

    private fun columnTypes(conn: Connection, schema: String, table: String): List<Pair<String, String>> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA $schema.table_info(${q(table)})").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name") to (rs.getString("type") ?: "")) }
            }
        }

    /** Deletes the oldest archive files beyond `keepFiles` (0 = keep everything). */
    private fun pruneOldFiles() {
        if (config.keepFiles <= 0) return
        val files = archiveDir.listFiles { f -> f.isFile && ARCHIVE_FILE.matches(f.name) }.orEmpty().sortedBy { it.name }
        for (old in files.dropLast(config.keepFiles)) {
            if (old.delete()) logger.info { "[Archive] removed ${old.name} (archive.keepFiles=${config.keepFiles})" }
            else logger.warn { "[Archive] could not remove ${old.name}" }
        }
    }

    /** `2026-08` → `2026-08` (monthly) | `2026-Q3` (quarterly) | `2026` (yearly). */
    internal fun periodKey(month: String): String = when (config.rotation) {
        "yearly" -> month.substring(0, 4)
        "quarterly" -> "${month.substring(0, 4)}-Q${(month.substring(5, 7).toInt() - 1) / 3 + 1}"
        else -> month
    }

    companion object {
        private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val MONTH = Regex("""\d{4}-\d{2}""")
        private val ARCHIVE_FILE = Regex("""news-archive-.+\.db""")

        private val SPECS: Map<String, Spec> = listOf(
            Spec("article_embeddings", "created_at", ArticleRef.BY_ID),
            Spec("event_embeddings", "created_at"),
            Spec("rejected_events", "extracted_at", ArticleRef.BY_URL),
            Spec("covered_events", "covered_at", ArticleRef.BY_URL),
            Spec("llm_calls", "ts"),
            Spec("summaries", "created_at"),
            Spec("digest_messages", "sent_at"),
            Spec("reaction_counts", "updated_at")
        ).associateBy { it.table }

        /** Archive-side indexes: the timestamp and the join keys the benches use. */
        private val ARCHIVE_INDEXES: Map<String, List<String>> =
            SPECS.mapValues { (_, s) -> listOf(s.tsColumn) } +
                mapOf(
                    "rejected_events" to listOf("extracted_at", "url"),
                    "covered_events" to listOf("covered_at", "url"),
                    "articles" to listOf("link")
                )

        /** `embeddings: labelled` — only vectors whose article reached Step 1 (the benches' labels). */
        private const val LABELLED =
            "article_id IN (SELECT a.id FROM main.articles a WHERE a.link IN " +
                "(SELECT url FROM main.covered_events UNION SELECT url FROM main.rejected_events))"

        private fun q(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""

        init {
            check(SPECS.keys == ArchiveConfig.ARCHIVABLE_TABLES.toSet()) {
                "RetentionArchiver specs out of sync with ArchiveConfig.ARCHIVABLE_TABLES"
            }
        }

        /** Builds the archiver for [config], resolving a relative `dir` against the main DB's directory. */
        fun create(config: ArchiveConfig, db: NewsDatabase): RetentionArchiver {
            val dir = File(config.dir).let { if (it.isAbsolute) it else File(File(db.canonicalPath).parentFile, config.dir) }
            return RetentionArchiver(db.jdbcUrl, dir, config)
        }
    }
}
