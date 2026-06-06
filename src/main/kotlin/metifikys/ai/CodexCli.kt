package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Sync LLM client that shells out to the local OpenAI `codex exec` CLI (Codex in
 * non-interactive mode). Authentication is whatever the machine's `codex` login uses
 * (a ChatGPT subscription or a configured key) — this client never sees an API key.
 * Mirrors [ClaudeCli] / [Anthropic] in retry/billing semantics and JSON handling so the
 * choice of provider does not change editorial output.
 *
 * Transport:
 *   argv:  command exec --skip-git-repo-check [--model <model>]
 *          -c model_instructions_file="<utf8 file>" --output-last-message <file> -
 *   stdin: the user prompt (the trailing `-` makes codex read the prompt from stdin,
 *          avoiding OS argv length limits and shell escaping)
 *   result: read from the --output-last-message file (the final assistant message only,
 *           so we don't parse codex's reasoning/log lines); falls back to stdout if blank
 *
 * The process runs in a neutral temp directory so Codex does not pick up this
 * repository's AGENTS.md / project context, which would skew the output.
 *
 * Batch methods throw [UnsupportedOperationException] — the CLI has no Batch API.
 */
class CodexCli(
    override val endpoint: LlmEndpoint,
    private val command: String,
    private val timeoutSeconds: Long,
    private val maxRetries: Int = 5,
    private val retryBackoffMillis: Long = 60_000L,
    private val batchUnsupportedReason: String =
        "Codex CLI provider has no Batch API — it is sync-only"
) : LlmClient {

    private val model: String get() = endpoint.model

    // Neutral working dir keeps `codex exec` from loading the repo's project context.
    private val workDir by lazy { Files.createTempDirectory("rssbot-codexcli").toFile() }

    override fun summarizeArticles(
        category: String,
        emoji: String,
        articles: List<Article>,
        systemPrompt: String?,
        userPrompt: String?,
        previousSummaries: List<String>,
        maxArticles: Int
    ): String {
        val prompt = PromptBuilder.buildLegacyUserPrompt(
            category, articles, userPrompt, previousSummaries, maxArticles
        )
        return request(prompt, systemPrompt)
    }

    override fun summarizeShortlist(
        category: String,
        emoji: String,
        shortlist: List<ShortlistItem>,
        articles: List<Article>,
        renderSystemPrompt: String,
        renderUserPromptTemplate: String
    ): String {
        val prompt = PromptBuilder.buildRenderUserPrompt(
            category, emoji, renderUserPromptTemplate, shortlist, articles
        )
        return request(prompt, renderSystemPrompt)
    }

    override fun complete(systemPrompt: String, userPrompt: String): String =
        request(prompt = userPrompt, systemPromptOverride = systemPrompt)

    override fun submitCategoryBatch(
        categoryName: String,
        input: CategoryInput
    ): CompletableFuture<String> = throw UnsupportedOperationException(batchUnsupportedReason)

    override fun resumeBatch(batchId: String): CompletableFuture<Map<String, String>> =
        throw UnsupportedOperationException(batchUnsupportedReason)

    override fun submitExtractBatch(
        category: String,
        systemPrompt: String,
        userPrompt: String,
        articleLinks: String
    ): CompletableFuture<String> = throw UnsupportedOperationException(batchUnsupportedReason)

    override fun resumeExtractBatch(batchId: String): CompletableFuture<String> =
        throw UnsupportedOperationException(batchUnsupportedReason)

    /**
     * The CLI has no JSON-mode toggle. As with [ClaudeCli], we append a strict
     * instruction asking for raw JSON only and strip any code fences. Caller parses.
     */
    override fun completeJson(systemPrompt: String, userPrompt: String): String {
        val jsonInstruction = "\n\nRespond with a single valid JSON object only. " +
                "Do not wrap the JSON in code fences and do not include any prose before or after."
        val raw = request(prompt = userPrompt + jsonInstruction, systemPromptOverride = systemPrompt)
        return stripCodeFences(raw)
    }

    override fun completeJson(systemPrompt: String, userPrompt: String, maxRetry: Int): String {
        var attempt = 0
        var lastException: Exception? = null
        while (attempt <= maxRetry) {
            try {
                val jsonString = completeJson(systemPrompt, userPrompt)
                Json.parseToJsonElement(jsonString)
                return jsonString
            } catch (e: Exception) {
                lastException = e
                attempt++
                if (attempt > maxRetry) throw e
                logger.error(e) { "CodexCli completeJson attempt $attempt failed" }
                Thread.sleep(attempt * 20 * 1000L)
            }
        }
        throw lastException ?: IOException("Unknown error")
    }

    private fun stripCodeFences(s: String): String {
        val trimmed = s.trim()
        if (!trimmed.startsWith("```")) return trimmed
        // ```json\n...\n``` or ```\n...\n```
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed
        val withoutOpening = trimmed.substring(firstNewline + 1)
        val closing = withoutOpening.lastIndexOf("```")
        return if (closing >= 0) withoutOpening.substring(0, closing).trim() else withoutOpening.trim()
    }

    private fun request(prompt: String, systemPromptOverride: String? = null): String {
        val systemPrompt = systemPromptOverride ?: PromptBuilder.LEGACY_SYSTEM_PROMPT

        logger.info { "[LLM][sync][codexcli] REQUEST | model=${model.ifBlank { "<cli-default>" }}\n--- system ---\n$systemPrompt\n--- user ---\n$prompt" }

        // Pass the system prompt via a UTF-8 FILE referenced by codex's `model_instructions_file`
        // config key, not an argv. On Windows the JVM encodes process arguments with
        // sun.jnu.encoding (cp1251/cp1252), which mangles the Cyrillic examples in the editorial
        // prompt before `codex` ever reads them; a file sidesteps argv encoding entirely.
        val systemPromptFile = Files.createTempFile(workDir.toPath(), "sysprompt", ".md")
        Files.writeString(systemPromptFile, systemPrompt)
        // Codex writes only the final assistant message here, so we don't have to parse its
        // reasoning/log stream off stdout.
        val outFile = Files.createTempFile(workDir.toPath(), "codex-last", ".txt")

        val args = buildList {
            add(command)
            add("exec")
            add("--skip-git-repo-check") // the neutral temp workDir is not a git repo
            if (model.isNotBlank()) { add("--model"); add(model) }
            // Forward slashes so a Windows path's backslashes aren't parsed as TOML escapes.
            add("-c"); add("model_instructions_file=\"${systemPromptFile.toString().replace('\\', '/')}\"")
            add("--output-last-message"); add(outFile.toString())
            add("-") // read the prompt from stdin
        }

        var attempt = 0
        var lastException: Exception? = null

        try {
            while (attempt <= maxRetries) {
                try {
                    val result = runOnce(args, prompt, outFile)
                    if (result.isBlank()) {
                        logger.warn { "Empty CodexCli response. model=${model.ifBlank { "<cli-default>" }}" }
                    }
                    logger.info { "[LLM][sync][codexcli] RESPONSE | model=${model.ifBlank { "<cli-default>" }}\n$result" }
                    return result
                } catch (e: BillingException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    attempt++
                    if (attempt > maxRetries) throw e
                    logger.error(e) { "CodexCli attempt $attempt failed" }
                    Thread.sleep(retryBackoffMillis)
                }
            }
        } finally {
            try { Files.deleteIfExists(systemPromptFile) } catch (_: IOException) { /* best effort */ }
            try { Files.deleteIfExists(outFile) } catch (_: IOException) { /* best effort */ }
        }

        throw lastException ?: IOException("Unknown error")
    }

    /**
     * Runs one subprocess invocation. stdin is fed from a writer thread and BOTH stdout
     * and stderr are drained on their own threads — reading stdout to EOF on the calling
     * thread would block until the process exits and defeat the timeout. We therefore
     * [Process.waitFor] with the timeout first; only on clean exit do we join the drains
     * and read the answer. The final message is read from [outFile] (the codex
     * `--output-last-message` file); if that is blank we fall back to the trimmed stdout.
     * A timeout force-kills the process and surfaces a (retryable) IOException.
     */
    private fun runOnce(args: List<String>, prompt: String, outFile: Path): String {
        val process = ProcessBuilder(args)
            .directory(workDir)
            .redirectErrorStream(false)
            .start()

        val stdinWriter = Thread {
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(prompt) }
            } catch (_: IOException) {
                // process may have exited early; surfaced via exit code / stderr below
            }
        }.apply { isDaemon = true; start() }

        val stdoutBuf = StringBuilder()
        val stdoutDrain = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { stdoutBuf.appendLine(it) }
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true; start() }

        val stderrBuf = StringBuilder()
        val stderrDrain = Thread {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { stderrBuf.appendLine(it) }
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IOException("codex CLI timed out after ${timeoutSeconds}s")
        }
        stdinWriter.join(1000)
        stdoutDrain.join(1000)
        stderrDrain.join(1000)

        val exit = process.exitValue()
        if (exit != 0) {
            val stderr = stderrBuf.toString().trim()
            if (isBillingError(stderr)) {
                throw BillingException("Codex CLI usage/credit limit reached: $stderr")
            }
            throw IOException("codex CLI exited with code $exit: ${stderr.ifBlank { "<no stderr>" }}")
        }

        // Prefer the final-message file; fall back to stdout if codex wrote nothing there.
        val lastMessage = try {
            Files.readString(outFile).trim()
        } catch (_: IOException) {
            ""
        }
        return lastMessage.ifBlank { stdoutBuf.toString().trim() }
    }

    private fun isBillingError(stderr: String): Boolean {
        val s = stderr.lowercase()
        return "usage limit" in s || "quota" in s || "credit" in s ||
            "insufficient_quota" in s || "out of credits" in s ||
            "rate limit" in s && "exceeded" in s
    }
}
