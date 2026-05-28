package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Sync Anthropic Messages API client. Mirrors [OpenAI] in retry/timeout/billing
 * semantics so the choice of provider does not change editorial output.
 *
 * Wire shape:
 *   POST {baseUrl}/messages
 *   Headers: x-api-key, anthropic-version (from extraHeaders), content-type
 *   Body:    { model, max_tokens, system, messages: [{role:"user", content:...}] }
 *
 * Batch methods throw [UnsupportedOperationException] — use [AnthropicWithBatch]
 * when batch capability is required.
 */
class Anthropic(
    override val endpoint: LlmEndpoint,
    private val maxTokens: Int,
    private val batchUnsupportedReason: String =
        "Anthropic sync client does not handle batch — construct AnthropicWithBatch instead"
) : LlmClient {

    private val apiKey: String get() = endpoint.apiKey
    private val model: String get() = endpoint.model
    private val messagesUrl: String = "${endpoint.baseUrl}/messages"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class UserMessage(val role: String = "user", val content: String)

    @Serializable
    private data class MessagesRequest(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<UserMessage>
    )

    @Serializable
    private data class ResponseBlock(val type: String, val text: String? = null)

    @Serializable
    private data class MessagesResponse(
        val id: String? = null,
        val type: String? = null,
        val role: String? = null,
        val model: String? = null,
        val content: List<ResponseBlock> = emptyList(),
        val stop_reason: String? = null
    )

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
     * Anthropic has no native JSON-mode toggle (unlike OpenAI's `response_format`).
     * We append a strict instruction asking for raw JSON only, then strip any code
     * fences from the response. Caller is responsible for parsing.
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
                logger.error(e) { "Anthropic completeJson attempt $attempt failed" }
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

        logger.info { "[LLM][sync][anthropic] REQUEST | model=$model\n--- system ---\n$systemPrompt\n--- user ---\n$prompt" }

        val payload = MessagesRequest(
            model = model,
            max_tokens = maxTokens,
            system = systemPrompt,
            messages = listOf(UserMessage(content = prompt))
        )

        val body = json.encodeToString(payload)
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
                        if (isBillingError(responseBody)) {
                            throw BillingException("Anthropic billing/quota limit reached: $responseBody")
                        }
                        throw IOException("Unexpected code $response\nBody: $responseBody")
                    }

                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response body")

                    val parsed = json.decodeFromString<MessagesResponse>(responseBody)
                    val result = parsed.content
                        .firstOrNull { it.type == "text" }
                        ?.text
                        .orEmpty()
                    if (result.isBlank()) {
                        logger.warn { "Empty Anthropic response. Model=$model | raw: ${responseBody.take(500)}" }
                    }
                    logger.info { "[LLM][sync][anthropic] RESPONSE | model=$model\n$result" }
                    return result
                }
            } catch (e: BillingException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                attempt++
                if (attempt > maxRetries) throw e
                logger.error(e) { "Anthropic attempt $attempt failed" }
                val waitSeconds = extractWaitTime(e.message)
                if (waitSeconds != null) Thread.sleep((waitSeconds + 60) * 1000L)
                else Thread.sleep(60 * 1000L)
            }
        }

        throw lastException ?: IOException("Unknown error")
    }

    private fun buildRequest(body: RequestBody): Request {
        val builder = Request.Builder()
            .url(messagesUrl)
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
        for ((k, v) in endpoint.extraHeaders) builder.header(k, v)
        return builder.post(body).build()
    }

    private fun extractWaitTime(message: String?): Long? {
        val regex = "Please try again in (\\d+\\.?\\d*)s".toRegex()
        return regex.find(message ?: "")?.groups?.get(1)?.value?.toDoubleOrNull()?.toLong()
    }
}
