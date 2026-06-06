package metifikys.ai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [ClaudeCli]'s subprocess transport without a real `claude` binary by
 * pointing `command` at a generated shim script. The shim is OS-aware: a `.cmd` on
 * Windows (JDK's ProcessImpl launches .cmd/.bat directly) and a `chmod +x` `sh`
 * script elsewhere.
 */
class ClaudeCliTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private fun endpoint(model: String = "") =
        LlmEndpoint(baseUrl = "claude-cli", apiKey = "", model = model, provider = LlmEndpoint.Provider.CLAUDE_CLI)

    /** Writes an executable shim with the given Windows-batch / POSIX-sh bodies. */
    private fun shim(winBody: String, shBody: String): String {
        val dir = Files.createTempDirectory("claudecli-test").toFile()
        return if (isWindows) {
            val f = File(dir, "shim.cmd")
            f.writeText("@echo off\r\n$winBody\r\n")
            f.absolutePath
        } else {
            val f = File(dir, "shim.sh")
            f.writeText("#!/bin/sh\n$shBody\n")
            f.setExecutable(true)
            f.absolutePath
        }
    }

    @Test
    fun `complete returns trimmed stdout from the CLI`() {
        val cmd = shim(winBody = "echo OK-FROM-CLI", shBody = "echo OK-FROM-CLI")
        val cli = ClaudeCli(endpoint(), command = cmd, timeoutSeconds = 30)
        assertEquals("OK-FROM-CLI", cli.complete("sys", "hello"))
    }

    @Test
    fun `timeout force-kills the process and throws`() {
        val cmd = shim(
            winBody = "ping -n 5 127.0.0.1 >nul",
            shBody = "sleep 5"
        )
        val cli = ClaudeCli(endpoint(), command = cmd, timeoutSeconds = 1, maxRetries = 0)
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
        val cli = ClaudeCli(endpoint(), command = cmd, timeoutSeconds = 30, maxRetries = 0)
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
        val cli = ClaudeCli(endpoint(), command = cmd, timeoutSeconds = 30)
        assertThrows<BillingException> { cli.complete("sys", "hello") }
    }

    @Test
    fun `batch methods are unsupported`() {
        val cli = ClaudeCli(endpoint(), command = "claude", timeoutSeconds = 30)
        assertThrows<UnsupportedOperationException> { cli.resumeBatch("x") }
        assertThrows<UnsupportedOperationException> { cli.submitExtractBatch("c", "s", "u") }
    }
}
