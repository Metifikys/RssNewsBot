package metifikys.discussion

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import metifikys.enrich.BadgeHttp
import metifikys.model.Article
import metifikys.model.HnMeta
import metifikys.model.HtmlText
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Hacker News threads, no key needed:
 * - the order: the official Firebase API (`/v0/item/<id>.json`) lists a story's `kids` in the
 *   order the page shows them — HN's own ranking, the only one there is (comment scores are not
 *   public); the replies of the first [rankReplies] top-level comments are ordered the same way;
 * - the texts: one Algolia call (`/api/v1/items/<id>`) returns the whole tree, so a 400-comment
 *   thread costs 2 + [rankReplies] requests instead of 400;
 * - [lookupByUrl]: Algolia's search restricted to the story URL.
 * Deleted and dead comments (no author or no text) are left out, their replies with them.
 */
class HackerNewsDiscussionSource(
    private val http: BadgeHttp,
    private val firebaseBaseUrl: String = "https://hacker-news.firebaseio.com",
    private val algoliaBaseUrl: String = "https://hn.algolia.com",
    private val rankReplies: Int = 10,
    private val now: () -> Instant = Instant::now
) : DiscussionSource {

    override val id: String = ID

    companion object {
        const val ID = "hn"
        const val NAME = "HN"

        fun refOf(hn: HnMeta): DiscussionRef = DiscussionRef(ID, hn.id.toString(), hn.discussionUrl, hn.comments)

        /** Scheme, `www.`, fragment and a trailing slash do not tell two article URLs apart. */
        internal fun urlKey(url: String): String? = try {
            val u = URI(url.trim())
            val host = u.host?.lowercase()?.removePrefix("www.") ?: return null
            val path = (u.rawPath ?: "").trimEnd('/')
            host + path + (u.rawQuery?.let { "?$it" } ?: "")
        } catch (e: Exception) {
            null
        }
    }

    override fun locate(articles: List<Article>): DiscussionRef? =
        articles.firstNotNullOfOrNull { it.meta()?.hn }?.let(::refOf)

    override fun lookupByUrl(articleUrl: String, maxAgeDays: Long): DiscussionRef? {
        val key = urlKey(articleUrl) ?: return null
        val q = URLEncoder.encode(articleUrl, StandardCharsets.UTF_8)
        val json = http.getJson("$algoliaBaseUrl/api/v1/search?query=$q&restrictSearchableAttributes=url&tags=story&hitsPerPage=10")
            ?: return null
        val since = now().minus(maxAgeDays, ChronoUnit.DAYS).epochSecond
        val hit = ((json as? JsonObject)?.get("hits") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { h -> h.str("url")?.let(::urlKey) == key && (h.long("created_at_i") ?: 0L) >= since }
            .maxByOrNull { it.int("num_comments") ?: 0 }
            ?: return null
        val id = hit.str("objectID")?.toLongOrNull() ?: return null
        return refOf(HnMeta(id, points = hit.int("points"), comments = hit.int("num_comments")))
    }

    override fun fetch(ref: DiscussionRef): DiscussionThread? {
        val story = http.getJson("$firebaseBaseUrl/v0/item/${ref.threadId}.json") as? JsonObject ?: return null
        if (story.bool("deleted") || story.bool("dead")) return null
        val count = story.int("descendants") ?: ref.commentCount ?: 0
        val kids = story.ids("kids")
        if (kids.isEmpty()) return DiscussionThread(ref, NAME, count, emptyList())

        val tree = http.getJson("$algoliaBaseUrl/api/v1/items/${ref.threadId}") as? JsonObject ?: return null
        val byId = (tree["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .associateBy { it.long("id") }
        var ranked = 0
        val comments = kids.mapNotNull { kidId ->
            val node = byId[kidId] ?: return@mapNotNull null
            // Only the comments that can make the cut get their replies ranked: one request each.
            val replyOrder = if (ranked < rankReplies && (node["children"] as? JsonArray).orEmpty().isNotEmpty()) {
                ranked++
                replyRank(kidId)
            } else null
            toComment(node, replyOrder)
        }
        return DiscussionThread(ref, NAME, count, comments)
    }

    /** The comment's `kids` in page order; null (= Algolia's order) when Firebase does not answer. */
    private fun replyRank(commentId: Long): List<Long>? = try {
        (http.getJson("$firebaseBaseUrl/v0/item/$commentId.json") as? JsonObject)?.ids("kids")
    } catch (e: Exception) {
        logger.debug { "[Discussion:hn] reply order of $commentId unavailable: ${e.message}" }
        null
    }

    private fun toComment(node: JsonObject, replyOrder: List<Long>?): DiscussionComment? {
        val text = node.str("text")?.let(HtmlText::strip)?.trim().orEmpty()
        if (node.str("author") == null || text.isEmpty()) return null
        val children = (node["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val ordered = if (replyOrder == null) children else {
            val pos = replyOrder.withIndex().associate { (i, id) -> id to i }
            children.sortedBy { pos[it.long("id")] ?: Int.MAX_VALUE }
        }
        return DiscussionComment(
            id = node.long("id")?.toString() ?: "",
            text = text,
            replies = ordered.mapNotNull { toComment(it, null) }
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean = str(key) == "true"
    private fun JsonObject.ids(key: String): List<Long> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
}
