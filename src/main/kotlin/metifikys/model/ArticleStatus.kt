package metifikys.model

enum class ArticleStatus {
    UNPROCESSED,
    PROCESSING,
    PROCESSED,
    /**
     * Semantic-dedup detector rejected this article as a near-duplicate of a
     * previously-PROCESSED article (the canonical id is stored in `duplicate_of`).
     * DUPLICATE rows are excluded from the digest pipeline — the LLM never sees them.
     */
    DUPLICATE
}
