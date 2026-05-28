package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Sync chat-completions client. Uses any OpenAI-compatible provider described by [endpoint]
 * (OpenAI itself or OpenRouter). Batch methods throw [UnsupportedOperationException] —
 * use [OpenAIWithBatch] when batch capability is required.
 *
 * [batchUnsupportedReason] is surfaced as the exception message; the factory passes a
 * provider-specific reason (e.g. "OpenRouter does not support the Batch API").
 */
class OpenAI(
    override val endpoint: LlmEndpoint,
    private val batchUnsupportedReason: String =
        "Sync OpenAI client does not handle batch — construct OpenAIWithBatch instead"
) : LlmClient {

    private val apiKey: String get() = endpoint.apiKey
    private val model: String get() = endpoint.model
    private val chatCompletionsUrl: String = "${endpoint.baseUrl}/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        // No callTimeout: the per-call ceiling can fire mid-response on large JSON
        // payloads (Step-1 dedup extract). readTimeout already bounds stuck reads.
        // Do not follow redirects — prevents token leakage to a third-party host
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean = true
    )

    @Serializable
    private data class ResponseFormat(val type: String)

    @Serializable
    private data class ChatCompletionRequestJson(
        val model: String,
        val messages: List<Message>,
        val response_format: ResponseFormat,
        val stream: Boolean = false
    )

    @Serializable
    private data class Delta(val content: String? = null)

    @Serializable
    private data class StreamChoice(
        val delta: Delta,
        val index: Int,
        val finish_reason: String? = null
    )

    @Serializable
    private data class ChatCompletionChunk(
        val id: String,
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<StreamChoice>
    )


    @Serializable
    private data class NonStreamChoice(
        val message: Message,
        val index: Int,
        val finish_reason: String? = null
    )

    @Serializable
    private data class ChatCompletionResponse(
        val id: String,
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<NonStreamChoice>
    )

    @Serializable
    private data class ApiErrorEnvelope(val error: ApiError? = null)

    @Serializable
    private data class ApiError(
        val message: String? = null,
        val type: String? = null,
        val param: String? = null,
        val code: String? = null
    )

    private class OpenAIResponseException(
        message: String,
        val retryable: Boolean,
        cause: Throwable? = null
    ) : IOException(message, cause)

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

    /**
     * Sync render-mode call: same prompt that the Batch API would have produced for a Step 2
     * render request, served by the chat-completions endpoint. Used when the batch route is
     * unavailable (submission failure, queue backpressure) but the category is in dedup mode.
     * [articles] are used only to enrich the Step 2 shortlist JSON with prompt-only source
     * descriptions/titles; they are not persisted as part of the shortlist model.
     */
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

    /**
     * Simple sync chat completion: returns the model's text reply for a (system, user) pair.
     * Reuses the same retry/billing semantics as [summarizeArticles] / [summarizeShortlist].
     */
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

    private fun request(prompt: String, systemPromptOverride: String? = null): String {
        val systemPrompt = systemPromptOverride
            ?: ("Ти асистент для аналізу новин. Створюй стислі, інформативні дайджести українською мовою. " +
                    "Виділяй найважливіші події та тенденції. Використовуй markdown для форматування. " +
                    "Кожну тему починай із заголовку жирним шрифтом і відокремлюй теми рядком ---. " +
                    "Для кожної теми обов'язково вказуй посилання на джерела у форматі [назва](url) — лише з наданого списку. " +
                    "Якщо надано попередні дайджести, уникай повторення вже висвітленої інформації — " +
                    "згадуй попередні теми лише якщо є суттєво нові деталі або розвиток подій.")

        logger.info { "[LLM][sync] REQUEST | model=$model\n--- system ---\n$systemPrompt\n--- user ---\n$prompt" }

        val requestBody = ChatCompletionRequest(
            model = model,
            messages = listOf(
                Message(role = "system", content = systemPrompt),
                Message(role = "user", content = prompt)
            ),
            stream = true
        )

        val body = json.encodeToString(requestBody)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = buildRequest(body)

        val maxRetries = 5
        var attempt = 0
        var lastException: Exception? = null

        while (attempt <= maxRetries) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string()
                        throwOpenAIErrorIfPresent(responseBody, response.code)
                        throw IOException("Unexpected code $response\nBody: $responseBody")
                    }

                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response body")

                    val parsed = decodeChatCompletionResponse(responseBody)
                    val result = parsed.choices.firstOrNull()?.message?.content.orEmpty()
                    if (result.isBlank()) {
                        logger.warn { "Empty summary in response. Model=$model | raw: ${responseBody.take(500)}" }
                    }
                    logger.info { "[LLM][sync] RESPONSE | model=$model\n$result" }
                    return result
                }
            } catch (e: BillingException) {
                throw e  // non-retryable — fail immediately
            } catch (e: Exception) {
                if (e is OpenAIResponseException && !e.retryable) throw e
                lastException = e
                attempt++
                if (attempt > maxRetries) throw e
                logger.error(e) { "OpenAI attempt $attempt failed" }
                val waitSeconds = extractWaitTime(e.message)
                // NOTE: Thread.sleep here blocks the calling thread.
                // This method is only used in the fallback sync path (outside the scheduler thread).
                if (waitSeconds != null) {
                    Thread.sleep((waitSeconds + 60) * 1000L)
                } else {
                    Thread.sleep(60 * 1000L) // 1-minute wait between retries
                }
            }
        }

        throw lastException ?: IOException("Unknown error")
    }

    private fun buildRequest(body: RequestBody): Request {
        val builder = Request.Builder()
            .url(chatCompletionsUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
        for ((k, v) in endpoint.extraHeaders) builder.header(k, v)
        return builder.post(body).build()
    }

    private fun extractWaitTime(message: String?): Long? {
        val regex = "Please try again in (\\d+\\.?\\d*)s".toRegex()
        return regex.find(message ?: "")?.groups?.get(1)?.value?.toDoubleOrNull()?.toLong()
    }

    private fun decodeChatCompletionResponse(responseBody: String): ChatCompletionResponse {
        throwOpenAIErrorIfPresent(responseBody, statusCode = null)
        return try {
            json.decodeFromString<ChatCompletionResponse>(responseBody)
        } catch (e: SerializationException) {
            throw OpenAIResponseException(
                "Unexpected OpenAI chat completion response shape. Body: ${responseBody.take(500)}",
                retryable = false,
                cause = e
            )
        }
    }

    private fun throwOpenAIErrorIfPresent(responseBody: String?, statusCode: Int?) {
        if (responseBody == null) return
        if (isBillingError(responseBody)) {
            throw BillingException("OpenAI billing/quota limit reached: $responseBody")
        }

        val apiError = try {
            json.decodeFromString<ApiErrorEnvelope>(responseBody).error
        } catch (e: SerializationException) {
            null
        } ?: return

        throw OpenAIResponseException(
            message = formatOpenAIError(apiError, responseBody, statusCode),
            retryable = isRetryableOpenAIError(apiError, statusCode)
        )
    }

    private fun formatOpenAIError(apiError: ApiError, responseBody: String, statusCode: Int?): String {
        val details = listOfNotNull(
            statusCode?.let { "status=$it" },
            apiError.type?.let { "type=$it" },
            apiError.code?.let { "code=$it" },
            apiError.param?.let { "param=$it" },
            apiError.message?.let { "message=$it" }
        ).joinToString(", ")
        return "OpenAI error response${if (details.isBlank()) "" else ": $details"}. Body: ${responseBody.take(500)}"
    }

    private fun isRetryableOpenAIError(apiError: ApiError, statusCode: Int?): Boolean {
        if (statusCode != null) {
            return statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500
        }

        val markers = listOfNotNull(apiError.type, apiError.code)
            .map { it.lowercase() }
        return markers.any { marker ->
            marker.contains("rate_limit") ||
                    marker.contains("server_error") ||
                    marker.contains("service_unavailable") ||
                    marker.contains("temporarily_unavailable") ||
                    marker.contains("timeout")
        }
    }

    /**
     * Non-streaming chat completion with `response_format={type:"json_object"}`.
     * Used by the Step 1 event extractor; returns the raw content string of the first choice
     * (which is expected to be a JSON object — caller is responsible for parsing).
     *
     * Reuses the same retry semantics and [BillingException] handling as [request].
     * Blocks the calling thread on retries (same as [request]).
     */
    override fun completeJson(systemPrompt: String, userPrompt: String): String {
        val requestBody = ChatCompletionRequestJson(
            model = model,
            messages = listOf(
                Message(role = "system", content = systemPrompt),
                Message(role = "user", content = userPrompt)
            ),
            response_format = ResponseFormat(type = "json_object"),
            stream = false
        )

        logger.info { "[LLM][json] REQUEST | model=$model\n--- system ---\n$systemPrompt\n--- user ---\n$userPrompt" }

        val body = json.encodeToString(requestBody)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = buildRequest(body)

        val maxRetries = 5
        var attempt = 0
        var lastException: Exception? = null

        while (attempt <= maxRetries) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string()
                        throwOpenAIErrorIfPresent(responseBody, response.code)
                        throw IOException("Unexpected code $response\nBody: $responseBody")
                    }
                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response body")
                    val parsed = decodeChatCompletionResponse(responseBody)
                    val result = parsed.choices.firstOrNull()?.message?.content.orEmpty()
                    if (result.isBlank()) {
                        logger.warn { "Empty JSON response. Model=$model | raw: ${responseBody.take(500)}" }
                    }
                    logger.info { "[LLM][json] RESPONSE | model=$model\n$result" }
                    return result
                }
            } catch (e: BillingException) {
                throw e
            } catch (e: Exception) {
                if (e is OpenAIResponseException && !e.retryable) throw e
                lastException = e
                attempt++
                if (attempt > maxRetries) throw e
                logger.error(e) { "OpenAI completeJson attempt $attempt failed" }
                val waitSeconds = extractWaitTime(e.message)
                if (waitSeconds != null) Thread.sleep((waitSeconds + 60) * 1000L)
                else Thread.sleep(60 * 1000L)
            }
        }
        throw lastException ?: IOException("Unknown error")
    }

    override fun completeJson(systemPrompt: String, userPrompt: String, maxRetry: Int): String {
        var attempt = 0
        var lastException: Exception? = null
        while (attempt <= maxRetry) {
            try {
                val jsonString = completeJson(systemPrompt, userPrompt)
                Json.parseToJsonElement(jsonString)
                return jsonString
            } catch (e: BillingException) {
                throw e
            } catch (e: OpenAIResponseException) {
                if (!e.retryable) throw e
                lastException = e
                attempt++
                if (attempt > maxRetry) throw e
                logger.error(e) { "OpenAI completeJson attempt $attempt failed" }
                Thread.sleep(attempt * 20 * 1000L)
            } catch (e: Exception) {
                lastException = e
                attempt++
                if (attempt > maxRetry) throw e
                logger.error(e) { "OpenAI completeJson attempt $attempt failed" }
                Thread.sleep(attempt * 20 * 1000L)
            }
        }
        throw lastException ?: IOException("Unknown error")
    }

}
