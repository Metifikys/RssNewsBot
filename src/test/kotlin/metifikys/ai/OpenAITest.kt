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
