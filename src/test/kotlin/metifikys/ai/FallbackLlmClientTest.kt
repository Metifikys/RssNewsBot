package metifikys.ai

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.db.NewsDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FallbackLlmClientTest {

    private fun endpoint(
        baseUrl: String,
        model: String,
        provider: LlmEndpoint.Provider = LlmEndpoint.Provider.OPENAI_COMPATIBLE
    ) = LlmEndpoint(baseUrl = baseUrl, apiKey = "k", model = model, provider = provider)

    private fun client(endpoint: LlmEndpoint): LlmClient {
        val c = mockk<LlmClient>(relaxed = true)
        every { c.endpoint } returns endpoint
        return c
    }

    private val primaryEp = endpoint("https://api.openai.com/v1", "primary-model")
    private val fallbackEp = endpoint("codex-cli", "fallback-model", LlmEndpoint.Provider.CODEX_CLI)

    @Test
    fun `primary success returns primary result and never calls fallback`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.complete(any(), any()) } returns "primary-ok"

        val fb = FallbackLlmClient(primary, fallback)

        assertEquals("primary-ok", fb.complete("s", "u"))
        verify(exactly = 0) { fallback.complete(any(), any()) }
    }

    @Test
    fun `BillingException on primary fails over to fallback`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.summarizeShortlist(any(), any(), any(), any(), any(), any()) } throws BillingException("usage limit")
        every { fallback.summarizeShortlist(any(), any(), any(), any(), any(), any()) } returns "fallback-ok"

        val fb = FallbackLlmClient(primary, fallback)

        val out = fb.summarizeShortlist("c", "e", emptyList(), emptyList(), "sys", "tmpl")
        assertEquals("fallback-ok", out)
        verify(exactly = 1) { fallback.summarizeShortlist(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `exhausted-retry IOException on primary fails over to fallback`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.complete(any(), any()) } throws IOException("timed out")
        every { fallback.complete(any(), any()) } returns "fallback-ok"

        val fb = FallbackLlmClient(primary, fallback)

        assertEquals("fallback-ok", fb.complete("s", "u"))
    }

    @Test
    fun `NonRetryableCliException on primary fails over to fallback`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.completeJson(any(), any()) } throws NonRetryableCliException("model not found")
        every { fallback.completeJson(any(), any()) } returns "fallback-json"

        val fb = FallbackLlmClient(primary, fallback)

        assertEquals("fallback-json", fb.completeJson("s", "u"))
    }

    @Test
    fun `UnsupportedOperationException is rethrown and never fails over`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.complete(any(), any()) } throws UnsupportedOperationException("batch on sync client")

        val fb = FallbackLlmClient(primary, fallback)

        assertThrows<UnsupportedOperationException> { fb.complete("s", "u") }
        verify(exactly = 0) { fallback.complete(any(), any()) }
    }

    @Test
    fun `InterruptedException restores the interrupt flag and never fails over`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.complete(any(), any()) } throws InterruptedException("cancelled")

        val fb = FallbackLlmClient(primary, fallback)

        assertThrows<InterruptedException> { fb.complete("s", "u") }
        // Thread.interrupted() returns the flag AND clears it, leaving the thread clean for later tests.
        assertTrue(Thread.interrupted(), "interrupt flag should have been restored before rethrow")
        verify(exactly = 0) { fallback.complete(any(), any()) }
    }

    @Test
    fun `completeJson with maxRetry gives primary the full budget and the fallback zero`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        val primaryRetry = slot<Int>()
        val fallbackRetry = slot<Int>()
        every { primary.completeJson(any(), any(), capture(primaryRetry)) } throws IOException("boom")
        every { fallback.completeJson(any(), any(), capture(fallbackRetry)) } returns "fallback-json"

        val fb = FallbackLlmClient(primary, fallback)

        val out = fb.completeJson("s", "u", maxRetry = 3)
        assertEquals("fallback-json", out)
        assertEquals(3, primaryRetry.captured)
        assertEquals(0, fallbackRetry.captured)
    }

    @Test
    fun `when both legs fail the fallback exception propagates`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.complete(any(), any()) } throws IOException("primary down")
        every { fallback.complete(any(), any()) } throws BillingException("fallback also out")

        val fb = FallbackLlmClient(primary, fallback)

        val ex = assertThrows<BillingException> { fb.complete("s", "u") }
        assertTrue(ex.message?.contains("fallback also out") == true)
    }

    @Test
    fun `batch methods delegate to primary only without failover`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.resumeBatch(any()) } throws UnsupportedOperationException("no batch")

        val fb = FallbackLlmClient(primary, fallback)

        assertThrows<UnsupportedOperationException> { fb.resumeBatch("id") }
        verify(exactly = 0) { fallback.resumeBatch(any()) }
    }

    @Test
    fun `endpoint and isBatchCapable delegate to primary`() {
        val primary = client(primaryEp)
        val fallback = client(fallbackEp)
        every { primary.isBatchCapable } returns false

        val fb = FallbackLlmClient(primary, fallback)

        assertSame(primaryEp, fb.endpoint)
        assertFalse(fb.isBatchCapable)
    }

    @Test
    fun `constructor rejects identical primary and fallback`() {
        val primary = client(primaryEp)
        assertThrows<IllegalArgumentException> { FallbackLlmClient(primary, primary) }
    }

    @Test
    fun `metering attributes a failed-over call to the fallback provider`() {
        val db: NewsDatabase = mockk(relaxed = true)
        val recorder = LlmCallRecorder(db)

        val primaryInner = client(primaryEp)
        val fallbackInner = client(fallbackEp)
        every { primaryInner.complete(any(), any()) } throws IOException("down")
        every { fallbackInner.complete(any(), any()) } returns "served-by-fallback"

        // Mirror the factory: meter each leg, THEN wrap.
        val meteredPrimary = MeteredLlmClient(primaryInner, recorder, category = "Tech", useCase = LlmUseCase.RENDER)
        val meteredFallback = MeteredLlmClient(fallbackInner, recorder, category = "Tech", useCase = LlmUseCase.RENDER)
        val fb = FallbackLlmClient(meteredPrimary, meteredFallback)

        val out = fb.complete("sys", "user")
        assertEquals("served-by-fallback", out)

        // Primary threw before recording; only the fallback leg records, tagged codexcli/fallback-model.
        verify(exactly = 1) {
            db.insertLlmCall(
                provider = "codexcli",
                model = "fallback-model",
                category = "Tech",
                useCase = "RENDER",
                promptTokens = any(),
                completionTokens = any(),
                estCostUsd = any(),
                ts = any(),
                durationMs = any()
            )
        }
        verify(exactly = 0) {
            db.insertLlmCall(
                provider = "openai",
                model = "primary-model",
                category = any(),
                useCase = any(),
                promptTokens = any(),
                completionTokens = any(),
                estCostUsd = any(),
                ts = any(),
                durationMs = any()
            )
        }
    }
}
