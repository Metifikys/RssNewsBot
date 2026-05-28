package metifikys.ai

import metifikys.config.LlmPriceEntry

/**
 * Per-million-tokens price for an LLM. Costs are in USD; inputs and outputs
 * are billed separately on every provider we use. Mirrors how OpenAI / Anthropic
 * publish their pricing pages.
 */
data class PricePerMillion(val input: Double, val output: Double)

/**
 * Cost-estimation table sourced from the YAML config (`pricing:` block).
 * Pairs not in the catalog resolve to $0 — the row is still persisted with
 * token counts, so a missing-price entry shows up as a zero cost in `/status`.
 *
 * The catalog is intentionally config-driven rather than hardcoded: provider
 * prices change frequently and we don't want to ship a code change just to
 * track a new model or rate cut.
 */
class LlmPricing(entries: List<LlmPriceEntry> = emptyList()) {

    private val table: Map<Pair<String, String>, PricePerMillion> =
        entries.associate { (it.provider to it.model) to PricePerMillion(it.input, it.output) }

    /**
     * Estimated USD cost for a call. Returns 0.0 when the (provider, model) pair
     * is not in the catalog or when both token counts are non-positive.
     *
     * When [isBatch] is true the result is halved, matching the OpenAI / Anthropic
     * batch-API 50%-off pricing tier. Apply only when the call really went through
     * a batch endpoint — sync calls billed at full rate must pass `false`.
     */
    fun estimateUsd(
        provider: String,
        model: String,
        promptTokens: Int,
        completionTokens: Int,
        isBatch: Boolean = false
    ): Double {
        if (promptTokens <= 0 && completionTokens <= 0) return 0.0
        val price = table[provider to model] ?: return 0.0
        val raw = promptTokens / 1_000_000.0 * price.input + completionTokens / 1_000_000.0 * price.output
        return if (isBatch) raw * 0.5 else raw
    }
}
