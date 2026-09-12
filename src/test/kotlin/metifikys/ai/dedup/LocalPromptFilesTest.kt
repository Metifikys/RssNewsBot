package metifikys.ai.dedup

import metifikys.config.CategoryConfig
import metifikys.config.DedupConfig
import metifikys.config.FeedConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke-checks every two-step dedup prompt file present under `prompts/` (the operator's
 * local files plus the shipped `*.example.yaml`): each must parse through [PromptLoader]
 * with all four prompt strings and carry the placeholders the pipeline substitutes.
 * Skipped when the directory holds no such files (a fresh checkout).
 */
class LocalPromptFilesTest {

    private val projectDir = File(".").canonicalFile

    private fun cat(promptFile: String): CategoryConfig = CategoryConfig(
        emoji = "🔬",
        feeds = listOf(FeedConfig("https://example.com/rss")),
        channelId = "@ch",
        dedup = DedupConfig(promptFile = promptFile)
    )

    @Test
    fun `every dedup prompt file under prompts resolves with all placeholders`() {
        val files = File(projectDir, "prompts").listFiles { f ->
            f.isFile && f.name.matches(Regex("""[a-z]+-dedup(\.example)?\.yaml"""))
        }.orEmpty().sortedBy { it.name }
        assumeTrue(files.isNotEmpty(), "no prompts/*-dedup*.yaml on this machine")

        val loader = PromptLoader(baseDir = projectDir)
        for (f in files) {
            val rel = "prompts/${f.name}"
            val r = assertNotNull(loader.resolve(cat(rel)), "$rel did not resolve (missing/blank field or YAML error)")
            for (v in listOf("{{CURRENT_BATCH_JSON}}", "{{PREVIOUSLY_COVERED_EVENTS_JSON}}")) {
                assertTrue(v in r.extractUser, "$rel extract.user lacks $v")
            }
            assertTrue("{{SHORTLIST_JSON}}" in r.renderUser, "$rel render.user lacks {{SHORTLIST_JSON}}")
            assertTrue("•" in r.renderSystem, "$rel render.system does not ask for the • bullet TopicFormatter splits on")
        }
    }
}
