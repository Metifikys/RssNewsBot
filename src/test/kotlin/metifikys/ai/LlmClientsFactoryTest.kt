package metifikys.ai

import io.mockk.mockk
import metifikys.config.AnthropicConfig
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.ClaudeCliConfig
import metifikys.config.CodexCliConfig
import metifikys.config.CategoryLlmOverrides
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.LlmOverride
import metifikys.config.OpenAIConfig
import metifikys.config.OpenRouterConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.NewsDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LlmClientsFactoryTest {

    private val db: NewsDatabase = mockk(relaxed = true)

    private fun config(
        withOpenRouter: Boolean = true,
        orModels: List<String> = emptyList(),
        withAnthropic: Boolean = false,
        withClaudeCli: Boolean = false,
        claudeCliModel: String = "claude-cli-default",
        withCodexCli: Boolean = false,
        codexCliModel: String = "codex-cli-default",
        categories: Map<String, CategoryConfig> = emptyMap()
    ) = AppConfig(
        telegram = TelegramConfig(botToken = "tok"),
        openai = OpenAIConfig(apiKey = "sk-test", model = "gpt-default", batchModel = "gpt-batch-default"),
        openrouter = if (withOpenRouter) OpenRouterConfig(apiKey = "or", model = "router-default", models = orModels) else null,
        anthropic = if (withAnthropic) AnthropicConfig(
            apiKey = "ant-key",
            model = "claude-default",
            batchModel = "claude-batch-default"
        ) else null,
        claudeCli = if (withClaudeCli) ClaudeCliConfig(command = "claude", model = claudeCliModel) else null,
        codexCli = if (withCodexCli) CodexCliConfig(command = "codex", model = codexCliModel) else null,
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = categories
    )

    private fun cat(llm: CategoryLlmOverrides? = null) = CategoryConfig(
        emoji = "x",
        feeds = listOf(FeedConfig("https://example.com/feed")),
        channelId = "@x",
        llm = llm
    )

    private fun split(vararg entries: LlmOverride) = LlmOverride(providers = entries.toList())

    // ─── weighted providers split ───

    @Test
    fun `summarize auto follows the category split and a blank openrouter entry walks the ladder`() {
        val factory = LlmClientsFactory(
            config(withAnthropic = true, orModels = listOf("or/m1", "or/m2")), db, splitter = LlmSplitter(null)
        )
        val cat = cat(
            CategoryLlmOverrides(
                summarize = split(
                    LlmOverride("openai", "gpt-mini", percent = 20),
                    LlmOverride("anthropic", "claude-haiku", percent = 10),
                    LlmOverride("openrouter", percent = 70)
                )
            )
        )
        val models = (1..10).map {
            val c = factory.forSummarize(cat, "auto")
            if (c is FallbackLlmClient) c.primary.endpoint.model else c.endpoint.model
        }
        assertEquals(2, models.count { it == "gpt-mini" })
        assertEquals(1, models.count { it == "claude-haiku" })
        assertEquals(7, models.count { it == "or/m1" })
    }

    @Test
    fun `a feed naming a provider stays on it and takes only the split entry on that provider`() {
        val splitter = LlmSplitter(null)
        val factory = LlmClientsFactory(config(withAnthropic = true), db, splitter = splitter)
        val cat = cat(
            CategoryLlmOverrides(
                summarize = split(LlmOverride("openai", "gpt-mini", percent = 30), LlmOverride("anthropic", "claude-haiku", percent = 70))
            )
        )
        repeat(3) { assertEquals("claude-haiku", factory.forSummarize(cat, "anthropic").endpoint.model) }
        repeat(3) { assertEquals("gpt-mini", factory.forSummarize(cat, "openai").endpoint.model) }
        // No entry on openrouter: the feed's provider with its global default.
        assertEquals("router-default", factory.forSummarize(cat, "openrouter").endpoint.model)
        assertTrue(splitter.snapshot().isEmpty(), "a pinned feed never counts as a pick")
    }

    @Test
    fun `summarize auto without a category override uses the global sync default`() {
        assertEquals("router-default", LlmClientsFactory(config(), db).forSummarize(cat(), "auto").endpoint.model)
        assertEquals("gpt-default", LlmClientsFactory(config(withOpenRouter = false), db).forSummarize(null, "auto").endpoint.model)
        val plain = cat(CategoryLlmOverrides(summarize = LlmOverride("openai", "gpt-mini")))
        assertEquals("gpt-mini", LlmClientsFactory(config(), db).forSummarize(plain, "auto").endpoint.model)
    }

    @Test
    fun `batchFallback and render keep separate tallies although both meter as RENDER`() {
        val shared = split(LlmOverride("openai", "gpt-a", percent = 50), LlmOverride("openai", "gpt-b", percent = 50))
        val factory = LlmClientsFactory(config(), db, splitter = LlmSplitter(null))
        val cat = cat(CategoryLlmOverrides(render = shared, batchFallback = shared))
        assertEquals("gpt-a", factory.forRender(cat).endpoint.model)
        assertEquals("gpt-a", factory.forBatchFallback(cat)!!.endpoint.model)
        assertEquals("gpt-b", factory.forRender(cat).endpoint.model)
    }

    @Test
    fun `forExtractResume picks the batch entry by batch id and does not count as a pick`() {
        val splitter = LlmSplitter(null)
        val factory = LlmClientsFactory(config(withAnthropic = true), db, splitter = splitter)
        val cat = cat(
            CategoryLlmOverrides(
                extract = split(
                    LlmOverride("openai", "gpt-batch", batch = true, percent = 50),
                    LlmOverride("anthropic", "claude-batch", batch = true, percent = 50)
                )
            )
        )
        assertTrue(factory.forExtractResume(cat, "msgbatch_01abc") is AnthropicWithBatch)
        assertTrue(factory.forExtractResume(cat, "batch_abc") is OpenAIWithBatch)
        assertTrue(splitter.snapshot().isEmpty())
    }

    @Test
    fun `forRender with no override picks openrouter when present`() {
        val factory = LlmClientsFactory(config(withOpenRouter = true), db)
        val client = factory.forRender(null)
        assertEquals("https://openrouter.ai/api/v1", client.endpoint.baseUrl)
        assertEquals("router-default", client.endpoint.model)
    }

    @Test
    fun `forRender with no override falls back to openai when no openrouter`() {
        val factory = LlmClientsFactory(config(withOpenRouter = false), db)
        val client = factory.forRender(null)
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-default", client.endpoint.model)
    }

    @Test
    fun `forRender with override uses override provider+model`() {
        val factory = LlmClientsFactory(config(), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("openai", "gpt-special")))
        val client = factory.forRender(cat)
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-special", client.endpoint.model)
    }

    @Test
    fun `forPersona uses its own override, else the render one, else the sync default — metered as PERSONA`() {
        val recorder = mockk<LlmCallRecorder>(relaxed = true)
        val own = cat(CategoryLlmOverrides(persona = LlmOverride("openai", "gpt-mini"), render = LlmOverride("openai", "gpt-render")))
        val render = cat(CategoryLlmOverrides(render = LlmOverride("openai", "gpt-render")))
        val factory = LlmClientsFactory(config(categories = mapOf("g" to own, "r" to render)), db, recorder)
        assertEquals("gpt-mini", factory.forPersona(own).endpoint.model)
        assertEquals("gpt-render", factory.forPersona(render).endpoint.model)
        assertEquals("router-default", factory.forPersona(null).endpoint.model)
        val metered = factory.forPersona(own)
        assertTrue(metered is MeteredLlmClient)
        assertEquals(LlmUseCase.PERSONA, metered.useCase)
    }

    @Test
    fun `forUpdateDiff uses its own override, else the render one, else the sync default — metered as UPDATE_DIFF`() {
        val recorder = mockk<LlmCallRecorder>(relaxed = true)
        val own = cat(CategoryLlmOverrides(updateDiff = LlmOverride("openai", "gpt-mini"), render = LlmOverride("openai", "gpt-render")))
        val render = cat(CategoryLlmOverrides(render = LlmOverride("openai", "gpt-render")))
        val factory = LlmClientsFactory(config(categories = mapOf("g" to own, "r" to render)), db, recorder)
        assertEquals("gpt-mini", factory.forUpdateDiff(own).endpoint.model)
        assertEquals("gpt-render", factory.forUpdateDiff(render).endpoint.model)
        assertEquals("router-default", factory.forUpdateDiff(null).endpoint.model)
        val metered = factory.forUpdateDiff(own)
        assertTrue(metered is MeteredLlmClient)
        assertEquals(LlmUseCase.UPDATE_DIFF, metered.useCase)
    }

    @Test
    fun `forExtract with no override alternates openai and openrouter as a built-in 50-50 split`() {
        val factory = LlmClientsFactory(config(withOpenRouter = true), db, splitter = LlmSplitter(null))
        val first = factory.forExtract(null)
        assertEquals("https://api.openai.com/v1", first.endpoint.baseUrl)
        assertEquals("gpt-default", first.endpoint.model)
        val second = factory.forExtract(null)
        assertEquals("https://openrouter.ai/api/v1", second.endpoint.baseUrl)
        assertEquals("router-default", second.endpoint.model)
        assertEquals("gpt-default", factory.forExtract(null).endpoint.model)
    }

    @Test
    fun `forExtract with no openrouter always uses openai`() {
        val factory = LlmClientsFactory(config(withOpenRouter = false), db)
        repeat(3) { assertEquals("https://api.openai.com/v1", factory.forExtract(null).endpoint.baseUrl) }
    }

    @Test
    fun `forExtract with a plain override always uses it`() {
        val factory = LlmClientsFactory(config(), db)
        val cat = cat(CategoryLlmOverrides(extract = LlmOverride("openrouter", "alt-model")))
        repeat(3) {
            val client = factory.forExtract(cat)
            assertEquals("https://openrouter.ai/api/v1", client.endpoint.baseUrl)
            assertEquals("alt-model", client.endpoint.model)
        }
    }

    @Test
    fun `forExtract with a 50-50 providers split alternates the two entries`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db, splitter = LlmSplitter(null))
        val cat = cat(
            CategoryLlmOverrides(
                extract = split(LlmOverride("openai", "gpt-a", percent = 50), LlmOverride("anthropic", "claude-b", percent = 50))
            )
        )
        assertEquals(listOf("gpt-a", "claude-b", "gpt-a", "claude-b"), (1..4).map { factory.forExtract(cat).endpoint.model })
    }

    @Test
    fun `forBatch with no override uses default batchModel`() {
        val factory = LlmClientsFactory(config(), db)
        val client = factory.forBatch(null)
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-batch-default", client.endpoint.model)
        assertTrue(client is OpenAIWithBatch)
    }

    @Test
    fun `forBatch with override uses override model on openai endpoint`() {
        val factory = LlmClientsFactory(config(), db)
        val cat = cat(CategoryLlmOverrides(batch = LlmOverride("openai", "gpt-batch-2026")))
        val client = factory.forBatch(cat)
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-batch-2026", client.endpoint.model)
        assertTrue(client is OpenAIWithBatch)
    }

    @Test
    fun `forSummarize per-feed provider wins when category override targets a different provider`() {
        val factory = LlmClientsFactory(config(), db)
        // Category override says openai, but the feed explicitly opted into openrouter.
        // Per-feed wins — we must not silently re-route to openai.
        val cat = cat(CategoryLlmOverrides(summarize = LlmOverride("openai", "gpt-summary")))
        val client = factory.forSummarize(cat, "openrouter")
        assertEquals("https://openrouter.ai/api/v1", client.endpoint.baseUrl)
        assertEquals("router-default", client.endpoint.model)
    }

    @Test
    fun `forSummarize category override refines the model when its provider matches the feed's`() {
        val factory = LlmClientsFactory(config(), db)
        // Category override and feed agree on openai → override's model takes effect.
        val cat = cat(CategoryLlmOverrides(summarize = LlmOverride("openai", "gpt-summary")))
        val client = factory.forSummarize(cat, "openai")
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-summary", client.endpoint.model)
    }

    @Test
    fun `forSummarize with no override uses per-feed provider plus its global model`() {
        val factory = LlmClientsFactory(config(), db)
        val client = factory.forSummarize(cat(), "openrouter")
        assertEquals("https://openrouter.ai/api/v1", client.endpoint.baseUrl)
        assertEquals("router-default", client.endpoint.model)
    }

    @Test
    fun `caching returns same instance for same baseUrl model and batchCapable`() {
        val factory = LlmClientsFactory(config(), db)
        val a = factory.forRender(null)
        val b = factory.forRender(null)
        assertSame(a, b)
    }

    @Test
    fun `sync and batch capable variants are cached separately`() {
        val factory = LlmClientsFactory(config(), db)
        val syncClient = factory.forRender(null)  // forSync → openrouter
        // forBatch ignores override.provider for endpoint → always openai
        val batchClient = factory.forBatch(null)
        // Different baseUrls so they're trivially different. Use a same-baseUrl test instead:
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("openai", "gpt-batch-default")))
        val syncOnOpenAI = factory.forRender(cat)
        // syncOnOpenAI and batchClient share (openai baseUrl, gpt-batch-default model) but different batchCapable
        assertEquals(syncOnOpenAI.endpoint.baseUrl, batchClient.endpoint.baseUrl)
        assertEquals(syncOnOpenAI.endpoint.model, batchClient.endpoint.model)
        // Yet they are different instances (sync-only vs batch-capable)
        assertTrue(batchClient is OpenAIWithBatch)
        assertTrue(syncOnOpenAI is OpenAI)
        assertTrue(syncClient is OpenAI)
    }

    @Test
    fun `sync client on openrouter throws on submitCategoryBatch`() {
        val factory = LlmClientsFactory(config(), db)
        val client = factory.forRender(null)  // openrouter sync client
        val ex = assertThrows<UnsupportedOperationException> {
            client.submitCategoryBatch("x", mockk(relaxed = true))
        }
        assertEquals(true, ex.message?.contains("OpenRouter does not support the Batch API"))
    }

    @Test
    fun `sync client on openai throws on submitCategoryBatch with openai-specific message`() {
        val factory = LlmClientsFactory(config(withOpenRouter = false), db)
        val client = factory.forRender(null)  // forSync → openai (no router)
        val ex = assertThrows<UnsupportedOperationException> {
            client.resumeBatch("any")
        }
        assertEquals(true, ex.message?.contains("construct OpenAIWithBatch"))
    }

    // ── Anthropic provider ────────────────────────────────────────────────────

    @Test
    fun `forRender with anthropic override uses anthropic endpoint`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("anthropic", "claude-render")))
        val client = factory.forRender(cat)
        assertEquals("https://api.anthropic.com/v1", client.endpoint.baseUrl)
        assertEquals("claude-render", client.endpoint.model)
        assertEquals(LlmEndpoint.Provider.ANTHROPIC, client.endpoint.provider)
        assertTrue(client is Anthropic)
    }

    @Test
    fun `forExtract with anthropic override uses anthropic`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val cat = cat(CategoryLlmOverrides(extract = LlmOverride("anthropic", "claude-extract")))
        val client = factory.forExtract(cat)
        assertEquals("https://api.anthropic.com/v1", client.endpoint.baseUrl)
        assertEquals("claude-extract", client.endpoint.model)
    }

    @Test
    fun `forBatch with anthropic override returns AnthropicWithBatch`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val cat = cat(CategoryLlmOverrides(batch = LlmOverride("anthropic", "claude-batch-x")))
        val client = factory.forBatch(cat)
        assertEquals("https://api.anthropic.com/v1", client.endpoint.baseUrl)
        assertEquals("claude-batch-x", client.endpoint.model)
        assertTrue(client is AnthropicWithBatch)
    }

    @Test
    fun `forBatch with no override still defaults to openai when anthropic is configured`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val client = factory.forBatch(null)
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-batch-default", client.endpoint.model)
        assertTrue(client is OpenAIWithBatch)
    }

    @Test
    fun `forSummarize with feed provider anthropic uses global anthropic model`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val client = factory.forSummarize(cat(), "anthropic")
        assertEquals("https://api.anthropic.com/v1", client.endpoint.baseUrl)
        assertEquals("claude-default", client.endpoint.model)
        assertTrue(client is Anthropic)
    }

    @Test
    fun `forSummarize per-feed openai wins over anthropic category override`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        // Category says anthropic, feed says openai. Feed wins.
        val cat = cat(CategoryLlmOverrides(summarize = LlmOverride("anthropic", "claude-summary")))
        val client = factory.forSummarize(cat, "openai")
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-default", client.endpoint.model)
        assertTrue(client is OpenAI)
    }

    @Test
    fun `caching anthropic sync and batch variants are separate instances`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val syncCat = cat(CategoryLlmOverrides(render = LlmOverride("anthropic", "claude-shared")))
        val batchCat = cat(CategoryLlmOverrides(batch = LlmOverride("anthropic", "claude-shared")))
        val sync = factory.forRender(syncCat)
        val batch = factory.forBatch(batchCat)
        assertEquals(sync.endpoint.baseUrl, batch.endpoint.baseUrl)
        assertEquals(sync.endpoint.model, batch.endpoint.model)
        assertTrue(sync is Anthropic)
        assertTrue(batch is AnthropicWithBatch)
    }

    @Test
    fun `anthropic sync client throws on submitCategoryBatch`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("anthropic", "claude-x")))
        val client = factory.forRender(cat)
        val ex = assertThrows<UnsupportedOperationException> {
            client.resumeBatch("any")
        }
        assertEquals(true, ex.message?.contains("AnthropicWithBatch"))
    }

    // ── Claude CLI provider ───────────────────────────────────────────────────

    @Test
    fun `forRender with claudecli override returns ClaudeCli on cli endpoint`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("claudecli", "claude-sonnet-4-6")))
        val client = factory.forRender(cat)
        assertEquals("claude-cli", client.endpoint.baseUrl)
        assertEquals("claude-sonnet-4-6", client.endpoint.model)
        assertEquals(LlmEndpoint.Provider.CLAUDE_CLI, client.endpoint.provider)
        assertTrue(client is ClaudeCli)
    }

    @Test
    fun `forRender with claudecli override and blank model keeps cli default model`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true, claudeCliModel = ""), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("claudecli", "")))
        val client = factory.forRender(cat)
        assertEquals("claude-cli", client.endpoint.baseUrl)
        assertEquals("", client.endpoint.model)
        assertTrue(client is ClaudeCli)
    }

    @Test
    fun `forSummarize with feed provider claudecli uses ClaudeCli`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true), db)
        val client = factory.forSummarize(cat(), "claudecli")
        assertEquals("claude-cli", client.endpoint.baseUrl)
        assertEquals("claude-cli-default", client.endpoint.model)
        assertTrue(client is ClaudeCli)
    }

    @Test
    fun `overrideEndpoint with claudecli provider but no claudeCli block throws`() {
        val factory = LlmClientsFactory(config(withClaudeCli = false), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("claudecli", "x")))
        val ex = assertThrows<IllegalStateException> { factory.forRender(cat) }
        assertTrue(ex.message?.contains("claudecli") == true)
    }

    @Test
    fun `claudecli sync client throws on resumeBatch`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("claudecli", "x")))
        val client = factory.forRender(cat)
        val ex = assertThrows<UnsupportedOperationException> { client.resumeBatch("any") }
        assertTrue(ex.message?.contains("Batch API") == true)
    }

    // ── Codex CLI provider ────────────────────────────────────────────────────

    @Test
    fun `forRender with codexcli override returns CodexCli on cli endpoint`() {
        val factory = LlmClientsFactory(config(withCodexCli = true), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("codexcli", "gpt-5-codex")))
        val client = factory.forRender(cat)
        assertEquals("codex-cli", client.endpoint.baseUrl)
        assertEquals("gpt-5-codex", client.endpoint.model)
        assertEquals(LlmEndpoint.Provider.CODEX_CLI, client.endpoint.provider)
        assertTrue(client is CodexCli)
    }

    @Test
    fun `forRender with codexcli override and blank model keeps cli default model`() {
        val factory = LlmClientsFactory(config(withCodexCli = true, codexCliModel = ""), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("codexcli", "")))
        val client = factory.forRender(cat)
        assertEquals("codex-cli", client.endpoint.baseUrl)
        assertEquals("", client.endpoint.model)
        assertTrue(client is CodexCli)
    }

    @Test
    fun `forSummarize with feed provider codexcli uses CodexCli`() {
        val factory = LlmClientsFactory(config(withCodexCli = true), db)
        val client = factory.forSummarize(cat(), "codexcli")
        assertEquals("codex-cli", client.endpoint.baseUrl)
        assertEquals("codex-cli-default", client.endpoint.model)
        assertTrue(client is CodexCli)
    }

    @Test
    fun `overrideEndpoint with codexcli provider but no codexCli block throws`() {
        val factory = LlmClientsFactory(config(withCodexCli = false), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("codexcli", "x")))
        val ex = assertThrows<IllegalStateException> { factory.forRender(cat) }
        assertTrue(ex.message?.contains("codexcli") == true)
    }

    @Test
    fun `codexcli sync client throws on resumeBatch`() {
        val factory = LlmClientsFactory(config(withCodexCli = true), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("codexcli", "x")))
        val client = factory.forRender(cat)
        val ex = assertThrows<UnsupportedOperationException> { client.resumeBatch("any") }
        assertTrue(ex.message?.contains("Batch API") == true)
    }

    // ── Branch coverage: forBatchFallback ─────────────────────────────────────

    @Test
    fun `forBatchFallback returns null when category has no override`() {
        val factory = LlmClientsFactory(config(), db)
        assertNull(factory.forBatchFallback(null))
        assertNull(factory.forBatchFallback(cat()))
    }

    @Test
    fun `forBatchFallback returns sync client matching batchFallback override`() {
        val factory = LlmClientsFactory(config(), db)
        val cat = cat(CategoryLlmOverrides(batchFallback = LlmOverride("openai", "gpt-fb")))
        val client = factory.forBatchFallback(cat)
        assertNotNull(client)
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-fb", client.endpoint.model)
        assertTrue(client is OpenAI)
    }

    // ── Branch coverage: forSummarize per-feed provider branches ──────────────

    @Test
    fun `forSummarize with no override and feed openai uses global openai model`() {
        val factory = LlmClientsFactory(config(), db)
        val client = factory.forSummarize(cat(), "openai")
        assertEquals("https://api.openai.com/v1", client.endpoint.baseUrl)
        assertEquals("gpt-default", client.endpoint.model)
    }

    @Test
    fun `forSummarize with feed openrouter and no openrouter block throws`() {
        val factory = LlmClientsFactory(config(withOpenRouter = false), db)
        assertThrows<IllegalStateException> {
            factory.forSummarize(cat(), "openrouter")
        }
    }

    @Test
    fun `forSummarize with feed anthropic and no anthropic block throws`() {
        val factory = LlmClientsFactory(config(withAnthropic = false), db)
        assertThrows<IllegalStateException> {
            factory.forSummarize(cat(), "anthropic")
        }
    }

    @Test
    fun `forSummarize with unknown feed provider throws`() {
        val factory = LlmClientsFactory(config(), db)
        val ex = assertThrows<IllegalStateException> {
            factory.forSummarize(cat(), "bogus")
        }
        assertTrue(ex.message?.contains("Unknown summarize provider") == true)
    }

    // ── Branch coverage: overrideEndpoint missing-block error paths ───────────

    @Test
    fun `overrideEndpoint with openrouter provider but no openrouter block throws`() {
        val factory = LlmClientsFactory(config(withOpenRouter = false), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("openrouter", "or-x")))
        val ex = assertThrows<IllegalStateException> {
            factory.forRender(cat)
        }
        assertTrue(ex.message?.contains("openrouter") == true)
    }

    @Test
    fun `overrideEndpoint with anthropic provider but no anthropic block throws`() {
        val factory = LlmClientsFactory(config(withAnthropic = false), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("anthropic", "claude-x")))
        val ex = assertThrows<IllegalStateException> {
            factory.forRender(cat)
        }
        assertTrue(ex.message?.contains("anthropic") == true)
    }

    // ── On-failure fallback wiring ────────────────────────────────────────────

    @Test
    fun `forRender with a fallback returns FallbackLlmClient wrapping both legs`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true, withCodexCli = true), db)
        val cat = cat(
            CategoryLlmOverrides(
                render = LlmOverride("claudecli", "claude-x", fallback = LlmOverride("codexcli", "codex-y"))
            )
        )
        val client = factory.forRender(cat)
        assertTrue(client is FallbackLlmClient)
        client as FallbackLlmClient
        assertEquals("claude-cli", client.endpoint.baseUrl) // delegates to primary
        assertTrue(client.primary is ClaudeCli)
        assertEquals("claude-x", client.primary.endpoint.model)
        assertTrue(client.fallback is CodexCli)
        assertEquals("codex-cli", client.fallback.endpoint.baseUrl)
        assertEquals("codex-y", client.fallback.endpoint.model)
    }

    @Test
    fun `forRender default path is not wrapped in FallbackLlmClient`() {
        val factory = LlmClientsFactory(config(), db)
        assertFalse(factory.forRender(null) is FallbackLlmClient)
    }

    @Test
    fun `forRender override without a fallback is not wrapped`() {
        val factory = LlmClientsFactory(config(), db)
        val cat = cat(CategoryLlmOverrides(render = LlmOverride("openai", "gpt-x")))
        val client = factory.forRender(cat)
        assertFalse(client is FallbackLlmClient)
        assertTrue(client is OpenAI)
    }

    @Test
    fun `forExtract wraps the primary leg when extract has a fallback`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true, withCodexCli = true), db)
        val cat = cat(
            CategoryLlmOverrides(
                extract = LlmOverride("claudecli", "claude-x", fallback = LlmOverride("codexcli", "codex-y"))
            )
        )
        assertTrue(factory.forExtract(cat) is FallbackLlmClient)
    }

    @Test
    fun `forExtract wraps each split entry in its own fallback chain`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true, withCodexCli = true), db, splitter = LlmSplitter(null))
        val cat = cat(
            CategoryLlmOverrides(
                extract = split(
                    LlmOverride("openai", "gpt-a", percent = 50),
                    LlmOverride("claudecli", "claude-x", fallback = LlmOverride("codexcli", "codex-y"), percent = 50)
                )
            )
        )
        assertFalse(factory.forExtract(cat) is FallbackLlmClient) // openai entry has no fallback
        assertTrue(factory.forExtract(cat) is FallbackLlmClient)
    }

    @Test
    fun `forExtract does not wrap a batch leg even when a fallback is present`() {
        val factory = LlmClientsFactory(config(withCodexCli = true), db)
        // batch=true ⇒ async Batch API path; the sync fallback can't help, so withFallback skips it.
        // (ConfigLoader also rejects this combo, but the factory guards independently.)
        val cat = cat(
            CategoryLlmOverrides(
                extract = LlmOverride("openai", "gpt-a", batch = true, fallback = LlmOverride("codexcli", "codex-y"))
            )
        )
        val primary = factory.forExtract(cat)
        assertFalse(primary is FallbackLlmClient)
        assertTrue(primary is OpenAIWithBatch)
    }

    @Test
    fun `forSummarize wraps when the matched override carries a fallback`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true, withCodexCli = true), db)
        val cat = cat(
            CategoryLlmOverrides(
                summarize = LlmOverride("claudecli", "claude-x", fallback = LlmOverride("codexcli", "codex-y"))
            )
        )
        assertTrue(factory.forSummarize(cat, "claudecli") is FallbackLlmClient)
    }

    @Test
    fun `forSummarize does not wrap when the per-feed default path is taken`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true, withCodexCli = true), db)
        // Override targets claudecli (with a fallback) but the feed asked for openai → default path.
        val cat = cat(
            CategoryLlmOverrides(
                summarize = LlmOverride("claudecli", "claude-x", fallback = LlmOverride("codexcli", "codex-y"))
            )
        )
        val client = factory.forSummarize(cat, "openai")
        assertFalse(client is FallbackLlmClient)
        assertTrue(client is OpenAI)
    }

    @Test
    fun `forBatchFallback wraps when batchFallback carries a fallback`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true, withCodexCli = true), db)
        val cat = cat(
            CategoryLlmOverrides(
                batchFallback = LlmOverride("claudecli", "claude-x", fallback = LlmOverride("codexcli", "codex-y"))
            )
        )
        val client = factory.forBatchFallback(cat)
        assertNotNull(client)
        assertTrue(client is FallbackLlmClient)
    }

    // ─── OpenRouter `models:` priority ladder on the global default ───

    @Test
    fun `forSummarize openrouter default walks the models ladder in order`() {
        val factory = LlmClientsFactory(config(orModels = listOf("or/m1", "or/m2", "or/m3")), db)
        val client = factory.forSummarize(null, "openrouter")

        assertTrue(client is FallbackLlmClient)
        assertEquals("or/m1", client.primary.endpoint.model)
        val second = client.fallback
        assertTrue(second is FallbackLlmClient)
        assertEquals("or/m2", second.primary.endpoint.model)
        assertEquals("or/m3", second.fallback.endpoint.model)
        assertFalse(second.fallback is FallbackLlmClient)
    }

    @Test
    fun `forSummarize openrouter default with single model builds no chain`() {
        val factory = LlmClientsFactory(config(), db)
        val client = factory.forSummarize(null, "openrouter")
        assertFalse(client is FallbackLlmClient)
        assertEquals("router-default", client.endpoint.model)
    }

    @Test
    fun `forRender default honors the models ladder`() {
        val factory = LlmClientsFactory(config(orModels = listOf("or/m1", "or/m2")), db)
        val client = factory.forRender(null)
        assertTrue(client is FallbackLlmClient)
        assertEquals("or/m1", client.primary.endpoint.model)
        assertEquals("or/m2", client.fallback.endpoint.model)
    }

    @Test
    fun `forExtract default openrouter half honors the models ladder`() {
        val factory = LlmClientsFactory(config(orModels = listOf("or/m1", "or/m2")), db, splitter = LlmSplitter(null))
        assertEquals("gpt-default", factory.forExtract(null).endpoint.model)
        val alternate = factory.forExtract(null)
        assertTrue(alternate is FallbackLlmClient)
        assertEquals("or/m1", alternate.primary.endpoint.model)
    }

    @Test
    fun `category summarize override pins one model and bypasses the ladder`() {
        val factory = LlmClientsFactory(
            config(
                orModels = listOf("or/m1", "or/m2"),
                categories = mapOf("sports" to cat(CategoryLlmOverrides(summarize = LlmOverride("openrouter", "or/pinned"))))
            ),
            db
        )
        val cat = cat(CategoryLlmOverrides(summarize = LlmOverride("openrouter", "or/pinned")))
        val client = factory.forSummarize(cat, "openrouter")
        assertFalse(client is FallbackLlmClient)
        assertEquals("or/pinned", client.endpoint.model)
    }

    @Test
    fun `forShadow builds B only while the shadow is enabled`() {
        val shadow = metifikys.config.ShadowConfig(render = LlmOverride("openrouter", "or/b"))
        val factory = LlmClientsFactory(config(), db)
        assertNotNull(factory.forShadow(cat(CategoryLlmOverrides(shadow = shadow)), LlmUseCase.SHADOW_RENDER))
        assertNull(factory.forShadow(cat(CategoryLlmOverrides(shadow = shadow.copy(enabled = false))), LlmUseCase.SHADOW_RENDER))
    }
}
