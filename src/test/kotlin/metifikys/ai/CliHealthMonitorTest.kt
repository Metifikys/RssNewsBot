package metifikys.ai

import metifikys.config.AdminConfig
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.ClaudeCliConfig
import metifikys.config.CliProbeConfig
import metifikys.config.CodexCliConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.HealthConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.BotStateStore
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliHealthMonitorTest {

    private class MemStore : BotStateStore {
        val map = mutableMapOf<String, String>()
        override fun getState(key: String) = map[key]
        override fun setState(key: String, value: String) { map[key] = value }
        override fun deleteState(key: String) = map.remove(key) != null
    }

    private var now: Instant = Instant.parse("2026-09-23T03:10:00Z")
    private val alerts = mutableListOf<Pair<String, Boolean>>()
    private val probes = mutableListOf<String>()
    private val store = MemStore()

    private fun config(
        health: HealthConfig = HealthConfig(enabled = true),
        claude: ClaudeCliConfig? = ClaudeCliConfig(),
        codex: CodexCliConfig? = CodexCliConfig()
    ) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        claudeCli = claude,
        codexCli = codex,
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("tech" to CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://e.com/rss")), channelId = "@t")),
        admin = AdminConfig(statusChatId = "42"),
        health = health
    )

    private fun monitor(
        cfg: AppConfig = config(),
        probe: ((String) -> Unit)? = { probes += it }
    ) = CliHealthMonitor(cfg, store, alert = { t, s -> alerts += t to s }, probeRunner = probe, clock = { now }, zone = ZoneOffset.UTC)

    @Test
    fun `alerts only on transitions and announces recovery once`() {
        val m = monitor()
        m.report("claude", CliState.OK, null)
        assertEquals(emptyList(), alerts)

        m.report("claude", CliState.AUTH, "OAuth token has expired · Please run /login")
        m.report("claude", CliState.AUTH, "again")
        assertEquals(1, alerts.size)
        assertTrue("⛔ claude: логін протух (з 03:10)" in alerts[0].first, alerts[0].first)
        assertTrue("claude setup-token" in alerts[0].first)
        assertEquals(false, alerts[0].second)

        now += Duration.ofHours(1)
        m.report("claude", CliState.OK, null)
        m.report("claude", CliState.OK, null)
        assertEquals(2, alerts.size)
        assertTrue(alerts[1].first.startsWith("✅ claude знову працює"), alerts[1].first)
    }

    @Test
    fun `timeouts and errors are not alerted, and do not swallow the recovery message`() {
        val m = monitor()
        m.report("codex", CliState.AUTH, "Not logged in")
        m.report("codex", CliState.TIMEOUT, "timed out")
        m.report("codex", CliState.ERROR, "boom")
        assertEquals(1, alerts.size)
        m.report("codex", CliState.OK, null)
        assertEquals(2, alerts.size)
        assertTrue(alerts[1].first.startsWith("✅ codex"))
    }

    @Test
    fun `limit is a silent notice with the reset hint`() {
        val m = monitor()
        m.report("codex", CliState.LIMIT, "You've hit your usage limit. Try again in 3 hours.")
        assertEquals(1, alerts.size)
        assertTrue("⏳ codex: ліміт підписки (Try again in 3 hours)" in alerts[0].first, alerts[0].first)
        assertEquals(true, alerts[0].second)
        m.report("codex", CliState.OK, null)
        assertEquals(true, alerts[1].second, "recovery from a limit is silent too")
    }

    @Test
    fun `state survives a restart - no repeated alert`() {
        monitor().report("claude", CliState.AUTH, "expired")
        monitor().report("claude", CliState.AUTH, "expired")
        assertEquals(1, alerts.size)
    }

    @Test
    fun `a recent real success skips the probe, a stale one probes once per interval`() {
        val m = monitor()
        m.report("claude", CliState.OK, null)
        m.report("codex", CliState.OK, null)
        now += Duration.ofHours(2)
        m.maintenanceTick()
        assertEquals(emptyList(), probes)

        now += Duration.ofHours(5) // 7h since the last success
        m.maintenanceTick()
        assertEquals(listOf("claude", "codex"), probes)
        now += Duration.ofHours(1)
        m.maintenanceTick()
        assertEquals(2, probes.size, "probed again within the interval")
    }

    @Test
    fun `failing CLIs are probed every interval so a fixed login is noticed`() {
        val m = monitor(config(codex = null))
        m.report("claude", CliState.AUTH, "expired")
        m.maintenanceTick()
        now += Duration.ofHours(6)
        m.maintenanceTick()
        assertEquals(listOf("claude", "claude"), probes)
    }

    @Test
    fun `probe off - only the token check runs`() {
        val m = monitor(config(health = HealthConfig(enabled = true, cliProbe = CliProbeConfig(enabled = false))))
        m.maintenanceTick()
        assertEquals(emptyList(), probes)
    }

    @Test
    fun `setup-token expiry warns once a day inside the window`() {
        val cfg = config(health = HealthConfig(enabled = true, claudeTokenExpires = "2026-10-03", warnDaysBefore = 14, cliProbe = CliProbeConfig(enabled = false)))
        val m = monitor(cfg)
        m.maintenanceTick()
        m.maintenanceTick()
        assertEquals(1, alerts.size)
        assertTrue("спливає 2026-10-03 (через 10 дн.)" in alerts[0].first, alerts[0].first)
        now += Duration.ofDays(1)
        m.maintenanceTick()
        assertEquals(2, alerts.size)
    }

    @Test
    fun `setup-token far from expiry is quiet`() {
        val cfg = config(health = HealthConfig(enabled = true, claudeTokenExpires = "2027-08-01", cliProbe = CliProbeConfig(enabled = false)))
        monitor(cfg).maintenanceTick()
        assertEquals(emptyList(), alerts)
    }

    @Test
    fun `status line`() {
        val m = monitor()
        assertEquals("CLI: claude ? · codex ?", m.statusLine())
        m.report("claude", CliState.OK, null)
        m.report("codex", CliState.AUTH, "x")
        now += Duration.ofHours(2)
        assertEquals("CLI: claude ✅ 2h · codex ⛔ auth з 03:10", m.statusLine())
    }

    // ── the real probe against fake CLIs ──

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private fun shim(winBody: String, shBody: String): String {
        val dir = Files.createTempDirectory("clihealth-test").toFile()
        return if (isWindows) {
            File(dir, "shim.cmd").also { it.writeText("@echo off\r\n$winBody\r\n") }.absolutePath
        } else {
            File(dir, "shim.sh").also { it.writeText("#!/bin/sh\n$shBody\n"); it.setExecutable(true) }.absolutePath
        }
    }

    @Test
    fun `claude probe classifies an expired login and alerts`() {
        val cmd = shim(
            winBody = "echo OAuth token has expired. Please run /login\r\nexit /b 1",
            shBody = "echo 'OAuth token has expired. Please run /login'\nexit 1"
        )
        val m = monitor(config(claude = ClaudeCliConfig(command = cmd), codex = null), probe = null)
        m.maintenanceTick()
        assertEquals(CliState.AUTH, m.load("claude")?.state)
        assertEquals(1, alerts.size)
        assertTrue(m.load("claude")?.lastProbe != null)
    }

    @Test
    fun `claude probe success is ok`() {
        val cmd = shim(winBody = "echo pong", shBody = "echo pong")
        val m = monitor(config(claude = ClaudeCliConfig(command = cmd), codex = null), probe = null)
        m.maintenanceTick()
        assertEquals(CliState.OK, m.load("claude")?.state)
        assertEquals(emptyList(), alerts)
    }

    @Test
    fun `codex login status - logged out settles as auth without a model call`() {
        val marker = Files.createTempFile("codex-exec", ".txt").toFile().also { it.delete() }
        val cmd = shim(
            winBody = "if \"%1\"==\"login\" (\r\n  echo Not logged in\r\n  exit /b 1\r\n)\r\necho called> \"${marker.absolutePath}\"\r\necho pong",
            shBody = "if [ \"$1\" = login ]; then echo 'Not logged in'; exit 1; fi\necho called > '${marker.absolutePath}'\necho pong"
        )
        val m = monitor(config(claude = null, codex = CodexCliConfig(command = cmd)), probe = null)
        m.maintenanceTick()
        assertEquals(CliState.AUTH, m.load("codex")?.state)
        assertTrue(!marker.exists(), "exec should not have run")
    }

    @Test
    fun `codex without a login status subcommand falls back to exec`() {
        val cmd = shim(
            winBody = "if \"%1\"==\"login\" (\r\n  echo error: unrecognized subcommand 'status'\r\n  exit /b 2\r\n)\r\necho pong",
            shBody = "if [ \"$1\" = login ]; then echo \"error: unrecognized subcommand 'status'\"; exit 2; fi\necho pong"
        )
        val m = monitor(config(claude = null, codex = CodexCliConfig(command = cmd)), probe = null)
        m.maintenanceTick()
        assertEquals(CliState.OK, m.load("codex")?.state)
    }
}
