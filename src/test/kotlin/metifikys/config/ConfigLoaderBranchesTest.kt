package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigLoaderBranchesTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("config-branch-test", ".yaml")
        file.writeText(yaml.trimIndent())
        return try {
            ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    // ─── ConfigLoader: untested branch coverage ───

    @Test
    fun `load yaml with missing telegram block throws`() {
        val yaml = """
            openai:
              apiKey: "sk-test"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()

        assertThrows<Exception> { load(yaml) }
    }

    @Test
    fun `load yaml with missing openai block throws`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()

        assertThrows<Exception> { load(yaml) }
    }

    @Test
    fun `load yaml with missing database block throws`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()

        assertThrows<Exception> { load(yaml) }
    }

    @Test
    fun `load yaml with missing scheduler block throws`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            database:
              path: "/tmp/test.db"
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()

        assertThrows<Exception> { load(yaml) }
    }

    @Test
    fun `load yaml with no categories throws`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
        """.trimIndent()

        assertThrows<Exception> { load(yaml) }
    }

    @Test
    fun `load yaml with empty channelId throws`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: ""
                feeds:
                  - https://example.com/rss
        """.trimIndent()

        assertThrows<IllegalArgumentException> {
            load(yaml)
        }
    }

    @Test
    fun `load yaml with empty feed entry throws`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - ""
        """.trimIndent()

        assertThrows<Exception> { load(yaml) }
    }

    @Test
    fun `load yaml with category having no feeds throws`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
        """.trimIndent()

        assertThrows<Exception> { load(yaml) }
    }

    @Test
    fun `load yaml with category having null feed list throws`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds: null
        """.trimIndent()

        assertThrows<Exception> { load(yaml) }
    }

    // ─── FeedConfigDeserializer: untested getNullValue branch ───

    @Test
    fun `null feed entry in yaml is rejected`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - null
        """.trimIndent()

        val ex = assertThrows<Exception> { load(yaml) }
        val rootMsg = generateSequence<Throwable>(ex) { it.cause }.last().message ?: ""
        assertTrue(rootMsg.contains("Empty feed entry"))
    }

    // ─── load(): file existence ────────────────────────────────────────────────

    @Test
    fun `load throws when config file does not exist`() {
        val ex = assertThrows<IllegalArgumentException> {
            ConfigLoader.load("/no/such/path/config.yaml")
        }
        assertTrue(ex.message!!.contains("Config file not found"))
    }

    // ─── applyEnvOverrides: provider block validation ──────────────────────────

    @Test
    fun `openrouter block with blank model throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: "or"
              model: ""
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("OpenRouter model"))
    }

    @Test
    fun `openrouter block with blank baseUrl throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: "or"
              model: "m"
              baseUrl: ""
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("OpenRouter baseUrl"))
    }

    @Test
    fun `anthropic block with blank apiKey throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: ""
              model: "claude"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("Anthropic apiKey"))
    }

    @Test
    fun `anthropic block with blank model throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: ""
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("Anthropic model"))
    }

    @Test
    fun `anthropic block with blank batchModel throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: "claude"
              batchModel: ""
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("Anthropic batchModel"))
    }

    @Test
    fun `anthropic block with blank baseUrl throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: "claude"
              baseUrl: ""
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("Anthropic baseUrl"))
    }

    @Test
    fun `anthropic block with blank anthropicVersion throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: "claude"
              anthropicVersion: ""
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("Anthropic anthropicVersion"))
    }

    @Test
    fun `anthropic block with non-positive maxTokens throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: "claude"
              maxTokens: 0
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("Anthropic maxTokens"))
    }

    @Test
    fun `openrouter block with blank apiKey throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: ""
              model: "or-model"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("OpenRouter apiKey"))
    }

    // ─── applyEnvOverrides: processing & database validation ───────────────────

    @Test
    fun `processing primaryMaxPending of zero throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            processing:
              primaryMaxPending: 0
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("primaryMaxPending"))
    }

    @Test
    fun `processing secondaryMaxPending negative throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            processing:
              secondaryMaxPending: -1
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("secondaryMaxPending"))
    }

    @Test
    fun `database path with parent traversal throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/../etc/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("path traversal"))
    }

    // ─── applyEnvOverrides: feed summarize provider validation ─────────────────

    @Test
    fun `feed summarize openrouter without openrouter block throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - url: "https://example.com/rss"
                    summarize: "openrouter"
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("OpenRouter summarization"))
    }

    @Test
    fun `feed summarize anthropic without anthropic block throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - url: "https://example.com/rss"
                    summarize: "anthropic"
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("Anthropic summarization"))
    }

    // ─── validateOverride: per-use-case override validation ────────────────────

    @Test
    fun `llm batch override with openrouter provider throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: "or"
              model: "m"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  batch:
                    provider: "openrouter"
                    model: "or-batch"
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("OpenRouter has no Batch API"))
    }

    @Test
    fun `validateOverride invalid provider name throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  render:
                    provider: "deepseek"
                    model: "v3"
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("provider must be"))
    }

    @Test
    fun `validateOverride blank model throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  render:
                    provider: "openai"
                    model: ""
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("model must not be blank"))
    }

    @Test
    fun `validateOverride openrouter provider without block throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  render:
                    provider: "openrouter"
                    model: "or-x"
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("requires top-level openrouter"))
    }

    @Test
    fun `validateOverride anthropic provider without block throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  render:
                    provider: "anthropic"
                    model: "claude-x"
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("requires top-level anthropic"))
    }

    @Test
    fun `llm extractAlternate without extract throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: "or"
              model: "or-m"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  extractAlternate:
                    provider: "openrouter"
                    model: "or-x"
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("extractAlternate is set but llm.extract is not"))
    }

    @Test
    fun `llm extract and extractAlternate together pass`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: "claude"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  extract:
                    provider: "openai"
                    model: "gpt-a"
                  extractAlternate:
                    provider: "anthropic"
                    model: "claude-b"
        """.trimIndent()
        val cfg = load(yaml)
        val ovr = cfg.categories["sports"]?.llm
        assertNotNull(ovr)
        assertEquals("openai", ovr.extract?.provider)
        assertEquals("gpt-a", ovr.extract?.model)
        assertEquals("anthropic", ovr.extractAlternate?.provider)
        assertEquals("claude-b", ovr.extractAlternate?.model)
    }

    @Test
    fun `valid llm overrides for all five use cases pass`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: "or"
              model: "or-m"
            anthropic:
              apiKey: "ak"
              model: "claude"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  extract:
                    provider: "openai"
                    model: "gpt-x"
                  render:
                    provider: "openrouter"
                    model: "or-x"
                  batch:
                    provider: "anthropic"
                    model: "claude-batch"
                  summarize:
                    provider: "openai"
                    model: "gpt-sum"
                  batchFallback:
                    provider: "openrouter"
                    model: "or-fb"
        """.trimIndent()
        val cfg = load(yaml)
        val ovr = cfg.categories["sports"]?.llm
        assertNotNull(ovr)
        assertEquals("openai", ovr.extract?.provider)
        assertEquals("openrouter", ovr.render?.provider)
        assertEquals("anthropic", ovr.batch?.provider)
        assertEquals("openrouter", ovr.batchFallback?.provider)
    }

    // ─── applyEnvOverrides: env var overrides take effect ──────────────────────

    @Test
    fun `TELEGRAM_BOT_TOKEN env overrides yaml token`() {
        EnvHelper.withEnv("TELEGRAM_BOT_TOKEN", "env-token") {
            val cfg = load(
                """
                telegram:
                  botToken: "yaml-token"
                openai:
                  apiKey: "sk"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """.trimIndent()
            )
            assertEquals("env-token", cfg.telegram.botToken)
        }
    }

    @Test
    fun `OPENAI_API_KEY env overrides yaml api key`() {
        EnvHelper.withEnv("OPENAI_API_KEY", "env-sk") {
            val cfg = load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "yaml-sk"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """.trimIndent()
            )
            assertEquals("env-sk", cfg.openai.apiKey)
        }
    }

    @Test
    fun `OPENROUTER_API_KEY env replaces openrouter block apiKey`() {
        EnvHelper.withEnv("OPENROUTER_API_KEY", "env-or") {
            val cfg = load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "sk"
                openrouter:
                  apiKey: "yaml-or"
                  model: "or-m"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """.trimIndent()
            )
            assertEquals("env-or", cfg.openrouter?.apiKey)
        }
    }

    @Test
    fun `OPENROUTER_API_KEY env without block leaves openrouter null`() {
        EnvHelper.withEnv("OPENROUTER_API_KEY", "env-or") {
            val cfg = load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "sk"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """.trimIndent()
            )
            assertEquals(null, cfg.openrouter)
        }
    }

    @Test
    fun `ANTHROPIC_API_KEY env replaces anthropic block apiKey`() {
        EnvHelper.withEnv("ANTHROPIC_API_KEY", "env-ak") {
            val cfg = load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "sk"
                anthropic:
                  apiKey: "yaml-ak"
                  model: "claude"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """.trimIndent()
            )
            assertEquals("env-ak", cfg.anthropic?.apiKey)
        }
    }

    @Test
    fun `ANTHROPIC_API_KEY env without block leaves anthropic null`() {
        EnvHelper.withEnv("ANTHROPIC_API_KEY", "env-ak") {
            val cfg = load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "sk"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """.trimIndent()
            )
            assertEquals(null, cfg.anthropic)
        }
    }

    // ─── applyEnvOverrides: blank env vars fall back to yaml (takeIf false branch) ─

    @Test
    fun `blank TELEGRAM_BOT_TOKEN env falls back to yaml token`() {
        EnvHelper.withEnv("TELEGRAM_BOT_TOKEN", "") {
            val cfg = load(
                """
                telegram:
                  botToken: "yaml-token"
                openai:
                  apiKey: "sk"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """
            )
            assertEquals("yaml-token", cfg.telegram.botToken)
        }
    }

    @Test
    fun `blank OPENAI_API_KEY env falls back to yaml key`() {
        EnvHelper.withEnv("OPENAI_API_KEY", "") {
            val cfg = load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "yaml-sk"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """
            )
            assertEquals("yaml-sk", cfg.openai.apiKey)
        }
    }

    @Test
    fun `blank OPENROUTER_API_KEY env falls back to openrouter block apiKey`() {
        EnvHelper.withEnv("OPENROUTER_API_KEY", "") {
            val cfg = load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "sk"
                openrouter:
                  apiKey: "yaml-or"
                  model: "or-m"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """
            )
            assertEquals("yaml-or", cfg.openrouter?.apiKey)
        }
    }

    @Test
    fun `blank ANTHROPIC_API_KEY env falls back to anthropic block apiKey`() {
        EnvHelper.withEnv("ANTHROPIC_API_KEY", "") {
            val cfg = load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "sk"
                anthropic:
                  apiKey: "yaml-ak"
                  model: "claude"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """
            )
            assertEquals("yaml-ak", cfg.anthropic?.apiKey)
        }
    }

    // ─── applyEnvOverrides: blank required string fields throw ─────────────────

    @Test
    fun `blank botToken in yaml throws`() {
        val ex = assertThrows<IllegalArgumentException> {
            load(
                """
                telegram:
                  botToken: ""
                openai:
                  apiKey: "sk"
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """
            )
        }
        assertTrue(ex.message!!.contains("botToken"))
    }

    @Test
    fun `blank openai apiKey in yaml throws`() {
        val ex = assertThrows<IllegalArgumentException> {
            load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: ""
                database:
                  path: "/tmp/test.db"
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """
            )
        }
        assertTrue(ex.message!!.contains("apiKey"))
    }

    @Test
    fun `blank database path in yaml throws`() {
        val ex = assertThrows<IllegalArgumentException> {
            load(
                """
                telegram:
                  botToken: "t"
                openai:
                  apiKey: "sk"
                database:
                  path: ""
                scheduler:
                  intervalMinutes: 60
                categories:
                  sports:
                    emoji: "⚽"
                    channelId: "@sports"
                    feeds:
                      - https://example.com/rss
                """
            )
        }
        assertTrue(ex.message!!.contains("Database path"))
    }

    // ─── applyEnvOverrides: feed summarize + provider block present (require true) ──

    @Test
    fun `feed summarize openrouter with openrouter block configured passes`() {
        val cfg = load(
            """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: "or"
              model: "or-m"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - url: "https://example.com/rss"
                    summarize: "openrouter"
            """
        )
        assertEquals("openrouter", cfg.categories["sports"]?.feeds?.first()?.summarize)
    }

    @Test
    fun `feed summarize anthropic with anthropic block configured passes`() {
        val cfg = load(
            """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: "claude"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - url: "https://example.com/rss"
                    summarize: "anthropic"
            """
        )
        assertEquals("anthropic", cfg.categories["sports"]?.feeds?.first()?.summarize)
    }

    private object EnvHelper {
        @Suppress("UNCHECKED_CAST")
        private fun mutate(name: String, value: String?) {
            // Try the JDK 8+ unmodifiable wrapper "m" field first.
            try {
                val env = System.getenv()
                val field = env.javaClass.getDeclaredField("m")
                field.isAccessible = true
                val writable = field.get(env) as MutableMap<String, String>
                if (value == null) writable.remove(name) else writable[name] = value
                return
            } catch (_: NoSuchFieldException) {
            } catch (_: ReflectiveOperationException) {
            } catch (_: RuntimeException) {
            }
            // Windows: ProcessEnvironment.theEnvironment / theCaseInsensitiveEnvironment
            try {
                val pe = Class.forName("java.lang.ProcessEnvironment")
                val theEnv = pe.getDeclaredField("theEnvironment").apply { isAccessible = true }
                    .get(null) as MutableMap<String, String>
                val theCi = pe.getDeclaredField("theCaseInsensitiveEnvironment").apply { isAccessible = true }
                    .get(null) as MutableMap<String, String>
                if (value == null) {
                    theEnv.remove(name); theCi.remove(name)
                } else {
                    theEnv[name] = value; theCi[name] = value
                }
            } catch (_: Throwable) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Cannot mutate process env on this JDK")
            }
        }

        fun withEnv(name: String, value: String, block: () -> Unit) {
            val original = System.getenv(name)
            mutate(name, value)
            try {
                block()
            } finally {
                mutate(name, original)
            }
        }
    }

    // ─── applyEnvOverrides: extract.batch validation ────────────────────────────

    @Test
    fun `extract batch=true with openrouter provider throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: "or"
              model: "or-m"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  extract:
                    provider: "openrouter"
                    model: "or-x"
                    batch: true
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("batch=true is not supported for provider 'openrouter'"))
    }

    @Test
    fun `extractAlternate batch=true with openrouter provider throws`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            openrouter:
              apiKey: "or"
              model: "or-m"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  extract:
                    provider: "openai"
                    model: "gpt-a"
                  extractAlternate:
                    provider: "openrouter"
                    model: "or-x"
                    batch: true
        """.trimIndent()
        val ex = assertThrows<IllegalArgumentException> { load(yaml) }
        assertTrue(ex.message!!.contains("batch=true is not supported for provider 'openrouter'"))
    }

    @Test
    fun `mixed mode extract batch=true extractAlternate batch=false passes`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: "claude"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  extract:
                    provider: "anthropic"
                    model: "claude-batch"
                    batch: true
                  extractAlternate:
                    provider: "openai"
                    model: "gpt-sync"
        """.trimIndent()
        val cfg = load(yaml)
        val ovr = cfg.categories["sports"]?.llm
        assertNotNull(ovr)
        assertEquals(true, ovr.extract?.batch)
        assertEquals(false, ovr.extractAlternate?.batch)
    }

    @Test
    fun `both extract and extractAlternate batch=true with anthropic passes`() {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            anthropic:
              apiKey: "ak"
              model: "claude"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              sports:
                emoji: "⚽"
                channelId: "@sports"
                feeds:
                  - https://example.com/rss
                llm:
                  extract:
                    provider: "anthropic"
                    model: "claude-a"
                    batch: true
                  extractAlternate:
                    provider: "anthropic"
                    model: "claude-b"
                    batch: true
        """.trimIndent()
        val cfg = load(yaml)
        val ovr = cfg.categories["sports"]?.llm
        assertNotNull(ovr)
        assertEquals(true, ovr.extract?.batch)
        assertEquals(true, ovr.extractAlternate?.batch)
    }

}
