package metifikys.ai

import com.sun.net.httpserver.HttpServer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class OpenAITest {
    @Test
    fun `completeJson reports provider error envelope instead of parsing it as success`() {
        withChatCompletionServer(
            statusCode = 200,
            responseBody = """
                {
                  "error": {
                    "message": "The requested model was not found",
                    "type": "invalid_request_error",
                    "code": "model_not_found"
                  }
                }
            """.trimIndent()
        ) { baseUrl, requestCount ->
            val client = OpenAI(LlmEndpoint(baseUrl = baseUrl, apiKey = "test-key", model = "bad-model"))

            val error = assertFailsWith<IOException> {
                client.completeJson(systemPrompt = "system", userPrompt = "user", maxRetry = 3)
            }

            assertContains(error.message.orEmpty(), "OpenAI error response")
            assertContains(error.message.orEmpty(), "model_not_found")
            assertEquals(1, requestCount.get())
        }
    }

    @Test
    fun `completeJson does not retry billing errors returned in provider envelope`() {
        withChatCompletionServer(
            statusCode = 200,
            responseBody = """
                {
                  "error": {
                    "message": "You exceeded your current quota",
                    "type": "insufficient_quota",
                    "code": "insufficient_quota"
                  }
                }
            """.trimIndent()
        ) { baseUrl, requestCount ->
            val client = OpenAI(LlmEndpoint(baseUrl = baseUrl, apiKey = "test-key", model = "test-model"))

            assertFailsWith<BillingException> {
                client.completeJson(systemPrompt = "system", userPrompt = "user", maxRetry = 3)
            }
            assertEquals(1, requestCount.get())
        }
    }

    @Test
    fun `OpenRouter numeric error code on 404 is non-retryable and fails on the first attempt`() {
        // Real OpenRouter body from 2026-09-02 when openai/gpt-oss-120b:free left the free tier.
        // `code` is a bare number there; a String-typed field failed to decode and the 404 was
        // retried 5× a minute apart per article.
        withChatCompletionServer(
            statusCode = 404,
            responseBody = """
                {"error":{"message":"This model is unavailable for free. The paid version is available now - use this slug instead: openai/gpt-oss-120b","code":404},"user_id":"user_x"}
            """.trimIndent()
        ) { baseUrl, requestCount ->
            val client = OpenAI(LlmEndpoint(baseUrl = baseUrl, apiKey = "test-key", model = "openai/gpt-oss-120b:free"))

            val error = assertFailsWith<IOException> {
                client.completeJson(systemPrompt = "system", userPrompt = "user", maxRetry = 3)
            }

            assertContains(error.message.orEmpty(), "status=404")
            assertContains(error.message.orEmpty(), "code=404")
            assertEquals(1, requestCount.get())
        }
    }

    private fun withChatCompletionServer(
        statusCode: Int,
        responseBody: String,
        block: (baseUrl: String, requestCount: AtomicInteger) -> Unit
    ) {
        val requestCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requestCount.incrementAndGet()
            exchange.requestBody.close()
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1", requestCount)
        } finally {
            server.stop(0)
        }
    }
}
