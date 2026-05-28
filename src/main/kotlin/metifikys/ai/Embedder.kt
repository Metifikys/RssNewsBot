package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * OpenAI `/v1/embeddings` client. Mirrors [OpenAI]'s OkHttp + retry envelope
 * (5-min timeouts, no redirects, billing-aware error parsing) but speaks the
 * embeddings wire format instead of chat-completions.
 *
 * Recorded into [LlmCallRecorder] under [LlmUseCase.EMBED] when [recorder] is
 * non-null. Failures are surfaced as exceptions; the caller is responsible for
 * deciding whether to log-and-skip or propagate.
 */
class Embedder(
    val endpoint: LlmEndpoint,
    private val recorder: LlmCallRecorder? = null
) {

    private val embeddingsUrl: String = "${endpoint.baseUrl}/embeddings"

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
    private data class EmbeddingRequest(
        val model: String,
        val input: List<String>
    )

    @Serializable
    private data class EmbeddingItem(val embedding: List<Float>, val index: Int)

    @Serializable
    private data class EmbeddingResponse(val data: List<EmbeddingItem>)

    @Serializable
    private data class ApiErrorEnvelope(val error: ApiError? = null)

    @Serializable
    private data class ApiError(
        val message: String? = null,
        val type: String? = null,
        val code: String? = null
    )

    /** Provider tag used for cost recording (`openai` / `openrouter` / `anthropic`). */
    private val provider: String get() = MeteredLlmClient.providerKeyOf(endpoint)

    /**
     * Returns one [FloatArray] per input, in the same order as [texts].
     *
     * [model] is the embedding model id (e.g. `text-embedding-3-small`). The Embedder
     * is constructed with a generic [LlmEndpoint] whose `model` field carries the chat
     * model — embeddings deliberately accept the model per call so the same client
     * serves multiple categories that may have configured different embedding models.
     *
     * Retries up to [maxRetries] on transient failures (5xx, 429, IO errors).
     * Throws [BillingException] immediately on quota / billing rejection.
     * Throws [IOException] (or its subclasses) for unrecoverable errors.
     */
    fun embed(texts: List<String>, model: String, maxRetries: Int = 3): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val body = json.encodeToString(EmbeddingRequest(model = model, input = texts))
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val builder = Request.Builder()
            .url(embeddingsUrl)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Content-Type", "application/json")
        for ((k, v) in endpoint.extraHeaders) builder.header(k, v)
        val request = builder.post(body).build()

        var attempt = 0
        var lastException: Exception? = null
        while (attempt <= maxRetries) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string()
                        throwOpenAIErrorIfPresent(responseBody, response.code)
                        throw IOException("Unexpected code ${response.code}\nBody: ${responseBody?.take(500)}")
                    }
                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response body")
                    val parsed = decodeResponse(responseBody)
                    val out = parsed.data
                        .sortedBy { it.index }
                        .map { item -> FloatArray(item.embedding.size) { i -> item.embedding[i] } }

                    val promptChars = texts.sumOf { it.length }
                    recorder?.record(
                        provider = provider,
                        model = model,
                        category = null,
                        useCase = LlmUseCase.EMBED,
                        promptTokens = MeteredLlmClient.estimateTokens(promptChars),
                        completionTokens = 0
                    )
                    logger.info {
                        "[Embed] model=$model inputs=${texts.size} chars=$promptChars dim=${out.firstOrNull()?.size ?: 0}"
                    }
                    return out
                }
            } catch (e: BillingException) {
                throw e
            } catch (e: Exception) {
                if (e is NonRetryableEmbedException) throw e
                lastException = e
                attempt++
                if (attempt > maxRetries) throw e
                logger.warn(e) { "[Embed] attempt $attempt failed; retrying" }
                Thread.sleep(attempt * 30 * 1000L)
            }
        }
        throw lastException ?: IOException("Unknown error")
    }

    private fun decodeResponse(responseBody: String): EmbeddingResponse {
        throwOpenAIErrorIfPresent(responseBody, statusCode = null)
        return try {
            json.decodeFromString<EmbeddingResponse>(responseBody)
        } catch (e: SerializationException) {
            throw NonRetryableEmbedException(
                "Unexpected embeddings response shape. Body: ${responseBody.take(500)}",
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

        if (!isRetryable(apiError, statusCode)) {
            val details = listOfNotNull(
                statusCode?.let { "status=$it" },
                apiError.type?.let { "type=$it" },
                apiError.code?.let { "code=$it" },
                apiError.message?.let { "message=$it" }
            ).joinToString(", ")
            throw NonRetryableEmbedException(
                "OpenAI embeddings error${if (details.isBlank()) "" else ": $details"}. " +
                    "Body: ${responseBody.take(500)}"
            )
        }
        // retryable: surface as IOException so the loop retries
        throw IOException("Retryable embeddings error: $responseBody")
    }

    private fun isRetryable(apiError: ApiError, statusCode: Int?): Boolean {
        if (statusCode != null) {
            return statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500
        }
        val markers = listOfNotNull(apiError.type, apiError.code).map { it.lowercase() }
        return markers.any { m ->
            m.contains("rate_limit") ||
                m.contains("server_error") ||
                m.contains("service_unavailable") ||
                m.contains("temporarily_unavailable") ||
                m.contains("timeout")
        }
    }

    private class NonRetryableEmbedException(message: String, cause: Throwable? = null) :
        IOException(message, cause)
}
