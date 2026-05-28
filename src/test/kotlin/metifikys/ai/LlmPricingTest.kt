package metifikys.ai

import metifikys.config.LlmPriceEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmPricingTest {

    private fun pricing(vararg entries: LlmPriceEntry) = LlmPricing(entries.toList())

    @Test
    fun `known model returns positive cost`() {
        val p = pricing(LlmPriceEntry("openai", "gpt-4o-mini", input = 0.15, output = 0.60))
        // 1M input @ $0.15 + 0.5M output @ $0.60 = 0.15 + 0.30 = 0.45
        assertEquals(0.45, p.estimateUsd("openai", "gpt-4o-mini", 1_000_000, 500_000), 1e-9)
    }

    @Test
    fun `unknown provider model returns zero`() {
        val p = pricing(LlmPriceEntry("openai", "gpt-4o-mini", 0.15, 0.60))
        assertEquals(0.0, p.estimateUsd("nobody", "made-up-model", 1000, 500), 1e-9)
    }

    @Test
    fun `empty pricing catalog returns zero for everything`() {
        val p = LlmPricing()
        assertEquals(0.0, p.estimateUsd("openai", "gpt-4o-mini", 1000, 500), 1e-9)
    }

    @Test
    fun `zero tokens returns zero even for known model`() {
        val p = pricing(LlmPriceEntry("openai", "gpt-4o-mini", 0.15, 0.60))
        assertEquals(0.0, p.estimateUsd("openai", "gpt-4o-mini", 0, 0), 1e-9)
    }

    @Test
    fun `cost scales linearly with token counts`() {
        val p = pricing(LlmPriceEntry("anthropic", "claude-haiku-4-5", 1.0, 5.0))
        val cost1m = p.estimateUsd("anthropic", "claude-haiku-4-5", 1_000_000, 0)
        val cost2m = p.estimateUsd("anthropic", "claude-haiku-4-5", 2_000_000, 0)
        assertTrue(cost1m > 0.0)
        assertEquals(2 * cost1m, cost2m, 1e-9)
    }

    @Test
    fun `last entry wins for duplicate provider model pairs`() {
        val p = pricing(
            LlmPriceEntry("openai", "gpt-4o-mini", 1.0, 2.0),
            LlmPriceEntry("openai", "gpt-4o-mini", 0.1, 0.2)
        )
        // 1M input @ 0.1 per 1M = 0.1
        assertEquals(0.1, p.estimateUsd("openai", "gpt-4o-mini", 1_000_000, 0), 1e-9)
    }

    @Test
    fun `prices use per-million-tokens divisor`() {
        // User's real config: gpt-5.4-2026-03-05 input=2.50, output=15.00 per 1M.
        // 100k input + 10k output = 0.250 + 0.150 = 0.400.
        val p = pricing(LlmPriceEntry("openai", "gpt-5.4-2026-03-05", input = 2.50, output = 15.00))
        assertEquals(0.40, p.estimateUsd("openai", "gpt-5.4-2026-03-05", 100_000, 10_000), 1e-9)
    }

    @Test
    fun `batch flag halves the estimated cost`() {
        val p = pricing(LlmPriceEntry("openai", "gpt-5.4-mini-2026-03-17", input = 0.75, output = 4.50))
        val sync = p.estimateUsd("openai", "gpt-5.4-mini-2026-03-17", 100_000, 10_000, isBatch = false)
        val batch = p.estimateUsd("openai", "gpt-5.4-mini-2026-03-17", 100_000, 10_000, isBatch = true)
        assertTrue(sync > 0.0)
        assertEquals(sync / 2.0, batch, 1e-9)
    }
}
