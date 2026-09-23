package metifikys.ai.dedup

import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.DedupConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.config.TrackerConfig
import metifikys.config.WeeklyConfig
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

    @Test
    fun `every dedup prompt file under prompts passes validation without errors`() {
        val files = File(projectDir, "prompts").listFiles { f ->
            f.isFile && f.name.matches(Regex("""[a-z]+-dedup(\.example)?\.yaml"""))
        }.orEmpty().sortedBy { it.name }
        assumeTrue(files.isNotEmpty(), "no prompts/*-dedup*.yaml on this machine")

        val validator = PromptValidator(PromptLoader(baseDir = projectDir), projectDir)
        for (f in files) {
            val name = f.name.substringBefore("-dedup")
            val findings = validator.validateCategory(name, cat("prompts/${f.name}"), config(cat("prompts/${f.name}")))
            assertTrue(findings.none { it.level == PromptFinding.Level.ERROR }, "prompts/${f.name}: $findings")
        }
    }

    @Test
    fun `trackers - the shipped example asks for eventDate`() {
        val trackers = listOf(TrackerConfig(id = "releases", title = "📅", eventTypes = listOf("release_date")))
        val validator = PromptValidator(PromptLoader(baseDir = projectDir), projectDir)
        val example = cat("prompts/games-dedup.example.yaml").copy(trackers = trackers)
        val findings = validator.validateCategory("games", example, config(example))
        assertTrue(findings.none { "eventDate" in it.message }, "$findings")
    }

    @Test
    fun `weekly prompt files under prompts pass validation without errors`() {
        val files = File(projectDir, "prompts").listFiles { f ->
            f.isFile && f.name.matches(Regex("""weekly(\.example)?\.yaml"""))
        }.orEmpty()
        assumeTrue(files.isNotEmpty(), "no prompts/weekly*.yaml on this machine")
        val validator = PromptValidator(PromptLoader(baseDir = projectDir), projectDir)
        for (f in files) {
            val cfg = config(cat("prompts/games-dedup.example.yaml"))
                .copy(weekly = WeeklyConfig(enabled = true, promptFile = "prompts/${f.name}"))
            val findings = validator.validateWeekly(cfg)
            assertTrue(findings.isEmpty(), "prompts/${f.name}: $findings")
        }
    }

    private fun config(c: CategoryConfig) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("games" to c)
    )
}
