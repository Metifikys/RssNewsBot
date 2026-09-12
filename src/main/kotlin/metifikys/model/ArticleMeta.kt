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
    val hn: HnMeta? = null
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

/** Hacker News story identity plus the points / comment counts seen in the hnrss feed at fetch time. */
@Serializable
data class HnMeta(
    val id: Long,
    val points: Int? = null,
    val comments: Int? = null
) {
    val discussionUrl: String get() = "https://news.ycombinator.com/item?id=$id"
}
