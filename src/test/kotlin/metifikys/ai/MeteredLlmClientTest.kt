package metifikys.ai

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.db.NewsDatabase
import metifikys.model.Article
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class MeteredLlmClientTest {

    private val db: NewsDatabase = mockk(relaxed = true)
    private val recorder = LlmCallRecorder(db)

    private fun openAiEndpoint(model: String = "gpt-4o-mini") = LlmEndpoint(
        baseUrl = "https://api.openai.com/v1",
        apiKey = "k",
        model = model,
        provider = LlmEndpoint.Provider.OPENAI_COMPATIBLE
    )

    private fun openRouterEndpoint() = LlmEndpoint(
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "k",
        model = "router-model",
        provider = LlmEndpoint.Provider.OPENAI_COMPATIBLE
    )

    private fun anthropicEndpoint() = LlmEndpoint(
        baseUrl = "https://api.anthropic.com/v1",
        apiKey = "k",
        model = "claude-haiku-4-5",
        provider = LlmEndpoint.Provider.ANTHROPIC
    )

    private fun stubInner(endpoint: LlmEndpoint, completeReturn: String = "ok"): LlmClient {
        val inner = mockk<LlmClient>(relaxed = true)
        every { inner.endpoint } returns endpoint
        every { inner.complete(any(), any()) } returns completeReturn
        every { inner.completeJson(any(), any()) } returns completeReturn
        every { inner.completeJson(any(), any(), any()) } returns completeReturn
        every { inner.summarizeArticles(any(), any(), any(), any(), any(), any(), any()) } returns completeReturn
        every { inner.summarizeShortlist(any(), any(), any(), any(), any(), any()) } returns completeReturn
        return inner
    }

    @Test
    fun `complete records token estimates with chars div 4`() {
        val inner = stubInner(openAiEndpoint(), completeReturn = "abcdefgh") // 8 chars / 4 = 2
        val metered = MeteredLlmClient(inner, recorder, category = "Tech", useCase = LlmUseCase.RENDER)

        val out = metered.complete("sys-1234", "user-12345678") // 8 + 13 = 21 chars / 4 = 5

        assertEquals("abcdefgh", out)
        val tokensIn = slot<Int>(); val tokensOut = slot<Int>(); val duration = slot<Long?>()
        verify(exactly = 1) {
            db.insertLlmCall(
                provider = "openai",
                model = "gpt-4o-mini",
                category = "Tech",
                useCase = "RENDER",
                promptTokens = capture(tokensIn),
                completionTokens = capture(tokensOut),
                estCostUsd = any(),
                ts = any(),
                durationMs = captureNullable(duration)
            )
        }
        assertEquals(5, tokensIn.captured)
        assertEquals(2, tokensOut.captured)
        // Synchronous calls record a non-null latency.
        assertNotNull(duration.captured)
    }

    @Test
    fun `completeJson with retry overload also records once`() {
        val inner = stubInner(openAiEndpoint(), completeReturn = "12345678") // 2 tokens
        val metered = MeteredLlmClient(inner, recorder, category = null, useCase = LlmUseCase.EXTRACT)

        metered.completeJson("a", "b", maxRetry = 2) // 2 chars / 4 = 0

        verify(exactly = 1) {
            db.insertLlmCall(
                provider = "openai",
                model = "gpt-4o-mini",
                category = null,
                useCase = "EXTRACT",
                promptTokens = 0,
                completionTokens = 2,
                estCostUsd = any(),
                ts = any(),
                durationMs = any()
            )
        }
    }

    @Test
    fun `summarizeArticles tallies articles plus prompts`() {
        val inner = stubInner(openAiEndpoint(), completeReturn = "x")
        val metered = MeteredLlmClient(inner, recorder, category = "News", useCase = LlmUseCase.SUMMARIZE)

        val articles = listOf(
            Article(category = "News", title = "1234", link = "u1", description = "5678",
                pubDate = LocalDateTime.now()),
            Article(category = "News", title = "abcd", link = "u2", description = "efgh",
                pubDate = LocalDateTime.now())
        )

        metered.summarizeArticles(
            category = "News",        // 4
            emoji = "x",              // 1
            articles = articles,      // 2 * (4+4) = 16
            systemPrompt = "sys-",    // 4
            userPrompt = "user",      // 4
            previousSummaries = listOf("aa", "bb"), // 2+2 = 4
            maxArticles = 30
        )
        // total: 4+1+16+4+4+4 = 33 chars / 4 = 8
        verify(exactly = 1) {
            db.insertLlmCall(
                provider = "openai", model = "gpt-4o-mini",
                category = "News", useCase = "SUMMARIZE",
                promptTokens = 8, completionTokens = 0,
                estCostUsd = any(), ts = any(), durationMs = any()
            )
        }
    }

    @Test
    fun `submitExtractBatch records on future completion`() {
        val inner = mockk<LlmClient>(relaxed = true)
        every { inner.endpoint } returns openAiEndpoint("gpt-batch")
        val future = CompletableFuture.completedFuture("RESULT-789")  // 10 chars / 4 = 2
        every { inner.submitExtractBatch(any(), any(), any(), any()) } returns future

        val metered = MeteredLlmClient(inner, recorder, category = "Tech", useCase = LlmUseCase.EXTRACT)

        val f = metered.submitExtractBatch(
            category = "Cat",     // 3
            systemPrompt = "sys", // 3
            userPrompt = "us",    // 2
            articleLinks = "ll"   // 2
        )
        f.get()
        // 3+3+2+2 = 10 chars / 4 = 2
        val duration = slot<Long?>()
        verify(exactly = 1) {
            db.insertLlmCall(
                provider = "openai", model = "gpt-batch",
                category = "Tech", useCase = "EXTRACT",
                promptTokens = 2, completionTokens = 2,
                estCostUsd = any(), ts = any(), durationMs = captureNullable(duration)
            )
        }
        // Batch jobs record no latency (wall-clock is poll-wait, not model latency).
        assertNull(duration.captured)
    }

    @Test
    fun `recorder failure does not break the call`() {
        val inner = stubInner(openAiEndpoint(), completeReturn = "out")
        every { db.insertLlmCall(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("db down")
        val metered = MeteredLlmClient(inner, recorder, category = "T", useCase = LlmUseCase.RENDER)

        val result = metered.complete("hi", "there")
        assertEquals("out", result) // call still succeeds
    }

    @Test
    fun `provider key for openrouter base url is openrouter`() {
        val key = MeteredLlmClient.providerKeyOf(openRouterEndpoint())
        assertEquals("openrouter", key)
    }

    @Test
    fun `provider key for openai base url is openai`() {
        val key = MeteredLlmClient.providerKeyOf(openAiEndpoint())
        assertEquals("openai", key)
    }

    @Test
    fun `provider key for anthropic provider tag is anthropic`() {
        val key = MeteredLlmClient.providerKeyOf(anthropicEndpoint())
        assertEquals("anthropic", key)
    }

    @Test
    fun `inner endpoint is exposed via decorator`() {
        val inner = stubInner(openAiEndpoint())
        val metered = MeteredLlmClient(inner, recorder, category = null, useCase = LlmUseCase.RENDER)
        assertSame(inner.endpoint, metered.endpoint)
        assertEquals(false, metered.isBatchCapable)
    }
}
