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
    val status: String
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
    val articleIndices: List<Int> = emptyList()
)

/** Full Step 1 output. */
@Serializable
data class ExtractionResult(
    val extractions: List<ExtractionItem> = emptyList(),
    val shortlist: List<ShortlistItem> = emptyList()
)
