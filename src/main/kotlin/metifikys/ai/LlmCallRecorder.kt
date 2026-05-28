package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.db.NewsDatabase
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * Persists one row per LLM call with its token estimates and an estimated USD cost
 * resolved via [LlmPricing]. Failures are logged and swallowed — metering must
 * never break a digest cycle.
 */
class LlmCallRecorder(
    private val db: NewsDatabase,
    private val pricing: LlmPricing = LlmPricing()
) {

    fun record(
        provider: String,
        model: String,
        category: String?,
        useCase: LlmUseCase?,
        promptTokens: Int,
        completionTokens: Int,
        isBatch: Boolean = false,
        ts: LocalDateTime = LocalDateTime.now()
    ) {
        try {
            val cost = pricing.estimateUsd(provider, model, promptTokens, completionTokens, isBatch)
            db.insertLlmCall(
                provider = provider,
                model = model,
                category = category,
                useCase = useCase?.name,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                estCostUsd = cost,
                ts = ts
            )
        } catch (e: Exception) {
            logger.warn(e) {
                "Failed to record LLM call (provider=$provider model=$model category=$category " +
                    "useCase=$useCase tokensIn=$promptTokens tokensOut=$completionTokens)"
            }
        }
    }
}
