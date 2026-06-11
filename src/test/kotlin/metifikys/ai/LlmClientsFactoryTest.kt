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
        withAnthropic: Boolean = false,
        withClaudeCli: Boolean = false,
        claudeCliModel: String = "claude-cli-default",
        withCodexCli: Boolean = false,
        codexCliModel: String = "codex-cli-default",
        categories: Map<String, CategoryConfig> = emptyMap()
    ) = AppConfig(
        telegram = TelegramConfig(botToken = "tok"),
        openai = OpenAIConfig(apiKey = "sk-test", model = "gpt-default", batchModel = "gpt-batch-default"),
        openrouter = if (withOpenRouter) OpenRouterConfig(apiKey = "or", model = "router-default") else null,
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
    fun `forExtract with no override returns primary openai + alternate openrouter`() {
        val factory = LlmClientsFactory(config(withOpenRouter = true), db)
        val (primary, alternate) = factory.forExtract(null)
        assertEquals("https://api.openai.com/v1", primary.endpoint.baseUrl)
        assertEquals("gpt-default", primary.endpoint.model)
        assertNotNull(alternate)
        assertEquals("https://openrouter.ai/api/v1", alternate.endpoint.baseUrl)
        assertEquals("router-default", alternate.endpoint.model)
    }

    @Test
    fun `forExtract with no openrouter returns null alternate`() {
        val factory = LlmClientsFactory(config(withOpenRouter = false), db)
        val (primary, alternate) = factory.forExtract(null)
        assertEquals("https://api.openai.com/v1", primary.endpoint.baseUrl)
        assertNull(alternate)
    }

    @Test
    fun `forExtract with override suppresses alternation`() {
        val factory = LlmClientsFactory(config(), db)
        val cat = cat(CategoryLlmOverrides(extract = LlmOverride("openrouter", "alt-model")))
        val (primary, alternate) = factory.forExtract(cat)
        assertEquals("https://openrouter.ai/api/v1", primary.endpoint.baseUrl)
        assertEquals("alt-model", primary.endpoint.model)
        assertNull(alternate)
    }

    @Test
    fun `forExtract with extract and extractAlternate returns both clients`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val cat = cat(
            CategoryLlmOverrides(
                extract = LlmOverride("openai", "gpt-a"),
                extractAlternate = LlmOverride("anthropic", "claude-b")
            )
        )
        val (primary, alternate) = factory.forExtract(cat)
        assertEquals("https://api.openai.com/v1", primary.endpoint.baseUrl)
        assertEquals("gpt-a", primary.endpoint.model)
        assertNotNull(alternate)
        assertEquals("https://api.anthropic.com/v1", alternate.endpoint.baseUrl)
        assertEquals("claude-b", alternate.endpoint.model)
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
    fun `forExtract with anthropic override suppresses alternation`() {
        val factory = LlmClientsFactory(config(withAnthropic = true), db)
        val cat = cat(CategoryLlmOverrides(extract = LlmOverride("anthropic", "claude-extract")))
        val (primary, alternate) = factory.forExtract(cat)
        assertEquals("https://api.anthropic.com/v1", primary.endpoint.baseUrl)
        assertEquals("claude-extract", primary.endpoint.model)
        assertNull(alternate)
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
        val (primary, alternate) = factory.forExtract(cat)
        assertTrue(primary is FallbackLlmClient)
        assertNull(alternate)
    }

    @Test
    fun `forExtract wraps the alternate leg independently of the primary`() {
        val factory = LlmClientsFactory(config(withClaudeCli = true, withCodexCli = true), db)
        val cat = cat(
            CategoryLlmOverrides(
                extract = LlmOverride("openai", "gpt-a"),
                extractAlternate = LlmOverride("claudecli", "claude-x", fallback = LlmOverride("codexcli", "codex-y"))
            )
        )
        val (primary, alternate) = factory.forExtract(cat)
        assertFalse(primary is FallbackLlmClient) // extract leg has no fallback
        assertNotNull(alternate)
        assertTrue(alternate is FallbackLlmClient)
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
        val (primary, _) = factory.forExtract(cat)
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
}
