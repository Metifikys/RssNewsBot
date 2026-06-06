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
    fun `batch methods are unsupported`() {
        val cli = CodexCli(endpoint(), command = "codex", timeoutSeconds = 30)
        assertThrows<UnsupportedOperationException> { cli.resumeBatch("x") }
        assertThrows<UnsupportedOperationException> { cli.submitExtractBatch("c", "s", "u") }
    }
}
