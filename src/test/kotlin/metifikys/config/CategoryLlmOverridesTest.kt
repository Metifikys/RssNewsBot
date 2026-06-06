package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CategoryLlmOverridesTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("config-llm-test", ".yaml")
        file.writeText(yaml.trimIndent())
        try {
            return ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parses full llm overrides block`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            openrouter:
              apiKey: "or-key"
              model: "default-router-model"
            categories:
              gaming:
                emoji: "🎮"
                channelId: "@gaming"
                feeds:
                  - https://example.com/feed
                llm:
                  extract:   { provider: openrouter, model: inclusionai/foo }
                  render:    { provider: openrouter, model: x-ai/grok-4 }
                  batch:     { provider: openai, model: gpt-5.4-2026-03-05 }
                  summarize: { provider: openai, model: gpt-5-mini }
        """)

        val ovr = config.categories["gaming"]?.llm
        assertEquals("openrouter", ovr?.extract?.provider)
        assertEquals("inclusionai/foo", ovr?.extract?.model)
        assertEquals("openrouter", ovr?.render?.provider)
        assertEquals("x-ai/grok-4", ovr?.render?.model)
        assertEquals("openai", ovr?.batch?.provider)
        assertEquals("gpt-5.4-2026-03-05", ovr?.batch?.model)
        assertEquals("openai", ovr?.summarize?.provider)
        assertEquals("gpt-5-mini", ovr?.summarize?.model)
    }

    @Test
    fun `omitted llm block leaves category llm null`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech"
                feeds:
                  - https://example.com/feed
        """)
        assertNull(config.categories["tech"]?.llm)
    }

    @Test
    fun `partial overrides leave other use-cases null`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech"
                feeds:
                  - https://example.com/feed
                llm:
                  extract: { provider: openai, model: m1 }
        """)
        val ovr = config.categories["tech"]?.llm
        assertEquals("m1", ovr?.extract?.model)
        assertNull(ovr?.render)
        assertNull(ovr?.batch)
        assertNull(ovr?.summarize)
    }

    @Test
    fun `unknown provider rejected with clear message`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      extract: { provider: foo, model: bar }
            """)
        }
        assertEquals(true, ex.message?.contains("must be 'openai', 'openrouter', 'anthropic', 'claudecli', or 'codexcli'"))
    }

    @Test
    fun `blank model rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      render: { provider: openai, model: "" }
            """)
        }
        assertEquals(true, ex.message?.contains("must not be blank"))
    }

    @Test
    fun `openrouter override without top-level block rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      render: { provider: openrouter, model: foo }
            """)
        }
        assertEquals(true, ex.message?.contains("requires top-level openrouter: block"))
    }

    @Test
    fun `batch override with openrouter provider rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                openrouter:
                  apiKey: "or-key"
                  model: "default-model"
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      batch: { provider: openrouter, model: foo }
            """)
        }
        assertEquals(true, ex.message?.contains("batch override only supports provider: openai or anthropic"))
    }

    // ── Codex CLI provider ──────────────────────────────────────────────────

    @Test
    fun `codexcli override accepted with block present and blank model allowed`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            codexCli:
              command: "codex"
              model: ""
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech"
                feeds:
                  - https://example.com/feed
                llm:
                  render:    { provider: codexcli, model: "" }
                  summarize: { provider: codexcli, model: gpt-5-codex }
        """)
        val ovr = config.categories["tech"]?.llm
        assertEquals("codexcli", ovr?.render?.provider)
        assertEquals("", ovr?.render?.model)
        assertEquals("codexcli", ovr?.summarize?.provider)
        assertEquals("gpt-5-codex", ovr?.summarize?.model)
    }

    @Test
    fun `codexcli override without top-level block rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      render: { provider: codexcli, model: "" }
            """)
        }
        assertEquals(true, ex.message?.contains("requires top-level codexCli: block"))
    }

    @Test
    fun `codexcli extract with batch true rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                codexCli:
                  command: "codex"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      extract: { provider: codexcli, model: "", batch: true }
            """)
        }
        assertEquals(true, ex.message?.contains("batch=true is not supported for provider 'codexcli'"))
    }

    // ── Anthropic provider ────────────────────────────────────────────────────

    @Test
    fun `anthropic override accepted when anthropic block present`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            anthropic:
              apiKey: "ant-key"
              model: "claude-default"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech"
                feeds:
                  - https://example.com/feed
                llm:
                  extract:   { provider: anthropic, model: claude-extract }
                  render:    { provider: anthropic, model: claude-render }
                  batch:     { provider: anthropic, model: claude-batch }
                  summarize: { provider: anthropic, model: claude-summary }
        """)
        val ovr = config.categories["tech"]?.llm
        assertEquals("anthropic", ovr?.extract?.provider)
        assertEquals("claude-extract", ovr?.extract?.model)
        assertEquals("anthropic", ovr?.render?.provider)
        assertEquals("anthropic", ovr?.batch?.provider)
        assertEquals("claude-batch", ovr?.batch?.model)
        assertEquals("anthropic", ovr?.summarize?.provider)
    }

    @Test
    fun `anthropic override without top-level block rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      render: { provider: anthropic, model: claude-x }
            """)
        }
        assertEquals(true, ex.message?.contains("requires top-level anthropic: block"))
    }

    @Test
    fun `feed summarize anthropic requires anthropic block`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - url: https://example.com/feed
                        summarize: anthropic
            """)
        }
        assertEquals(true, ex.message?.contains("requested Anthropic summarization"))
    }

    // ── batchFallback override ───────────────────────────────────────────────

    @Test
    fun `batchFallback parses with anthropic provider`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            anthropic:
              apiKey: "ant-key"
              model: "claude-default"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech"
                feeds:
                  - https://example.com/feed
                llm:
                  batchFallback: { provider: anthropic, model: claude-haiku }
        """)
        val ovr = config.categories["tech"]?.llm
        assertEquals("anthropic", ovr?.batchFallback?.provider)
        assertEquals("claude-haiku", ovr?.batchFallback?.model)
    }

    @Test
    fun `batchFallback with openrouter provider accepted (sync path, unlike batch)`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            openrouter:
              apiKey: "or-key"
              model: "default-router-model"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech"
                feeds:
                  - https://example.com/feed
                llm:
                  batchFallback: { provider: openrouter, model: x-ai/grok-4 }
        """)
        val ovr = config.categories["tech"]?.llm
        assertEquals("openrouter", ovr?.batchFallback?.provider)
        assertEquals("x-ai/grok-4", ovr?.batchFallback?.model)
    }

    @Test
    fun `batchFallback with unknown provider rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      batchFallback: { provider: foo, model: bar }
            """)
        }
        assertEquals(true, ex.message?.contains("must be 'openai', 'openrouter', 'anthropic', 'claudecli', or 'codexcli'"))
    }

    @Test
    fun `batchFallback with anthropic provider but no anthropic block rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    feeds:
                      - https://example.com/feed
                    llm:
                      batchFallback: { provider: anthropic, model: claude-x }
            """)
        }
        assertEquals(true, ex.message?.contains("requires top-level anthropic: block"))
    }

    @Test
    fun `anthropic block parses defaults`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            anthropic:
              apiKey: "ant-key"
              model: "claude-x"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories: {}
        """)
        val a = config.anthropic!!
        assertEquals("ant-key", a.apiKey)
        assertEquals("claude-x", a.model)
        assertEquals("claude-x", a.batchModel)  // defaults to model when omitted
        assertEquals("https://api.anthropic.com/v1", a.baseUrl)
        assertEquals("2023-06-01", a.anthropicVersion)
    }

    // ── skipBatch ──────────────────────────────────────────────────────────────

    @Test
    fun `skipBatch parses and defaults to false`() {
        val config = load("""
            telegram:
              botToken: "tok"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              live:
                emoji: "⚡"
                channelId: "@live"
                skipBatch: true
                feeds:
                  - https://example.com/feed
              normal:
                emoji: "📰"
                channelId: "@normal"
                feeds:
                  - https://example.com/feed2
        """)
        assertEquals(true, config.categories["live"]?.skipBatch)
        assertEquals(false, config.categories["normal"]?.skipBatch)
    }

    @Test
    fun `skipBatch combined with llm batch is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    skipBatch: true
                    feeds:
                      - https://example.com/feed
                    llm:
                      batch: { provider: openai, model: gpt-batch }
            """)
        }
        assertEquals(true, ex.message?.contains("skipBatch"))
    }

    @Test
    fun `skipBatch combined with extract batch true is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            load("""
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  tech:
                    emoji: "💻"
                    channelId: "@tech"
                    skipBatch: true
                    feeds:
                      - https://example.com/feed
                    llm:
                      extract: { provider: openai, model: gpt-x, batch: true }
            """)
        }
        assertEquals(true, ex.message?.contains("skipBatch"))
    }
}
