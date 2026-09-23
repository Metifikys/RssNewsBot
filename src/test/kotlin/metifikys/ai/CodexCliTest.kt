package metifikys.ai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [CodexCli]'s subprocess transport without a real `codex` binary by
 * pointing `command` at a generated shim script. The shim is OS-aware: a `.cmd` on
 * Windows (JDK's ProcessImpl launches .cmd/.bat directly) and a `chmod +x` `sh`
 * script elsewhere.
 *
 * Because [CodexCli] reads the answer from the `--output-last-message` file rather
 * than stdout, every shim first scans its argv for that flag and captures the path
 * into `OUT`, so a body can write the expected answer there.
 */
class CodexCliTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private fun endpoint(model: String = "") =
        LlmEndpoint(baseUrl = "codex-cli", apiKey = "", model = model, provider = LlmEndpoint.Provider.CODEX_CLI)

    /**
     * Writes an executable shim. The given bodies run after a prologue that captures the
     * `--output-last-message` path into `%OUT%` (Windows) / `$OUT` (POSIX).
     */
    private fun shim(winBody: String, shBody: String): String {
        val dir = Files.createTempDirectory("codexcli-test").toFile()
        return if (isWindows) {
            val f = File(dir, "shim.cmd")
            val prologue = buildString {
                append("@echo off\r\n")
                append("set \"OUT=\"\r\n")
                append(":scan\r\n")
                append("if \"%~1\"==\"\" goto scandone\r\n")
                append("if \"%~1\"==\"--output-last-message\" set \"OUT=%~2\"\r\n")
                append("shift\r\n")
                append("goto scan\r\n")
                append(":scandone\r\n")
            }
            f.writeText("$prologue$winBody\r\n")
            f.absolutePath
        } else {
            val f = File(dir, "shim.sh")
            val prologue = buildString {
                append("#!/bin/sh\n")
                append("OUT=\"\"\n")
                append("while [ \$# -gt 0 ]; do\n")
                append("  if [ \"\$1\" = \"--output-last-message\" ]; then OUT=\"\$2\"; fi\n")
                append("  shift\n")
                append("done\n")
            }
            f.writeText("$prologue$shBody\n")
            f.setExecutable(true)
            f.absolutePath
        }
    }

    @Test
    fun `complete returns trimmed last-message file content`() {
        // Write the real answer to the --output-last-message file; stdout carries only noise.
        val cmd = shim(
            winBody = "echo reasoning noise\r\necho OK-FROM-CODEX>\"%OUT%\"",
            shBody = "echo reasoning noise\nprintf 'OK-FROM-CODEX\\n' > \"\$OUT\""
        )
        val cli = CodexCli(endpoint(), command = cmd, timeoutSeconds = 30)
        assertEquals("OK-FROM-CODEX", cli.complete("sys", "hello"))
    }

    @Test
    fun `falls back to stdout when last-message file is empty`() {
        // Do not write OUT — CodexCli must fall back to the trimmed stdout.
        val cmd = shim(winBody = "echo STDOUT-FALLBACK", shBody = "echo STDOUT-FALLBACK")
        val cli = CodexCli(endpoint(), command = cmd, timeoutSeconds = 30)
        assertEquals("STDOUT-FALLBACK", cli.complete("sys", "hello"))
    }

    @Test
    fun `timeout force-kills the process and throws`() {
        val cmd = shim(
            winBody = "ping -n 5 127.0.0.1 >nul",
            shBody = "sleep 5"
        )
        val cli = CodexCli(endpoint(), command = cmd, timeoutSeconds = 1, maxRetries = 0)
        val ex = assertThrows<IOException> { cli.complete("sys", "hello") }
        assertTrue(ex.message?.contains("timed out") == true, "expected timeout message, got: ${ex.message}")
    }

    @Test
    fun `timeout is terminal for both retry loops and carries the CLI output tail`() {
        // 2026-09-04: a hung `codex exec` (OpenAI/Codex incident, ~132 KB Step-1 prompt) was
        // retried 3× inner × 4× outer = 12 × 20 min ≈ 4 h. A timeout must fail on the first
        // attempt and say what the CLI printed before it was killed.
        //
        // The timeout is 5s, not 1s: the shim has to print its line before the clock runs out, and
        // a fresh .cmd (argv scan loop, AV scan of the new file) sometimes needed over a second to
        // reach its first echo, so the tail came back "<no output>" in ~1 of 3 runs. The child
        // sleeps far past the timeout, so the kill is still what ends the attempt.
        val cmd = shim(
            winBody = "echo still-waiting-upstream 1>&2\r\nping -n 61 127.0.0.1 >nul",
            shBody = "echo still-waiting-upstream >&2\nsleep 60"
        )
        // Default maxRetries (2) + outer maxRetry=3. Without the fail-fast a second attempt would
        // first sit out the 60s retry back-off, so finishing under 30s proves a single attempt.
        val cli = CodexCli(endpoint(), command = cmd, timeoutSeconds = 5, retryBackoffMillis = 60_000L)
        val started = System.nanoTime()
        val ex = assertThrows<CliTimeoutException> { cli.completeJson("sys", "hello", maxRetry = 3) }
        val elapsedSec = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(ex.message?.contains("timed out") == true, "got: ${ex.message}")
        assertTrue(ex.message?.contains("still-waiting-upstream") == true, "output tail missing: ${ex.message}")
        assertTrue(elapsedSec < 30, "expected a single attempt, took ${elapsedSec}s")
    }

    @Test
    fun `non-zero exit surfaces a retryable IOException with stderr`() {
        val cmd = shim(
            winBody = "echo boom 1>&2\r\nexit /b 3",
            shBody = "echo boom >&2\nexit 3"
        )
        // maxRetries = 0 → fail fast instead of looping with 60s back-off.
        val cli = CodexCli(endpoint(), command = cmd, timeoutSeconds = 30, maxRetries = 0)
        val ex = assertThrows<IOException> { cli.complete("sys", "hello") }
        assertTrue(ex.message?.contains("exited with code 3") == true, "got: ${ex.message}")
        assertTrue(ex.message?.contains("boom") == true, "stderr not captured: ${ex.message}")
    }

    @Test
    fun `usage-limit stderr maps to BillingException`() {
        val cmd = shim(
            winBody = "echo usage limit reached 1>&2\r\nexit /b 1",
            shBody = "echo usage limit reached >&2\nexit 1"
        )
        val cli = CodexCli(endpoint(), command = cmd, timeoutSeconds = 30)
        assertThrows<BillingException> { cli.complete("sys", "hello") }
    }

    @Test
    fun `echoed prompt is ignored when classifying a non-zero exit`() {
        // `codex exec` echoes the user prompt to stdout before its own error. Trigger words in
        // the prompt ("login … run", "model … not found") must not turn a transient failure
        // into a NonRetryable auth/model verdict, and the detail must carry the real error.
        val prompt = "please login and run the tool because the model was not found"
        val cmd = shim(
            winBody = "echo user\r\necho $prompt\r\necho ERROR: stream disconnected before completion\r\nexit /b 1",
            shBody = "echo user\necho '$prompt'\necho 'ERROR: stream disconnected before completion'\nexit 1"
        )
        val cli = CodexCli(endpoint(), command = cmd, timeoutSeconds = 30, maxRetries = 0)
        // A plain IOException is the retryable verdict; NonRetryableCliException is not an IOException.
        val ex = assertThrows<IOException> { cli.complete("sys", prompt) }
        assertTrue(ex.message?.contains("stream disconnected") == true, "real error missing: ${ex.message}")
        assertTrue(ex.message?.contains("please login") != true, "prompt echo leaked into detail: ${ex.message}")
    }

    @Test
    fun `auth error after the echoed prompt is still non-retryable`() {
        val prompt = "summarize these articles"
        val cmd = shim(
            winBody = "echo user\r\necho $prompt\r\necho ERROR: not logged in, run codex login\r\nexit /b 1",
            shBody = "echo user\necho '$prompt'\necho 'ERROR: not logged in, run codex login'\nexit 1"
        )
        val cli = CodexCli(endpoint(), command = cmd, timeoutSeconds = 30, maxRetries = 0)
        val ex = assertThrows<NonRetryableCliException> { cli.complete("sys", prompt) }
        assertTrue(ex.message?.contains("auth/login") == true, "got: ${ex.message}")
    }

    @Test
    fun `batch methods are unsupported`() {
        val cli = CodexCli(endpoint(), command = "codex", timeoutSeconds = 30)
        assertThrows<UnsupportedOperationException> { cli.resumeBatch("x") }
        assertThrows<UnsupportedOperationException> { cli.submitExtractBatch("c", "s", "u") }
    }
}
