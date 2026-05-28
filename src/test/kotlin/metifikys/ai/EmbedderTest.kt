package metifikys.ai

import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import metifikys.db.NewsDatabase
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbedderTest {

    @Test
    fun `embed parses response data preserving input order`() {
        val responseBody = """
            {
              "data": [
                {"index": 1, "embedding": [0.5, -0.5]},
                {"index": 0, "embedding": [0.1, 0.2]}
              ]
            }
        """.trimIndent()
        withEmbeddingsServer(statusCode = 200, responseBody = responseBody) { baseUrl, requests ->
            val embedder = Embedder(
                LlmEndpoint(baseUrl = baseUrl, apiKey = "k", model = "chat-model-ignored")
            )

            val result = embedder.embed(listOf("first", "second"), model = "text-embedding-3-small")

            assertEquals(2, result.size)
            // Sorted back into request order via `index`
            assertEquals(0.1f, result[0][0])
            assertEquals(0.2f, result[0][1])
            assertEquals(0.5f, result[1][0])
            assertEquals(-0.5f, result[1][1])
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun `embed throws BillingException on insufficient_quota`() {
        val responseBody = """
            {
              "error": {
                "message": "You exceeded your current quota",
                "type": "insufficient_quota",
                "code": "insufficient_quota"
              }
            }
        """.trimIndent()
        withEmbeddingsServer(statusCode = 200, responseBody = responseBody) { baseUrl, requests ->
            val embedder = Embedder(LlmEndpoint(baseUrl = baseUrl, apiKey = "k", model = "chat-model"))

            assertFailsWith<BillingException> {
                embedder.embed(listOf("hello"), model = "text-embedding-3-small")
            }
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun `embed records token usage on success when recorder is present`() {
        val responseBody = """
            {
              "data": [
                {"index": 0, "embedding": [0.0, 0.0, 0.0, 0.0]}
              ]
            }
        """.trimIndent()
        val db: NewsDatabase = mockk(relaxed = true)
        val recorder = LlmCallRecorder(db)
        withEmbeddingsServer(statusCode = 200, responseBody = responseBody) { baseUrl, _ ->
            val embedder = Embedder(
                // endpoint.model is the chat model — embed should record the embedding model passed at call time
                LlmEndpoint(baseUrl = baseUrl, apiKey = "k", model = "chat-model-ignored"),
                recorder = recorder
            )

            embedder.embed(listOf("abcd"), model = "text-embedding-3-small") // 4 chars / 4 = 1 token

            verify(exactly = 1) {
                db.insertLlmCall(
                    provider = any(),
                    model = "text-embedding-3-small",
                    category = null,
                    useCase = "EMBED",
                    promptTokens = 1,
                    completionTokens = 0,
                    estCostUsd = any(),
                    ts = any()
                )
            }
        }
    }

    @Test
    fun `embed surfaces non-retryable error envelope as IOException`() {
        val responseBody = """
            {
              "error": {
                "message": "model not found",
                "type": "invalid_request_error",
                "code": "model_not_found"
              }
            }
        """.trimIndent()
        withEmbeddingsServer(statusCode = 200, responseBody = responseBody) { baseUrl, requests ->
            val embedder = Embedder(LlmEndpoint(baseUrl = baseUrl, apiKey = "k", model = "chat-model"))

            val ex = assertFailsWith<IOException> {
                embedder.embed(listOf("x"), model = "bad-embed-model")
            }
            assertContains(ex.message.orEmpty(), "model_not_found")
            // Non-retryable: no retries past the first call
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun `embed returns empty list for empty input without hitting server`() {
        val embedder = Embedder(
            LlmEndpoint(baseUrl = "http://unused.invalid/v1", apiKey = "k", model = "chat-model")
        )
        val out = embedder.embed(emptyList(), model = "text-embedding-3-small")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `embed records nothing when recorder is null`() {
        val responseBody = """{"data":[{"index":0,"embedding":[1.0]}]}"""
        val db: NewsDatabase = mockk(relaxed = true)
        every { db.insertLlmCall(any(), any(), any(), any(), any(), any(), any(), any()) } just Runs

        withEmbeddingsServer(statusCode = 200, responseBody = responseBody) { baseUrl, _ ->
            val embedder = Embedder(
                LlmEndpoint(baseUrl = baseUrl, apiKey = "k", model = "chat-model"),
                recorder = null
            )
            embedder.embed(listOf("hi"), model = "text-embedding-3-small")
        }
        verify(exactly = 0) { db.insertLlmCall(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * Live integration test against the real OpenAI `/v1/embeddings` endpoint.
     *
     * Disabled by default — enable manually when verifying that the project's
     * OPENAI_API_KEY can actually generate embeddings (the production bug that
     * motivated this test was a 403 "You are not allowed to generate embeddings
     * from this model" caused by accidentally sending a chat model id).
     *
     * Verifies three things:
     *  1. The endpoint accepts `text-embedding-3-small` with the configured key.
     *  2. The returned vectors have the documented 1536 dimensions.
     *  3. Two near-duplicate sentences produce a cosine similarity ≥ 0.8 — proves
     *     the embeddings are meaningful and round-trip through L2-normalize + cosine.
     */
    @Test
    @org.junit.jupiter.api.Disabled(
        "Live integration: hits real OpenAI /v1/embeddings. Enable manually after setting " +
            "OPENAI_API_KEY (env var) or providing it via -DOPENAI_API_KEY=..."
    )
    fun `INTEGRATION text-embedding-3-small against live OpenAI`() {
        val apiKey = System.getenv("OPENAI_API_KEY")
            ?: System.getProperty("OPENAI_API_KEY")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !apiKey.isNullOrBlank(),
            "Skipping: OPENAI_API_KEY env var not set"
        )

        val embedder = Embedder(
            metifikys.ai.LlmEndpoint(
                baseUrl = "https://api.openai.com/v1",
                apiKey = apiKey!!,
                model = "chat-model-ignored" // endpoint model is unused for embeddings
            )
        )

        val texts = listOf(
            "Apple announced a new iPhone today at its annual launch event.",
            "Apple unveiled the latest iPhone at today's yearly product launch.",
            "Heavy rainfall flooded several streets in central London overnight."
        )

        val vectors = embedder.embed(texts, model = "text-embedding-3-small")

        assertEquals(3, vectors.size, "Expected 3 embedding vectors")
        for ((i, v) in vectors.withIndex()) {
            assertEquals(1536, v.size, "vector[$i] expected 1536 dims for text-embedding-3-small")
        }

        val nearDup = metifikys.digest.VectorMath.cosine(
            metifikys.digest.VectorMath.l2Normalize(vectors[0]),
            metifikys.digest.VectorMath.l2Normalize(vectors[1])
        )
        val unrelated = metifikys.digest.VectorMath.cosine(
            metifikys.digest.VectorMath.l2Normalize(vectors[0]),
            metifikys.digest.VectorMath.l2Normalize(vectors[2])
        )

        println("[INTEGRATION] nearDup cosine = $nearDup")
        println("[INTEGRATION] unrelated cosine = $unrelated")

        assertTrue(
            nearDup >= 0.8,
            "Near-duplicate sentences should produce cosine ≥ 0.8; got $nearDup"
        )
        assertTrue(
            nearDup > unrelated,
            "Near-duplicate cosine ($nearDup) must exceed unrelated cosine ($unrelated)"
        )
    }

    private fun withEmbeddingsServer(
        statusCode: Int,
        responseBody: String,
        block: (baseUrl: String, requestCount: AtomicInteger) -> Unit
    ) {
        val requestCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/embeddings") { exchange ->
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
