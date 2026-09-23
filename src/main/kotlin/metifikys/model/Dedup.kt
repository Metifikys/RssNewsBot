package metifikys.model

import kotlinx.serialization.Serializable

/**
 * Structured representation of a previously-covered event for Step 1 dedup context.
 * Serialized to JSON and inlined into the extract prompt as {{PREVIOUSLY_COVERED_EVENTS_JSON}}.
 */
@Serializable
data class CoveredEvent(
    val eventKey: String,
    val subject: String = "",
    val franchise: String = "",
    val eventType: String = "",
    val coreFact: String,
    val importance: Int = 0,
    val newsworthiness: Int = 0,
    val digestFit: Int = 0,
    val url: String = "",
    /** ISO-8601 timestamp (UTC or local — consumed only by the LLM). */
    val coveredAt: String
)

/**
 * Per-article dedup decision emitted by Step 1.
 * `articleIndex` is 0-based and refers to the input batch order.
 * `status` ∈ {"new", "duplicate", "meaningful_update", "rejected"}.
 */
@Serializable
data class ExtractionItem(
    val articleIndex: Int = -1,
    val eventKey: String,
    val subject: String = "",
    val franchise: String = "",
    val eventType: String = "",
    val coreFact: String,
    val importance: Int = 0,
    val newsworthiness: Int = 0,
    val digestFit: Int = 0,
    val relatedPreviousEventKey: String? = null,
    val url: String,
    val status: String,
    /**
     * The date the event is about (a release date, the new date after a delay), as Step 1 read
     * it from the article: `YYYY-MM-DD`, `YYYY-MM`, `YYYY-Qn` or `YYYY`; null when the article
     * states none. Feeds the trackers; validated against the article text before use.
     */
    val eventDate: String? = null
)

/**
 * An event selected by Step 1 to be rendered by Step 2. `status` is either "new"
 * or "meaningful_update" — the render prompt uses this to phrase updates vs new items.
 */
@Serializable
data class ShortlistItem(
    val eventKey: String,
    val subject: String = "",
    val franchise: String = "",
    val eventType: String = "",
    val coreFact: String,
    val importance: Int = 0,
    val newsworthiness: Int = 0,
    val digestFit: Int = 0,
    val relatedPreviousEventKey: String? = null,
    val url: String,
    val status: String,
    val articleIndices: List<Int> = emptyList(),
    /** See [ExtractionItem.eventDate]. */
    val eventDate: String? = null,
    /**
     * Set by code right before render, never by Step 1: [DEPTH_LONG] for the digest's long-form
     * events (see `post.longForm`), null for the usual short bullet. Travels with the shortlist
     * through a pending batch, so delivery knows which bullets are long.
     */
    val depth: String? = null
) {
    val isLongForm: Boolean get() = depth == DEPTH_LONG

    companion object {
        const val DEPTH_LONG = "long"
    }
}

/**
 * The news weight the ranker gates on: `newsworthiness` when Step 1 emitted it, else the older
 * single `importance` score (prompts written before the split only produce that one).
 */
val ShortlistItem.effectiveNewsworthiness: Int
    get() = if (newsworthiness > 0) newsworthiness else importance

/** Full Step 1 output. */
@Serializable
data class ExtractionResult(
    val extractions: List<ExtractionItem> = emptyList(),
    val shortlist: List<ShortlistItem> = emptyList()
)
