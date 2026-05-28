package metifikys.model

/**
 * Input data for a single category passed to the batch API.
 *
 * Two modes:
 * - Legacy (shortlist == null): the batch LLM receives raw [articles] + [previousSummaries]
 *   and returns a dedup-and-render digest in one shot.
 * - Render-only (shortlist != null): Step 1 already deduped; the batch LLM is given the
 *   [shortlist] plus prompt-only per-article context derived from [articles] via
 *   [renderUserPrompt] and only renders. [previousSummaries] and [userPrompt] are ignored
 *   in this mode.
 */
data class CategoryInput(
    val emoji: String,
    val articles: List<Article>,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val previousSummaries: List<String> = emptyList(),
    val shortlist: List<ShortlistItem>? = null,
    val renderSystemPrompt: String? = null,
    val renderUserPrompt: String? = null
)
