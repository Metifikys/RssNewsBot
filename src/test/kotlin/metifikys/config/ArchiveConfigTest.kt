package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The optional `archive:` block (copy-before-delete retention). */
class ArchiveConfigTest {

    private fun load(archive: String): AppConfig {
        val file = File.createTempFile("config-archive-test", ".yaml")
        file.writeText(
            """
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 30
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech"
                feeds:
                  - https://example.com/tech
            """.trimIndent() + "\n" + archive.trimIndent()
        )
        try {
            return ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `absent block means no archive`() {
        assertNull(load("").archive)
    }

    @Test
    fun `defaults are off, monthly, labelled, every archivable table`() {
        val a = load("archive: {}").archive!!
        assertFalse(a.enabled)
        assertEquals("monthly", a.rotation)
        assertEquals("labelled", a.embeddings)
        assertEquals(ArchiveConfig.ARCHIVABLE_TABLES, a.tables)
        assertEquals(0, a.keepFiles)
    }

    @Test
    fun `explicit values are parsed`() {
        val a = load(
            """
            archive:
              enabled: true
              dir: "/data/archive"
              rotation: quarterly
              embeddings: all
              tables: [llm_calls, covered_events]
              keepFiles: 12
              minFreeDiskMb: 512
            """
        ).archive!!
        assertTrue(a.enabled)
        assertEquals("/data/archive", a.dir)
        assertEquals("quarterly", a.rotation)
        assertEquals(listOf("llm_calls", "covered_events"), a.tables)
        assertEquals(12, a.keepFiles)
        assertEquals(512L, a.minFreeDiskMb)
    }

    @Test
    fun `invalid values are rejected at load`() {
        assertThrows<IllegalArgumentException> { load("archive:\n  rotation: weekly") }
        assertThrows<IllegalArgumentException> { load("archive:\n  embeddings: some") }
        assertThrows<IllegalArgumentException> { load("archive:\n  keepFiles: -1") }
        assertThrows<IllegalArgumentException> { load("archive:\n  dir: \"../elsewhere\"") }
        assertThrows<IllegalArgumentException> { load("archive:\n  tables: [articles]") }
    }
}
