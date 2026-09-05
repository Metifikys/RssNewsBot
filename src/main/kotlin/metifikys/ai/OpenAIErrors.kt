package metifikys.ai

/**
 * Thrown when an LLM provider rejects a request due to billing or quota limits.
 * These errors are non-retryable — no amount of waiting will fix them without
 * topping up the account balance.
 *
 * Markers that trigger this (substring match on the raw error body):
 *   - OpenAI:    `billing_hard_limit_reached`, `insufficient_quota`
 *   - Anthropic: `credit balance is too low`, `permission_error` referencing credits
 */
class BillingException(message: String) : Exception(message)

/**
 * Thrown when a CLI-backed LLM provider (`claude -p`, `codex exec`) fails in a way that
 * will recur on retry — an expired/again-needed login, an unknown or inaccessible model,
 * or a malformed request. Distinct from [BillingException] (usage/credit limits) but
 * handled the same way by the retry loops: surfaced immediately, never retried.
 */
class NonRetryableCliException(message: String) : Exception(message)

/**
 * Thrown when a CLI-backed LLM provider (`claude -p`, `codex exec`) hit its force-kill
 * ceiling ([timeoutSeconds]) without producing an answer. It is an [IOException] so callers
 * that only know about I/O failures still handle it, but the CLI retry loops treat it as
 * terminal: a call that already burned the full ceiling is not going to succeed by being
 * re-run immediately on the same input, and with the inner (per-call) and outer
 * (`completeJson(maxRetry)`) loops both retrying it, one oversized Step-1 prompt stalled a
 * category for 12 × 20 min ≈ 4 h (2026-09-04, tech, ~132 KB prompt) — and, with the cycle
 * barrier waiting for every category, the whole bot with it. Failing on the first timeout
 * lets Step 1 fall back to the legacy chunked path and the next cycle retry with fresh input.
 */
class CliTimeoutException(message: String) : java.io.IOException(message)

/** Substrings in error bodies that indicate a permanent billing/quota block. */
private val BILLING_ERROR_MARKERS = setOf(
    "billing_hard_limit_reached",
    "insufficient_quota",
    "credit balance is too low"
)

/**
 * Returns true if the raw JSON error body contains a known billing / quota marker
 * that cannot be resolved by retrying. Provider-agnostic: matches OpenAI, OpenRouter,
 * and Anthropic markers in a single set.
 */
fun isBillingError(body: String?): Boolean {
    if (body == null) return false
    return BILLING_ERROR_MARKERS.any { marker -> body.contains(marker) }
}
