package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.db.NewsDatabase
import metifikys.model.CategoryInput
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Anthropic Message Batches API client. Mirrors [OpenAIBatch] in retry/persistence/
 * polling shape so the choice of provider does not change editorial output.
 *
 * Wire shape:
 *   Submit:  POST {baseUrl}/messages/batches  with `{ requests: [{ custom_id, params: {...} }] }`
 *   Status:  GET  {baseUrl}/messages/batches/{id}     → `{ processing_status, results_url, ... }`
 *   Results: GET  {results_url}                       → JSONL of `{ custom_id, result }`
 *
 * Batch IDs are persisted in [db] via the shared [metifikys.db.PendingBatchesTable]
 * so in-flight jobs survive bot restarts.
 */
class AnthropicBatch(
    private val endpoint: LlmEndpoint,
    private val db: NewsDatabase,
    private val maxTokens: Int
) {

    private val apiKey: String get() = endpoint.apiKey
    private val model: String get() = endpoint.model
    private val batchesUrl: String = "${endpoint.baseUrl}/messages/batches"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Daemon thread pool for background polling — won't prevent JVM shutdown. */
    private val pollExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "anthropic-batch-poll").also { it.isDaemon = true }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ── Wire types ────────────────────────────────────────────────────────────

    @Serializable
    private data class UserMsg(val role: String = "user", val content: String)

    @Serializable
    private data class RequestParams(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<UserMsg>
    )

    @Serializable
    private data class BatchRequestEntry(
        val custom_id: String,
        val params: RequestParams
    )

    @Serializable
    private data class BatchCreateRequest(val requests: List<BatchRequestEntry>)

    @Serializable
    private data class BatchStatusResponse(
        val id: String,
        val processing_status: String,
        val results_url: String? = null,
        val ended_at: String? = null
    )

    companion object {
        /** Mirror OpenAIBatch's per-job category cap for symmetry. */
        const val MAX_CATEGORIES_PER_BATCH = OpenAIBatch.MAX_CATEGORIES_PER_BATCH
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun submitCategory(
        categoryName: String,
        input: CategoryInput
    ): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            val singleMap = mapOf(categoryName to input)
            val results = submitBatchJob(singleMap, chunkIndex = 0, totalChunks = 1)
            results[categoryName] ?: throw IOException("No result for category '$categoryName'")
        }, pollExecutor)
    }

    fun resumeBatch(batchId: String): CompletableFuture<Map<String, String>> {
        logger.info { "[AnthropicBatch] Resuming batch $batchId..." }
        return CompletableFuture.supplyAsync({ pollAndCollect(batchId) }, pollExecutor)
    }

    /**
     * Submits a single-request batch for Step 1 event extraction.
     * Adds the JSON-mode instruction to [userPrompt] and strips code fences on return,
     * mirroring [Anthropic.completeJson] behavior.
     * Saves a DB row with `kind="extract"` and [articleLinks] for restart recovery.
     */
    fun submitExtract(
        category: String,
        systemPrompt: String,
        userPrompt: String,
        articleLinks: String = ""
    ): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            val jsonInstruction = "\n\nRespond with a single valid JSON object only. " +
                "Do not wrap the JSON in code fences and do not include any prose before or after."
            val entry = BatchRequestEntry(
                custom_id = category,
                params = RequestParams(
                    model = model,
                    max_tokens = maxTokens,
                    system = systemPrompt,
                    messages = listOf(UserMsg(content = userPrompt + jsonInstruction))
                )
            )
            logger.info { "[LLM][batch][extract][anthropic] REQUEST | id=$category model=$model" }

            val payload = json.encodeToString(BatchCreateRequest(requests = listOf(entry)))
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(batchesUrl)
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .also { b -> for ((k, v) in endpoint.extraHeaders) b.header(k, v) }
                .post(payload)
                .build()
            val batchId = client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw IOException("Empty response from batch create")
                if (!response.isSuccessful) {
                    if (isBillingError(body)) throw BillingException("Anthropic billing/quota limit reached: $body")
                    throw IOException("Anthropic extract batch create failed: $body")
                }
                json.decodeFromString<BatchStatusResponse>(body).id
            }
            logger.info { "[AnthropicBatch][extract] Batch created: $batchId — saving to DB..." }
            db.savePendingBatch(batchId, 0, 1, category, articleLinks, null, kind = "extract")

            val results = pollAndCollect(batchId)
            val rawText = results[category]
                ?: throw IOException("No extract result for category '$category' in batch $batchId")
            val content = stripCodeFences(rawText)
            logger.info { "[LLM][batch][extract][anthropic] RESPONSE | id=$category\n$content" }
            content
        }, pollExecutor)
    }

    /**
     * Resumes polling a previously submitted extract batch.
     * Returns a future that resolves to the raw JSON content (single result, first value).
     * Strips code fences from the Anthropic response.
     */
    fun resumeExtract(batchId: String): CompletableFuture<String> {
        logger.info { "[AnthropicBatch][extract] Resuming extract batch $batchId..." }
        return CompletableFuture.supplyAsync({
            val results = pollAndCollect(batchId)
            val rawText = results.values.firstOrNull()
                ?: throw IOException("No result in extract batch $batchId")
            stripCodeFences(rawText)
        }, pollExecutor)
    }

    private fun stripCodeFences(s: String): String {
        val trimmed = s.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed
        val withoutOpening = trimmed.substring(firstNewline + 1)
        val closing = withoutOpening.lastIndexOf("```")
        return if (closing >= 0) withoutOpening.substring(0, closing).trim() else withoutOpening.trim()
    }

    // ── Submission ────────────────────────────────────────────────────────────

    private fun submitBatchJob(
        categories: Map<String, CategoryInput>,
        chunkIndex: Int,
        totalChunks: Int
    ): Map<String, String> {
        val entries = categories.map { (name, input) ->
            val renderMode = input.shortlist != null
            val effectiveSystemPrompt = when {
                renderMode -> input.renderSystemPrompt
                    ?: error("CategoryInput for '$name' is in render mode but renderSystemPrompt is null")
                else -> input.systemPrompt ?: PromptBuilder.LEGACY_SYSTEM_PROMPT
            }
            val userContent = if (renderMode) {
                PromptBuilder.buildRenderUserPrompt(
                    name,
                    input.emoji,
                    input.renderUserPrompt
                        ?: error("CategoryInput for '$name' is in render mode but renderUserPrompt is null"),
                    input.shortlist!!,
                    input.articles
                )
            } else {
                PromptBuilder.buildLegacyUserPrompt(
                    name, input.articles, input.userPrompt, input.previousSummaries
                )
            }
            BatchRequestEntry(
                custom_id = name,
                params = RequestParams(
                    model = model,
                    max_tokens = maxTokens,
                    system = effectiveSystemPrompt,
                    messages = listOf(UserMsg(content = userContent))
                )
            )
        }

        entries.forEach { e ->
            logger.info {
                "[LLM][batch][anthropic] REQUEST | id=${e.custom_id} model=${e.params.model}\n" +
                        "--- system ---\n${e.params.system}\n--- user ---\n${e.params.messages.firstOrNull()?.content.orEmpty()}"
            }
        }

        val payload = json.encodeToString(BatchCreateRequest(requests = entries))
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(batchesUrl)
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .also { b -> for ((k, v) in endpoint.extraHeaders) b.header(k, v) }
            .post(payload)
            .build()

        val batchId = client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty response from batch create")
            if (!response.isSuccessful) {
                if (isBillingError(body)) throw BillingException("Anthropic billing/quota limit reached: $body")
                throw IOException("Anthropic batch create failed: $body")
            }
            json.decodeFromString<BatchStatusResponse>(body).id
        }

        logger.info { "[AnthropicBatch] Batch created: $batchId — saving to DB..." }
        val categoryNamesCsv = categories.keys.joinToString(",")
        val articleLinks = categories.values.flatMap { it.articles }.map { it.link }.joinToString("\n")
        val shortlistJson = categories.values.firstOrNull()?.shortlist?.let { json.encodeToString(it) }
        db.savePendingBatch(batchId, chunkIndex, totalChunks, categoryNamesCsv, articleLinks, shortlistJson)

        return pollAndCollect(batchId)
    }

    /** Poll until complete, mark DB status, return parsed results. */
    private fun pollAndCollect(batchId: String): Map<String, String> {
        return try {
            val resultsUrl = pollUntilComplete(batchId)
            logger.info { "[AnthropicBatch] Batch $batchId completed. Downloading results..." }
            val results = downloadResults(resultsUrl)
            db.updateBatchStatus(batchId, "completed")
            results
        } catch (e: Exception) {
            db.updateBatchStatus(batchId, "failed")
            throw e
        }
    }

    /**
     * Polls GET {baseUrl}/messages/batches/{id} until processing_status="ended".
     * Exponential backoff: 30s → 5m cap, 26h deadline. Returns the results_url.
     */
    private fun pollUntilComplete(batchId: String): String {
        var intervalMs = 30_000L
        val maxIntervalMs = 5 * 60_000L
        val deadlineMs = System.currentTimeMillis() + 26 * 60 * 60_000L

        while (System.currentTimeMillis() < deadlineMs) {
            val status = fetchBatchStatus(batchId)
            logger.info { "[AnthropicBatch] Status: ${status.processing_status}" }

            when (status.processing_status) {
                "ended" -> {
                    return status.results_url
                        ?: throw IOException("Anthropic batch ended but results_url is missing (id=$batchId)")
                }
                "canceling" -> {
                    throw IOException("Anthropic batch was canceled (id=$batchId)")
                }
                // in_progress — keep waiting
            }

            Thread.sleep(intervalMs)
            intervalMs = minOf(intervalMs * 2, maxIntervalMs)
        }

        throw IOException("Anthropic batch polling timed out after 26h (id=$batchId)")
    }

    private fun fetchBatchStatus(batchId: String): BatchStatusResponse {
        val request = Request.Builder()
            .url("$batchesUrl/$batchId")
            .header("x-api-key", apiKey)
            .also { b -> for ((k, v) in endpoint.extraHeaders) b.header(k, v) }
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty response from batch status")
            if (!response.isSuccessful) throw IOException("Anthropic batch status failed: $body")
            json.decodeFromString<BatchStatusResponse>(body)
        }
    }

    /**
     * Downloads JSONL results from the signed [resultsUrl] returned by the status endpoint.
     * Each line is `{ custom_id, result: { type, message?: { content: [{type:"text", text:"..."}] } } }`.
     * Lines whose `result.type` is not `"succeeded"` are logged and skipped (the caller
     * sees a missing key for that custom_id, matching OpenAIBatch behavior).
     */
    private fun downloadResults(resultsUrl: String): Map<String, String> {
        val request = Request.Builder()
            .url(resultsUrl)
            .header("x-api-key", apiKey)
            .also { b -> for ((k, v) in endpoint.extraHeaders) b.header(k, v) }
            .get()
            .build()

        val jsonl = client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty results stream")
            if (!response.isSuccessful) throw IOException("Anthropic results download failed: $body")
            body
        }

        val results = mutableMapOf<String, String>()
        for (line in jsonl.lines()) {
            if (line.isBlank()) continue
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val customId = obj["custom_id"]?.jsonPrimitive?.content ?: continue
                val result = obj["result"]?.jsonObject
                val resultType = result?.get("type")?.jsonPrimitive?.content
                if (resultType != "succeeded") {
                    logger.warn { "[AnthropicBatch] Non-success result for custom_id=$customId type=$resultType line=$line" }
                    continue
                }
                val text = result["message"]?.jsonObject
                    ?.get("content")?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                    ?.jsonObject?.get("text")?.jsonPrimitive?.content
                if (text != null) {
                    logger.info { "[LLM][batch][anthropic] RESPONSE | id=$customId\n$text" }
                    results[customId] = text
                } else {
                    logger.warn { "[AnthropicBatch] No text content for custom_id=$customId" }
                }
            } catch (e: Exception) {
                logger.error(e) { "[AnthropicBatch] Failed to parse result line" }
            }
        }
        return results
    }
}
