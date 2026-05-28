package metifikys.ai.dedup

import metifikys.config.CategoryConfig
import metifikys.config.DedupConfig
import metifikys.config.DedupPromptsInline
import metifikys.config.FeedConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptLoaderTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var loader: PromptLoader

    private fun cat(dedup: DedupConfig?): CategoryConfig = CategoryConfig(
        emoji = "🎮",
        feeds = listOf(FeedConfig("https://example.com/rss")),
        channelId = "@ch",
        dedup = dedup
    )

    @BeforeEach
    fun setup() {
        loader = PromptLoader(baseDir = tmp.toFile())
    }

    @AfterEach
    fun teardown() {
        // TempDir handles cleanup
    }

    private fun writePromptFile(relPath: String, contents: String): File {
        val f = File(tmp.toFile(), relPath)
        f.parentFile?.mkdirs()
        f.writeText(contents)
        return f
    }

    @Test
    fun `resolve returns null when dedup block is missing`() {
        assertNull(loader.resolve(cat(null)))
    }

    @Test
    fun `resolve loads all four fields from file`() {
        writePromptFile(
            "prompts/games.yaml",
            """
            extract:
              system: "EXT_SYS"
              user: "EXT_USER"
            render:
              system: "REN_SYS"
              user: "REN_USER"
            """.trimIndent()
        )
        val r = loader.resolve(cat(DedupConfig(promptFile = "prompts/games.yaml")))
        assertNotNull(r)
        assertEquals("EXT_SYS", r.extractSystem)
        assertEquals("EXT_USER", r.extractUser)
        assertEquals("REN_SYS", r.renderSystem)
        assertEquals("REN_USER", r.renderUser)
        assertEquals(7L, r.contextDays)
        assertEquals(200, r.maxContextEvents)
    }

    @Test
    fun `resolve inline overrides win over file`() {
        writePromptFile(
            "prompts/games.yaml",
            """
            extract:
              system: "FILE_EXT_SYS"
              user: "FILE_EXT_USER"
            render:
              system: "FILE_REN_SYS"
              user: "FILE_REN_USER"
            """.trimIndent()
        )
        val inline = DedupPromptsInline(extractSystem = "INLINE_EXT_SYS")
        val r = loader.resolve(cat(DedupConfig(promptFile = "prompts/games.yaml", prompts = inline)))
        assertNotNull(r)
        assertEquals("INLINE_EXT_SYS", r.extractSystem)
        assertEquals("FILE_EXT_USER", r.extractUser)  // falls back to file
    }

    @Test
    fun `resolve returns null when a required field is missing`() {
        writePromptFile(
            "prompts/incomplete.yaml",
            """
            extract:
              system: "EXT_SYS"
              user: "EXT_USER"
            render:
              system: "REN_SYS"
              # render.user missing
            """.trimIndent()
        )
        val r = loader.resolve(cat(DedupConfig(promptFile = "prompts/incomplete.yaml")))
        assertNull(r)
    }

    @Test
    fun `resolve returns null when prompt file is missing`() {
        val r = loader.resolve(cat(DedupConfig(promptFile = "prompts/does-not-exist.yaml")))
        assertNull(r)
    }

    @Test
    fun `resolve rejects path traversal in promptFile`() {
        writePromptFile(
            "safe.yaml",
            """
            extract:
              system: "X"
              user: "X"
            render:
              system: "X"
              user: "X"
            """.trimIndent()
        )
        val r = loader.resolve(cat(DedupConfig(promptFile = "../safe.yaml")))
        assertNull(r)
    }

    @Test
    fun `substitute replaces all known vars`() {
        val out = loader.substitute(
            "Batch={{CURRENT_BATCH_JSON}} Prev={{PREVIOUSLY_COVERED_EVENTS_JSON}}",
            mapOf("CURRENT_BATCH_JSON" to "[1,2]", "PREVIOUSLY_COVERED_EVENTS_JSON" to "[]")
        )
        assertEquals("Batch=[1,2] Prev=[]", out)
    }

    @Test
    fun `substitute ignores unknown vars and leaves them verbatim`() {
        val out = loader.substitute(
            "Known={{CURRENT_BATCH_JSON}} Unknown={{NOPE}}",
            mapOf("CURRENT_BATCH_JSON" to "[]")
        )
        assertTrue(out.contains("{{NOPE}}"))
    }

    @Test
    fun `resolve uses inline-only when no promptFile is given`() {
        val inline = DedupPromptsInline(
            extractSystem = "ES",
            extractUser = "EU",
            renderSystem = "RS",
            renderUser = "RU"
        )
        val r = loader.resolve(cat(DedupConfig(promptFile = null, prompts = inline)))
        assertNotNull(r)
        assertEquals("ES", r.extractSystem)
        assertEquals("EU", r.extractUser)
        assertEquals("RS", r.renderSystem)
        assertEquals("RU", r.renderUser)
    }
}
