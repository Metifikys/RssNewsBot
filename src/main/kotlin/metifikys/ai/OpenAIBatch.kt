package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * OpenAI Batch API client.
 * Submits all category prompts as a single batch job for a 50% cost discount.
 * Completion window is 24h, but jobs typically finish much faster.
 *
 * Batch IDs are persisted in [db] so that in-flight jobs survive bot restarts.
 */
class OpenAIBatch(
    private val apiKey: String,
    private val db: NewsDatabase,
    private val model: String = "gpt-5-mini-2025-08-07"
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Do not follow redirects — prevents API key leakage to a third-party host
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Daemon thread pool for background batch polling.
     * Threads are daemon so they don't prevent JVM shutdown.
     */
    private val pollExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "batch-poll").also { it.isDaemon = true }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ── Data classes ──────────────────────────────────────────────────────────

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ResponseFormat(val type: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val response_format: ResponseFormat? = null
    )

    @Serializable
    private data class BatchLine(
        val custom_id: String,
        val method: String,
        val url: String,
        val body: ChatRequest
    )

    @Serializable
    private data class FileUploadResponse(val id: String)

    @Serializable
    private data class BatchCreateRequest(
        val input_file_id: String,
        val endpoint: String,
        val completion_window: String
    )

    @Serializable
    private data class BatchStatusResponse(
        val id: String,
        val status: String,
        val output_file_id: String? = null,
        val error_file_id: String? = null
    )

    @Serializable
    private data class ResultChoice(val message: ResultMessage)

    @Serializable
    private data class ResultMessage(val content: String)

    @Serializable
    private data class ResultBody(val choices: List<ResultChoice>)

    @Serializable
    private data class ResultResponse(val body: ResultBody)

    // ── Public API ────────────────────────────────────────────────────────────

    companion object {
        /** Maximum number of categories (requests) per single batch job. */
        const val MAX_CATEGORIES_PER_BATCH = 10
    }

    /**
     * Submits a single category as its own independent batch job.
     * Returns a [CompletableFuture] that completes with the summary text for this category.
     * The future runs on [pollExecutor] (daemon threads) so the scheduler thread is never blocked.
     *
     * Throws [BillingException] inside the future on quota/billing errors (non-retryable).
     */
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

    /**
     * Summarizes all categories asynchronously, splitting into batch jobs of at most
     * [MAX_CATEGORIES_PER_BATCH] requests each.
     *
     * Returns a [CompletableFuture] that completes with a merged map of category name → summary text
     * once ALL chunk jobs finish. The future runs on [pollExecutor] (daemon threads) so the
     * scheduler thread is never blocked.
     *
     * Throws [BillingException] inside the future on quota/billing errors (non-retryable).
     */
    fun summarizeAllCategories(
        categories: Map<String, CategoryInput>
    ): CompletableFuture<Map<String, String>> {
        if (categories.isEmpty()) return CompletableFuture.completedFuture(emptyMap())

        val chunks = categories.entries.chunked(MAX_CATEGORIES_PER_BATCH)

        // Submit each chunk as an independent async future, then merge results
        val chunkFutures: List<CompletableFuture<Map<String, String>>> =
            chunks.mapIndexed { index, chunk ->
                val chunkMap: Map<String, CategoryInput> = chunk.associate { it.key to it.value }
                if (chunks.size > 1) {
                    logger.info { "[Batch] Submitting chunk ${index + 1}/${chunks.size} (${chunkMap.size} categories)..." }
                }
                CompletableFuture.supplyAsync(
                    { submitBatchJob(chunkMap, chunkIndex = index, totalChunks = chunks.size) },
                    pollExecutor
                )
            }

        // Merge all chunk results into a single map once all futures complete
        return CompletableFuture.allOf(*chunkFutures.toTypedArray())
            .thenApply {
                chunkFutures.flatMap { it.get().entries }.associate { it.key to it.value }
            }
    }

    /**
     * Submits a single batch job for the given categories (≤ [MAX_CATEGORIES_PER_BATCH]).
     * Persists the batch ID to DB before polling so it survives a restart.
     * Returns a map of category name → summary text.
     */
    private fun submitBatchJob(
        categories: Map<String, CategoryInput>,
        chunkIndex: Int,
        totalChunks: Int
    ): Map<String, String> {
        val lines = categories.map { (name, input) ->
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
            BatchLine(
                custom_id = name,
                method = "POST",
                url = "/v1/chat/completions",
                body = ChatRequest(
                    model = model,
                    messages = listOf(
                        Message("system", effectiveSystemPrompt),
                        Message("user", userContent)
                    )
                )
            )
        }

        lines.forEach { line ->
            val sys = line.body.messages.firstOrNull { it.role == "system" }?.content.orEmpty()
            val usr = line.body.messages.firstOrNull { it.role == "user" }?.content.orEmpty()
            logger.info { "[LLM][batch] REQUEST | id=${line.custom_id} model=${line.body.model}\n--- system ---\n$sys\n--- user ---\n$usr" }
        }

        val jsonl = lines.joinToString("\n") { json.encodeToString(it) }

        logger.info { "[Batch] Uploading ${lines.size} requests..." }
        val fileName = categories.keys.joinToString("_")
        val fileId = uploadBatchFile(jsonl, fileName)
        logger.info { "[Batch] File uploaded: $fileId" }

        val batchId = createBatch(fileId)
        logger.info { "[Batch] Batch created: $batchId — saving to DB..." }
        val categoryNamesCsv = categories.keys.joinToString(",")
        val articleLinks = categories.values.flatMap { it.articles }.map { it.link }.joinToString("\n")
        val shortlistJson = categories.values.firstOrNull()?.shortlist?.let { json.encodeToString(it) }
        db.savePendingBatch(batchId, chunkIndex, totalChunks, categoryNamesCsv, articleLinks, shortlistJson)

        return pollAndCollect(batchId)
    }

    /**
     * Resumes polling a previously saved batch job (e.g. after a bot restart).
     * Returns a [CompletableFuture] with map of custom_id → summary text, same as a fresh job.
     */
    fun resumeBatch(batchId: String): CompletableFuture<Map<String, String>> {
        logger.info { "[Batch] Resuming batch $batchId..." }
        return CompletableFuture.supplyAsync({ pollAndCollect(batchId) }, pollExecutor)
    }

    /**
     * Submits a single-request batch for Step 1 event extraction (JSON mode).
     * Saves a DB row with `kind="extract"` and [articleLinks] for restart recovery.
     * Returns a future that resolves to the raw JSON content string.
     */
    fun submitExtract(
        category: String,
        systemPrompt: String,
        userPrompt: String,
        articleLinks: String = ""
    ): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            val line = BatchLine(
                custom_id = category,
                method = "POST",
                url = "/v1/chat/completions",
                body = ChatRequest(
                    model = model,
                    messages = listOf(
                        Message("system", systemPrompt),
                        Message("user", userPrompt)
                    ),
                    response_format = ResponseFormat("json_object")
                )
            )
            logger.info { "[LLM][batch][extract] REQUEST | id=$category model=$model" }
            val jsonl = json.encodeToString(line)
            val fileId = uploadBatchFile(jsonl, "${category}_extract")
            val batchId = createBatch(fileId)
            logger.info { "[OpenAIBatch][extract] Batch created: $batchId — saving to DB..." }
            db.savePendingBatch(batchId, 0, 1, category, articleLinks, null, kind = "extract")
            val results = pollAndCollect(batchId)
            val content = results[category] ?: throw IOException("No extract result for category '$category' in batch $batchId")
            logger.info { "[LLM][batch][extract] RESPONSE | id=$category\n$content" }
            content
        }, pollExecutor)
    }

    /**
     * Resumes polling a previously submitted extract batch.
     * Returns a future that resolves to the raw JSON content (single result, first value).
     */
    fun resumeExtract(batchId: String): CompletableFuture<String> {
        logger.info { "[OpenAIBatch][extract] Resuming extract batch $batchId..." }
        return CompletableFuture.supplyAsync({
            val results = pollAndCollect(batchId)
            results.values.firstOrNull()
                ?: throw IOException("No result in extract batch $batchId")
        }, pollExecutor)
    }

    /** Polls until complete, marks DB status, and returns results. */
    private fun pollAndCollect(batchId: String): Map<String, String> {
        return try {
            val outputFileId = pollUntilComplete(batchId)
            logger.info { "[Batch] Batch $batchId completed. Downloading results..." }
            val results = downloadResults(outputFileId)
            db.updateBatchStatus(batchId, "completed")
            results
        } catch (e: Exception) {
            db.updateBatchStatus(batchId, "failed")
            throw e
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Upload JSONL content to Files API with purpose=batch. Returns file id. */
    private fun uploadBatchFile(jsonl: String, fileName: String = "batch_input.jsonl"): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                jsonl.toRequestBody("application/jsonl".toMediaType())
            )
            .addFormDataPart("purpose", "batch")
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/files")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty response from file upload")
            if (!response.isSuccessful) {
                if (isBillingError(body)) throw BillingException("OpenAI billing/quota limit reached: $body")
                throw IOException("File upload failed: $body")
            }
            json.decodeFromString<FileUploadResponse>(body).id
        }
    }

    /** Create batch job. Returns batch id. */
    private fun createBatch(fileId: String): String {
        val payload = json.encodeToString(
            BatchCreateRequest(
                input_file_id = fileId,
                endpoint = "/v1/chat/completions",
                completion_window = "24h"
            )
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/batches")
            .header("Authorization", "Bearer $apiKey")
            .post(payload)
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty response from batch create")
            if (!response.isSuccessful) {
                if (isBillingError(body)) throw BillingException("OpenAI billing/quota limit reached: $body")
                throw IOException("Batch create failed: $body")
            }
            json.decodeFromString<BatchStatusResponse>(body).id
        }
    }

    /**
     * Polls GET /v1/batches/{id} until status is "completed" or terminal failure.
     * Uses exponential backoff: starts at 30s, caps at 5 minutes.
     * Returns the output_file_id.
     */
    private fun pollUntilComplete(batchId: String): String {
        var intervalMs = 30_000L
        val maxIntervalMs = 5 * 60_000L
        val deadlineMs = System.currentTimeMillis() + 26 * 60 * 60_000L  // 26h safety limit

        while (System.currentTimeMillis() < deadlineMs) {
            val status = fetchBatchStatus(batchId)
            logger.info { "[Batch] Status: ${status.status}" }

            when (status.status) {
                "completed" -> {
                    return status.output_file_id
                        ?: throw IOException("Batch completed but output_file_id is missing")
                }
                "failed", "expired", "cancelled" -> {
                    throw IOException("Batch ended with status '${status.status}' (id=$batchId)")
                }
                // validating, in_progress, finalizing — keep waiting
            }

            Thread.sleep(intervalMs)
            intervalMs = minOf(intervalMs * 2, maxIntervalMs)
        }

        throw IOException("Batch polling timed out after 26h (id=$batchId)")
    }

    private fun fetchBatchStatus(batchId: String): BatchStatusResponse {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/batches/$batchId")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty response from batch status")
            if (!response.isSuccessful) throw IOException("Batch status failed: $body")
            json.decodeFromString<BatchStatusResponse>(body)
        }
    }

    /** Download and parse JSONL results file. Returns map of custom_id → content text. */
    private fun downloadResults(outputFileId: String): Map<String, String> {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/files/$outputFileId/content")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val jsonl = client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty results file")
            if (!response.isSuccessful) throw IOException("Results download failed: $body")
            body
        }

        val results = mutableMapOf<String, String>()
        for (line in jsonl.lines()) {
            if (line.isBlank()) continue
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val customId = obj["custom_id"]?.jsonPrimitive?.content ?: continue
                val content = obj["response"]
                    ?.jsonObject?.get("body")
                    ?.jsonObject?.get("choices")
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content
                if (content != null) {
                    logger.info { "[LLM][batch] RESPONSE | id=$customId\n$content" }
                    results[customId] = content
                } else {
                    logger.warn { "[Batch] No content for custom_id=$customId" }
                }
            } catch (e: Exception) {
                logger.error(e) { "[Batch] Failed to parse result line" }
            }
        }
        return results
    }
}
