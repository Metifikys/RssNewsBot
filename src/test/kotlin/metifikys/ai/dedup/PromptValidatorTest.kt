package metifikys.ai.dedup

import metifikys.ai.dedup.PromptFinding.Level
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.DedupConfig
import metifikys.config.DedupPromptsInline
import metifikys.config.DigestConfig
import metifikys.config.FeedConfig
import metifikys.config.FeedbackConfig
import metifikys.config.LongFormConfig
import metifikys.config.OpenAIConfig
import metifikys.config.PostConfig
import metifikys.config.RankerConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.config.TrackerConfig
import metifikys.config.WeeklyConfig
import metifikys.config.WeeklyPromptsInline
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptValidatorTest {

    @TempDir
    lateinit var tmp: Path

    private val goodExtractSystem = "Extract events. Return eventDate for releases."
    private val goodExtractUser = "Batch: {{CURRENT_BATCH_JSON}}\nCovered: {{PREVIOUSLY_COVERED_EVENTS_JSON}}\n{{CATEGORY}} {{EMOJI}}"
    private val goodRenderSystem = "Render bullets •. Items with \"depth\": \"long\" get two paragraphs."
    private val goodRenderUser = "{{EMOJI}} {{CATEGORY}}\n{{SHORTLIST_JSON}}"

    private fun inline(
        extractSystem: String? = goodExtractSystem,
        extractUser: String? = goodExtractUser,
        renderSystem: String? = goodRenderSystem,
        renderUser: String? = goodRenderUser
    ) = DedupPromptsInline(extractSystem, extractUser, renderSystem, renderUser)

    private fun cat(
        dedup: DedupConfig? = DedupConfig(prompts = inline()),
        post: PostConfig? = null,
        trackers: List<TrackerConfig> = emptyList()
    ) = CategoryConfig(
        emoji = "🎮",
        feeds = listOf(FeedConfig("https://example.com/rss")),
        channelId = "@ch",
        dedup = dedup,
        post = post,
        trackers = trackers
    )

    private fun config(
        cat: CategoryConfig = cat(),
        feedback: FeedbackConfig = FeedbackConfig(),
        weekly: WeeklyConfig? = null
    ) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("games" to cat),
        feedback = feedback,
        weekly = weekly
    )

    private val loader get() = PromptLoader(baseDir = tmp.toFile())

    private fun validate(config: AppConfig) = PromptValidator(loader, tmp.toFile()).validate(config)

    private fun write(rel: String, text: String): File =
        File(tmp.toFile(), rel).also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun List<PromptFinding>.at(level: Level) = filter { it.level == level }

    @Test
    fun `good inline prompts give no findings`() {
        assertEquals(emptyList(), validate(config()))
    }

    @Test
    fun `category without dedup is not checked`() {
        assertEquals(emptyList(), validate(config(cat(dedup = null))))
    }

    @Test
    fun `missing prompt file is an error and the unresolved slots are named`() {
        val f = validate(config(cat(dedup = DedupConfig(promptFile = "prompts/nope.yaml"))))
        val errors = f.at(Level.ERROR)
        assertTrue(errors.any { it.slot == "promptFile" && "missing or unreadable" in it.message }, "$f")
        assertTrue(errors.any { it.slot == "prompts" && "legacy" in it.message && "render.user" in it.message }, "$f")
    }

    @Test
    fun `dot-dot path and unparseable YAML are errors`() {
        val dotDot = validate(config(cat(dedup = DedupConfig(promptFile = "../x.yaml"))))
        assertTrue(dotDot.any { it.level == Level.ERROR && it.slot == "promptFile" && ".." in it.message }, "$dotDot")

        write("prompts/bad.yaml", "extract: [unclosed\n  system: x")
        val bad = validate(config(cat(dedup = DedupConfig(promptFile = "prompts/bad.yaml"))))
        assertTrue(bad.any { it.level == Level.ERROR && "parse error" in it.message }, "$bad")
    }

    @Test
    fun `one unresolved key is an error`() {
        val f = validate(config(cat(dedup = DedupConfig(prompts = inline(renderSystem = null)))))
        assertEquals(listOf("prompts"), f.at(Level.ERROR).map { it.slot })
        assertTrue("render.system" in f.single().message)
    }

    @Test
    fun `extract without the batch placeholder is an error`() {
        val f = validate(config(cat(dedup = DedupConfig(prompts = inline(extractUser = "Covered: {{PREVIOUSLY_COVERED_EVENTS_JSON}}")))))
        assertTrue(f.at(Level.ERROR).any { it.slot == "extract" && "CURRENT_BATCH_JSON" in it.message }, "$f")
    }

    @Test
    fun `batch placeholder in extract system is enough`() {
        val f = validate(
            config(cat(dedup = DedupConfig(prompts = inline(
                extractSystem = "eventDate {{CURRENT_BATCH_JSON}}", extractUser = "{{PREVIOUSLY_COVERED_EVENTS_JSON}}"
            ))))
        )
        assertEquals(emptyList(), f)
    }

    @Test
    fun `render user without shortlist is an error`() {
        val f = validate(config(cat(dedup = DedupConfig(prompts = inline(renderUser = "{{CATEGORY}}")))))
        assertEquals(listOf("render.user"), f.at(Level.ERROR).map { it.slot })
    }

    @Test
    fun `extract without covered events is a warning`() {
        val f = validate(config(cat(dedup = DedupConfig(prompts = inline(extractUser = "{{CURRENT_BATCH_JSON}}")))))
        assertEquals(emptyList(), f.at(Level.ERROR))
        assertTrue(f.at(Level.WARN).any { "PREVIOUSLY_COVERED_EVENTS_JSON" in it.message }, "$f")
    }

    @Test
    fun `unknown and whitespace placeholders are warnings, any placeholder in render system too`() {
        val f = validate(
            config(cat(dedup = DedupConfig(prompts = inline(
                extractUser = goodExtractUser + " {{CURRENT_BACTH_JSON}}",
                renderUser = goodRenderUser + " {{ CATEGORY }}",
                renderSystem = goodRenderSystem + " {{EMOJI}}"
            ))))
        )
        val warns = f.at(Level.WARN)
        assertTrue(warns.any { it.slot == "extract.user" && "{{CURRENT_BACTH_JSON}}" in it.message }, "$f")
        assertTrue(warns.any { it.slot == "render.user" && "{{ CATEGORY }}" in it.message }, "$f")
        assertTrue(warns.any { it.slot == "render.system" && "{{EMOJI}}" in it.message }, "$f")
        assertEquals(emptyList(), f.at(Level.ERROR))
    }

    @Test
    fun `audience signals without feedback is a warning, feedback with reaction weight and no block is info`() {
        val withBlock = cat(dedup = DedupConfig(prompts = inline(extractUser = goodExtractUser + "\n{{AUDIENCE_SIGNALS}}")))
        val off = validate(config(withBlock))
        assertTrue(off.at(Level.WARN).any { "AUDIENCE_SIGNALS" in it.message && "feedback.enabled=false" in it.message }, "$off")
        assertEquals(emptyList(), validate(config(withBlock, feedback = FeedbackConfig(enabled = true))))

        val ranked = cat(dedup = DedupConfig(prompts = inline(), digest = DigestConfig(ranker = RankerConfig(reactionWeight = 0.5))))
        val info = validate(config(ranked, feedback = FeedbackConfig(enabled = true)))
        assertEquals(listOf(Level.INFO), info.map { it.level })
    }

    @Test
    fun `long form without depth in render is a warning`() {
        val post = PostConfig(longForm = LongFormConfig())
        assertEquals(emptyList(), validate(config(cat(post = post))))
        val f = validate(config(cat(dedup = DedupConfig(prompts = inline(renderSystem = "Render bullets •.")), post = post)))
        assertTrue(f.at(Level.WARN).any { it.slot == "render" && "depth" in it.message }, "$f")
        val disabled = PostConfig(longForm = LongFormConfig(enabled = false))
        assertEquals(emptyList(), validate(config(cat(dedup = DedupConfig(prompts = inline(renderSystem = "x")), post = disabled))))
    }

    @Test
    fun `trackers without eventDate in extract is a warning`() {
        val trackers = listOf(TrackerConfig(id = "releases", title = "📅", eventTypes = listOf("release_date")))
        assertEquals(emptyList(), validate(config(cat(trackers = trackers))))
        val f = validate(config(cat(dedup = DedupConfig(prompts = inline(extractSystem = "Extract.")), trackers = trackers)))
        assertTrue(f.at(Level.WARN).any { "eventDate" in it.message }, "$f")
    }

    @Test
    fun `weekly - defaults pass, missing candidates is an error, missing file a warning`() {
        assertEquals(emptyList(), validate(config(weekly = WeeklyConfig(enabled = true))))
        assertEquals(emptyList(), validate(config(weekly = WeeklyConfig(enabled = false, prompts = WeeklyPromptsInline(user = "x")))))

        val noCandidates = validate(config(weekly = WeeklyConfig(enabled = true, prompts = WeeklyPromptsInline(user = "{{TOP_N}}"))))
        assertTrue(noCandidates.at(Level.ERROR).any { it.category == "weekly" && "CANDIDATES_JSON" in it.message }, "$noCandidates")

        val missing = validate(config(weekly = WeeklyConfig(enabled = true, promptFile = "prompts/weekly-nope.yaml")))
        assertEquals(listOf(Level.WARN), missing.map { it.level })
    }

    @Test
    fun `categoriesUsing matches the canonical prompt file`() {
        val file = write("prompts/games-dedup.yaml", "extract: {system: a, user: b}\nrender: {system: c, user: d}\n")
        val v = PromptValidator(loader, tmp.toFile())
        val cfg = config(cat(dedup = DedupConfig(promptFile = "prompts/games-dedup.yaml")))
        assertEquals(listOf("games"), v.categoriesUsing(file.canonicalFile, cfg))
        assertEquals(emptyList(), v.categoriesUsing(File(tmp.toFile(), "other.yaml").canonicalFile, cfg))
    }

    @Test
    fun `hot reload fires onReload only for a changed file`() {
        val file = write("prompts/games-dedup.yaml", "extract: {system: a, user: b}\nrender: {system: c, user: d}\n")
        val l = loader
        val reloaded = mutableListOf<File>()
        l.onReload = { reloaded += it }
        val c = cat(dedup = DedupConfig(promptFile = "prompts/games-dedup.yaml"))
        l.resolve(c)
        l.resolve(c)
        assertEquals(emptyList(), reloaded)
        file.writeText("extract: {system: a2, user: b}\nrender: {system: c, user: d}\n")
        file.setLastModified(file.lastModified() + 5_000)
        assertEquals("a2", l.resolve(c)?.extractSystem)
        assertEquals(listOf(file.canonicalFile), reloaded)
    }
}
