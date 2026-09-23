package metifikys.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Source-specific signals captured at ingestion and persisted as `articles.meta_json`. The
 * pipeline never reads them for selection; they feed deterministic footer badges (HN points
 * and comments today). Every field is optional so the shape can grow without a migration.
 */
@Serializable
data class ArticleMeta(
    val hn: HnMeta? = null,
    /** Set for items of feeds with a `kind` or `repointLinks` (converted feeds); see [SourceMeta]. */
    val source: SourceMeta? = null
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Lenient: a malformed or empty value yields null rather than an exception. */
        fun parse(raw: String?): ArticleMeta? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString<ArticleMeta>(raw) }.getOrNull()
        }
    }
}

/**
 * Where a converted-feed item came from: the feed's `kind` and `label`, and — for a post repointed
 * at its one outbound link (`repointLinks`) — the post URL. [account] is the posting account
 * (`t.me/<channel>`, `x.com/<user>`) parsed from the post URL.
 */
@Serializable
data class SourceMeta(
    val kind: String? = null,
    val label: String? = null,
    val postUrl: String? = null,
    val account: String? = null
) {
    /**
     * The Step 1 hint for this item (`official account: Some Studio`), or null for kinds that need
     * none. English on purpose: it sits next to English JSON keys in the prompt.
     */
    fun promptHint(): String? {
        val who = label ?: account ?: return null
        return when (kind) {
            "social" -> "official account: $who"
            "release" -> "release feed: $who"
            "video" -> "video channel: $who"
            else -> null
        }
    }
}

/** Hacker News story identity plus the points / comment counts seen in the hnrss feed at fetch time. */
@Serializable
data class HnMeta(
    val id: Long,
    val points: Int? = null,
    val comments: Int? = null
) {
    val discussionUrl: String get() = "https://news.ycombinator.com/item?id=$id"
}
