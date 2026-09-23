package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.BotStateStore
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/** Outcome of one CLI run, as the operator sees it. */
enum class CliState { OK, AUTH, LIMIT, MODEL, TIMEOUT, ERROR }

/** Where [ClaudeCli] / [CodexCli] report every real run's outcome (provider = `claude` | `codex`). */
fun interface CliOutcomeSink {
    fun report(provider: String, state: CliState, detail: String?)
}

/**
 * Tracks whether the `claude` / `codex` CLIs on the server still work, and says so in the admin
 * chat (`health.enabled`). Two inputs:
 *  - passive: every real CLI run reports through [report] — an expired login in live traffic
 *    alerts at once, even when a `fallback` provider saved the call;
 *  - active: [maintenanceTick] probes a CLI with no successful real run in
 *    `health.cliProbe.intervalHours` (a busy bot never probes).
 *
 * Alerts fire on transitions only: entering AUTH / LIMIT / MODEL, and back to OK from the state
 * last alerted. TIMEOUT and ERROR are recorded (and shown in `/status`) but not alerted — they
 * come from oversized prompts and flaky upstreams, not from the login. State lives in
 * `bot_state["cli_health:<provider>"]`, so a restart neither forgets nor repeats an alert.
 */
class CliHealthMonitor(
    private val config: AppConfig,
    private val store: BotStateStore,
    /** Sends an admin-chat message; `silent` for information rather than alarm. */
    private val alert: (text: String, silent: Boolean) -> Unit,
    /** Runs one probe of `claude` / `codex`, reporting its outcome here; default = the real CLIs. */
    probeRunner: ((provider: String) -> Unit)? = null,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault()
) : CliOutcomeSink {

    private val health = config.health
    private val probeRunner: (String) -> Unit = probeRunner ?: ::runRealProbe

    /** CLIs configured on this instance. */
    val providers: List<String> = listOfNotNull(
        CLAUDE.takeIf { config.claudeCli != null },
        CODEX.takeIf { config.codexCli != null }
    )

    /** Persisted per-provider state. */
    internal data class Record(
        val state: CliState,
        val since: Instant,
        /** The state last alerted (AUTH / LIMIT / MODEL), null once recovery was announced. */
        val alerted: CliState? = null,
        val lastOk: Instant? = null,
        val lastProbe: Instant? = null
    )

    @Synchronized
    override fun report(provider: String, state: CliState, detail: String?) {
        val now = clock()
        val prev = load(provider)
        var rec = when {
            prev == null -> Record(state, now)
            prev.state != state -> prev.copy(state = state, since = now)
            else -> prev
        }
        if (state == CliState.OK) rec = rec.copy(lastOk = now)

        var message: Pair<String, Boolean>? = null
        if (state in ALERTING && rec.alerted != state) {
            message = alertText(provider, state, rec.since, detail)
            rec = rec.copy(alerted = state)
        } else if (state == CliState.OK && rec.alerted != null) {
            message = "✅ $provider знову працює (після: ${rec.alerted!!.name.lowercase()})." to (rec.alerted == CliState.LIMIT)
            rec = rec.copy(alerted = null)
        }

        if (prev?.state != state) {
            val line = "[CliHealth] $provider: ${prev?.state ?: "?"} → $state" + (detail?.let { " — ${it.take(300)}" } ?: "")
            if (state == CliState.AUTH || state == CliState.MODEL) logger.warn { line } else logger.info { line }
        }
        // A success only moves lastOk: persist that at most every few minutes, not per call.
        val lastOkStale = prev?.lastOk == null || Duration.between(prev.lastOk, now) > PERSIST_OK_EVERY
        if (rec != prev && (prev == null || prev.state != rec.state || prev.alerted != rec.alerted || lastOkStale)) save(provider, rec)
        message?.let { (text, silent) -> sendAlert(text, silent) }
    }

    /**
     * Maintenance tick: the setup-token expiry warning, then a probe for each CLI whose last
     * successful run is older than the interval (and that was not probed within it either).
     */
    fun maintenanceTick() {
        try {
            checkTokenExpiry()
        } catch (e: Exception) {
            logger.warn(e) { "[CliHealth] token expiry check failed" }
        }
        if (!health.cliProbe.enabled) return
        val interval = Duration.ofHours(health.cliProbe.intervalHours)
        for (p in providers) {
            val now = clock()
            val rec = load(p)
            val okRecently = rec?.state == CliState.OK && rec.lastOk != null && Duration.between(rec.lastOk, now) < interval
            val probedRecently = rec?.lastProbe != null && Duration.between(rec.lastProbe, now) < interval
            if (okRecently || probedRecently) continue
            logger.info { "[CliHealth] probing $p (last ok: ${rec?.lastOk ?: "never"})" }
            try {
                probeRunner(p)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                logger.warn(e) { "[CliHealth] probe of $p failed to run" }
            }
            synchronized(this) {
                val after = load(p) ?: Record(CliState.ERROR, clock())
                save(p, after.copy(lastProbe = clock()))
            }
        }
    }

    /** `/status` line, e.g. `CLI: claude ✅ 2h · codex ⛔ auth з 03:10`; null when no CLI is configured. */
    fun statusLine(): String? {
        if (providers.isEmpty()) return null
        val now = clock()
        val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        return "CLI: " + providers.joinToString(" · ") { p ->
            val r = load(p)
            when {
                r == null -> "$p ?"
                r.state == CliState.OK -> "$p ✅ " + (r.lastOk?.let { age(Duration.between(it, now)) } ?: "")
                else -> "$p ${EMOJI.getValue(r.state)} ${r.state.name.lowercase()} з ${hhmm.format(r.since)}"
            }.trim()
        }
    }

    private fun checkTokenExpiry() {
        val raw = health.claudeTokenExpires?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val expires = LocalDate.parse(raw)
        val today = LocalDate.ofInstant(clock(), zone)
        val daysLeft = ChronoUnit.DAYS.between(today, expires)
        if (daysLeft > health.warnDaysBefore) return
        if (store.getState(TOKEN_WARNED_KEY) == today.toString()) return
        store.setState(TOKEN_WARNED_KEY, today.toString())
        val whenText = if (daysLeft >= 0) "спливає $expires (через $daysLeft дн.)" else "сплив $expires (${-daysLeft} дн. тому)"
        sendAlert(
            "🔑 claude: токен `claude setup-token` $whenText. На сервері від користувача бота: " +
                "`claude setup-token`, потім онови health.claudeTokenExpires.",
            silent = false
        )
    }

    private fun alertText(provider: String, state: CliState, since: Instant, detail: String?): Pair<String, Boolean> {
        val at = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(since)
        return when (state) {
            CliState.AUTH -> {
                val fix = if (provider == CLAUDE) "`claude` → /login або `claude setup-token`" else "`codex login`"
                "⛔ $provider: логін протух (з $at). На сервері від користувача бота: $fix." to false
            }
            CliState.LIMIT -> {
                val reset = detail?.let { CliErrorClassifier.resetHint(it) }?.let { " ($it)" } ?: ""
                "⏳ $provider: ліміт підписки$reset, з $at." to true
            }
            else -> "⚠️ $provider: модель недоступна (з $at): ${detail.orEmpty().lineSequence().firstOrNull().orEmpty().take(200)}" to false
        }
    }

    private fun sendAlert(text: String, silent: Boolean) {
        try {
            alert(text, silent)
        } catch (e: Exception) {
            logger.warn(e) { "[CliHealth] alert failed: $text" }
        }
    }

    // ── the real probe ──────────────────────────────────────────────────────

    private fun runRealProbe(provider: String) {
        val probe = health.cliProbe
        when (provider) {
            CLAUDE -> {
                val cli = config.claudeCli ?: return
                val ep = LlmEndpoint.forClaudeCli(config)!!.let { if (probe.claudeModel.isBlank()) it else it.copy(model = probe.claudeModel) }
                runPing { ClaudeCli(ep, cli.command, probe.timeoutSeconds, maxRetries = 0, health = this).complete(PROBE_SYSTEM, PROBE_USER) }
            }
            CODEX -> {
                val cli = config.codexCli ?: return
                if (codexLoginStatusSettles(cli.command)) return
                val ep = LlmEndpoint.forCodexCli(config)!!.let { if (probe.codexModel.isBlank()) it else it.copy(model = probe.codexModel) }
                runPing { CodexCli(ep, cli.command, probe.timeoutSeconds, maxRetries = 0, health = this).complete(PROBE_SYSTEM, PROBE_USER) }
            }
        }
    }

    /** The CLI client reports the outcome itself; failures only need to be swallowed here. */
    private fun runPing(call: () -> String) {
        try {
            call()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            logger.info { "[CliHealth] probe failed: ${e.message?.lineSequence()?.firstOrNull()?.take(300)}" }
        }
    }

    /**
     * `codex login status` — a login check without a model call, when the installed codex has
     * it. A logged-out answer settles the probe as AUTH. Logged in settles it as OK unless the
     * last known state is LIMIT / MODEL, which only a real call can clear. Anything else (no
     * such subcommand in this version, the binary missing, a hang) falls through to `exec`.
     */
    private fun codexLoginStatusSettles(command: String): Boolean {
        val (exit, output) = runQuick(listOf(command, "login", "status"), 20) ?: return false
        val s = output.lowercase()
        val loggedOut = "not logged in" in s || "logged out" in s || "no credentials" in s ||
            ("login" in s && "expired" in s)
        return when {
            loggedOut -> {
                report(CODEX, CliState.AUTH, "codex login status: ${output.trim().take(300)}")
                true
            }
            exit == 0 && load(CODEX)?.state !in setOf(CliState.LIMIT, CliState.MODEL) -> {
                report(CODEX, CliState.OK, null)
                true
            }
            else -> false
        }
    }

    /** (exit code, combined output) of a short command, or null if it could not run in time. */
    private fun runQuick(args: List<String>, timeoutSeconds: Long): Pair<Int, String>? {
        val process = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return null
        }
        process.outputStream.close()
        val out = StringBuilder()
        val drain = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { out.appendLine(it) }
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        drain.join(1000)
        return process.exitValue() to out.toString()
    }

    // ── persistence ─────────────────────────────────────────────────────────

    internal fun load(provider: String): Record? {
        val raw = try {
            store.getState(KEY_PREFIX + provider)
        } catch (e: Exception) {
            null
        } ?: return null
        val parts = raw.split('|')
        return try {
            Record(
                state = CliState.valueOf(parts[0]),
                since = Instant.ofEpochMilli(parts[1].toLong()),
                alerted = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { CliState.valueOf(it) },
                lastOk = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { Instant.ofEpochMilli(it.toLong()) },
                lastProbe = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { Instant.ofEpochMilli(it.toLong()) }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun save(provider: String, r: Record) {
        val value = listOf(
            r.state.name, r.since.toEpochMilli().toString(), r.alerted?.name.orEmpty(),
            r.lastOk?.toEpochMilli()?.toString().orEmpty(), r.lastProbe?.toEpochMilli()?.toString().orEmpty()
        ).joinToString("|")
        try {
            store.setState(KEY_PREFIX + provider, value)
        } catch (e: Exception) {
            logger.warn(e) { "[CliHealth] could not persist state of $provider" }
        }
    }

    private fun age(d: Duration): String = when {
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        d.toHours() < 48 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }

    companion object {
        const val CLAUDE = "claude"
        const val CODEX = "codex"
        private const val KEY_PREFIX = "cli_health:"
        private const val TOKEN_WARNED_KEY = "cli_health:claude_token_warned"
        private val ALERTING = setOf(CliState.AUTH, CliState.LIMIT, CliState.MODEL)
        private val PERSIST_OK_EVERY: Duration = Duration.ofMinutes(10)
        private val EMOJI = mapOf(
            CliState.OK to "✅", CliState.AUTH to "⛔", CliState.LIMIT to "⏳",
            CliState.MODEL to "⚠️", CliState.TIMEOUT to "⌛", CliState.ERROR to "❗"
        )
        private const val PROBE_SYSTEM = "You are a health check. Reply with the single word: pong"
        private const val PROBE_USER = "ping"

        /** Maps a classified CLI failure onto the health state. */
        fun stateOf(f: CliFailure): CliState = when (f) {
            CliFailure.AUTH -> CliState.AUTH
            CliFailure.LIMIT -> CliState.LIMIT
            CliFailure.MODEL -> CliState.MODEL
            CliFailure.OTHER -> CliState.ERROR
        }
    }
}
