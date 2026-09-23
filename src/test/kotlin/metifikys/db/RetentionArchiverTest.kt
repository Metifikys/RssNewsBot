package metifikys.db

import metifikys.config.ArchiveConfig
import metifikys.model.Article
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionArchiverTest {

    @TempDir
    lateinit var tmp: Path

    private val db by lazy { NewsDatabase(tmp.resolve("news.db").toString()) }
    private val archiveDir by lazy { tmp.resolve("archive").toFile() }

    private fun archiver(
        config: ArchiveConfig = ArchiveConfig(enabled = true, minFreeDiskMb = 0),
        freeBytes: Long = Long.MAX_VALUE
    ) = RetentionArchiver(db.jdbcUrl, archiveDir, config, usableSpace = { freeBytes }, batchSize = 2)

    private fun count(file: String, sql: String): Long =
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }

    private fun main(sql: String) = count(db.canonicalPath, sql)
    private fun arch(period: String, sql: String) = count(File(archiveDir, "news-archive-$period.db").path, sql)

    private fun exec(vararg sql: String) = DriverManager.getConnection(db.jdbcUrl).use { c ->
        c.createStatement().use { st -> sql.forEach { st.execute(it) } }
    }

    private fun llmCall(ts: LocalDateTime) = db.insertLlmCall("openai", "m", "tech", "RENDER", 1, 1, 0.0, ts)

    private fun article(link: String): Int {
        db.insertArticles(listOf(Article(category = "tech", title = "T $link", link = link, description = "d")))
        return db.fetchArticleIdsByLinks(listOf(link)).getValue(link)
    }

    private fun embedding(articleId: Int, createdAt: String) {
        db.saveEmbedding(articleId, "m", ByteArray(8))
        exec("UPDATE article_embeddings SET created_at = '$createdAt' WHERE article_id = $articleId")
    }

    private fun covered(url: String, coveredAt: String) = exec(
        "INSERT INTO covered_events (category, event_key, core_fact, url, covered_at) " +
            "VALUES ('tech', 'k-$url', 'f', '$url', '$coveredAt')"
    )

    @Test
    fun `expired rows move to the file of their own month and leave the main db`() {
        llmCall(LocalDateTime.of(2026, 8, 10, 12, 0))
        llmCall(LocalDateTime.of(2026, 8, 31, 23, 59))
        llmCall(LocalDateTime.of(2026, 9, 2, 8, 0))
        llmCall(LocalDateTime.of(2026, 9, 20, 8, 0)) // after the cutoff

        assertTrue(archiver().archiveAndDelete("llm_calls", LocalDateTime.of(2026, 9, 15, 0, 0)))

        assertEquals(2, arch("2026-08", "SELECT COUNT(*) FROM llm_calls"))
        assertEquals(1, arch("2026-09", "SELECT COUNT(*) FROM llm_calls"))
        assertEquals(1, main("SELECT COUNT(*) FROM llm_calls"))
        assertEquals(1, main("SELECT COUNT(*) FROM llm_calls WHERE ts >= '2026-09-20'"))
    }

    @Test
    fun `a rerun after a crash between the two commits neither duplicates nor loses rows`() {
        llmCall(LocalDateTime.of(2026, 8, 10, 12, 0))
        llmCall(LocalDateTime.of(2026, 8, 11, 12, 0))
        val cutoff = LocalDateTime.of(2026, 9, 1, 0, 0)
        archiver().archiveAndDelete("llm_calls", cutoff)
        // The "crash": archive committed, main did not — the same rows (same ids) are back in main.
        exec(
            "ATTACH DATABASE '${File(archiveDir, "news-archive-2026-08.db").path}' AS a",
            "INSERT INTO main.llm_calls SELECT * FROM a.llm_calls"
        )
        assertEquals(2, main("SELECT COUNT(*) FROM llm_calls"))

        archiver().archiveAndDelete("llm_calls", cutoff)

        assertEquals(2, arch("2026-08", "SELECT COUNT(*) FROM llm_calls"))
        assertEquals(0, main("SELECT COUNT(*) FROM llm_calls"))
    }

    @Test
    fun `a column added to main after the archive was created is added to the archive`() {
        llmCall(LocalDateTime.of(2026, 8, 10, 12, 0))
        archiver().archiveAndDelete("llm_calls", LocalDateTime.of(2026, 9, 1, 0, 0))

        exec("ALTER TABLE llm_calls ADD COLUMN reasoning_tokens INT")
        llmCall(LocalDateTime.of(2026, 8, 12, 12, 0))
        exec("UPDATE llm_calls SET reasoning_tokens = 42")
        archiver().archiveAndDelete("llm_calls", LocalDateTime.of(2026, 9, 1, 0, 0))

        assertEquals(2, arch("2026-08", "SELECT COUNT(*) FROM llm_calls"))
        assertEquals(1, arch("2026-08", "SELECT COUNT(*) FROM llm_calls WHERE reasoning_tokens = 42"))
    }

    @Test
    fun `labelled mode keeps only Step-1 labelled vectors and snapshots their articles`() {
        val labelled = article("https://x/labelled")
        val unlabelled = article("https://x/unlabelled")
        embedding(labelled, "2026-08-05 10:00:00.000")
        embedding(unlabelled, "2026-08-05 11:00:00.000")
        covered("https://x/labelled", "2026-09-10 10:00:00.000")

        archiver().archiveAndDelete("article_embeddings", LocalDateTime.of(2026, 9, 1, 0, 0))

        assertEquals(0, main("SELECT COUNT(*) FROM article_embeddings"), "both vectors leave main")
        assertEquals(1, arch("2026-08", "SELECT COUNT(*) FROM article_embeddings WHERE article_id = $labelled"))
        assertEquals(1, arch("2026-08", "SELECT COUNT(*) FROM article_embeddings"))
        assertEquals(1, arch("2026-08", "SELECT COUNT(*) FROM articles WHERE link = 'https://x/labelled'"))
        assertEquals(1, arch("2026-08", "SELECT COUNT(*) FROM articles"))
        assertEquals(2, main("SELECT COUNT(*) FROM articles"), "articles themselves stay in main")
    }

    @Test
    fun `all mode archives every vector`() {
        embedding(article("https://x/a"), "2026-08-05 10:00:00.000")
        embedding(article("https://x/b"), "2026-08-05 11:00:00.000")

        archiver(ArchiveConfig(enabled = true, minFreeDiskMb = 0, embeddings = "all"))
            .archiveAndDelete("article_embeddings", LocalDateTime.of(2026, 9, 1, 0, 0))

        assertEquals(2, arch("2026-08", "SELECT COUNT(*) FROM article_embeddings"))
        assertEquals(2, arch("2026-08", "SELECT COUNT(*) FROM articles"))
    }

    @Test
    fun `covered events carry their article snapshot by url`() {
        article("https://x/c")
        covered("https://x/c", "2026-07-01 10:00:00.000")
        covered("https://x/missing", "2026-07-02 10:00:00.000")

        archiver().archiveAndDelete("covered_events", LocalDateTime.of(2026, 9, 1, 0, 0))

        assertEquals(2, arch("2026-07", "SELECT COUNT(*) FROM covered_events"))
        assertEquals(1, arch("2026-07", "SELECT COUNT(*) FROM articles"))
        assertEquals(0, main("SELECT COUNT(*) FROM covered_events"))
    }

    @Test
    fun `low disk space falls back to the caller's plain delete`() {
        llmCall(LocalDateTime.of(2026, 8, 10, 12, 0))

        val handled = archiver(ArchiveConfig(enabled = true, minFreeDiskMb = 10), freeBytes = 1024)
            .archiveAndDelete("llm_calls", LocalDateTime.of(2026, 9, 1, 0, 0))

        assertFalse(handled)
        assertEquals(1, main("SELECT COUNT(*) FROM llm_calls"), "nothing touched — the caller deletes")
        assertFalse(File(archiveDir, "news-archive-2026-08.db").exists())
    }

    @Test
    fun `unlisted tables and embeddings none are left to the plain delete`() {
        val a = archiver(ArchiveConfig(enabled = true, minFreeDiskMb = 0, tables = listOf("llm_calls", "article_embeddings"), embeddings = "none"))
        assertTrue(a.handles("llm_calls"))
        assertFalse(a.handles("article_embeddings"))
        assertFalse(a.handles("summaries"))
        assertFalse(a.archiveAndDelete("summaries", LocalDateTime.now()))
    }

    @Test
    fun `quarterly and yearly rotation group months into one file`() {
        llmCall(LocalDateTime.of(2026, 7, 10, 12, 0))
        llmCall(LocalDateTime.of(2026, 8, 10, 12, 0))
        archiver(ArchiveConfig(enabled = true, minFreeDiskMb = 0, rotation = "quarterly"))
            .archiveAndDelete("llm_calls", LocalDateTime.of(2026, 9, 1, 0, 0))
        assertEquals(2, arch("2026-Q3", "SELECT COUNT(*) FROM llm_calls"))

        val yearly = archiver(ArchiveConfig(enabled = true, minFreeDiskMb = 0, rotation = "yearly"))
        assertEquals("2026", yearly.periodKey("2026-12"))
        assertEquals("2026-Q1", archiver(ArchiveConfig(rotation = "quarterly")).periodKey("2026-03"))
        assertEquals("2026-Q4", archiver(ArchiveConfig(rotation = "quarterly")).periodKey("2026-10"))
    }

    @Test
    fun `keepFiles removes the oldest archive files beyond the limit`() {
        llmCall(LocalDateTime.of(2026, 6, 10, 12, 0))
        llmCall(LocalDateTime.of(2026, 7, 10, 12, 0))
        llmCall(LocalDateTime.of(2026, 8, 10, 12, 0))

        archiver(ArchiveConfig(enabled = true, minFreeDiskMb = 0, keepFiles = 2))
            .archiveAndDelete("llm_calls", LocalDateTime.of(2026, 9, 1, 0, 0))

        val names = archiveDir.list().orEmpty().filter { it.endsWith(".db") }.sorted()
        assertEquals(listOf("news-archive-2026-07.db", "news-archive-2026-08.db"), names)
    }

    @Test
    fun `create resolves a relative dir next to the main db`() {
        val a = RetentionArchiver.create(ArchiveConfig(enabled = true, minFreeDiskMb = 0, dir = "arch-rel"), db)
        llmCall(LocalDateTime.of(2026, 8, 10, 12, 0))
        a.archiveAndDelete("llm_calls", LocalDateTime.of(2026, 9, 1, 0, 0))
        assertTrue(tmp.resolve("arch-rel").resolve("news-archive-2026-08.db").toFile().isFile)
    }
}
