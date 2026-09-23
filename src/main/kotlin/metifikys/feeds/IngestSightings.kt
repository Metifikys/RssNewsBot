package metifikys.feeds

import metifikys.db.FeedSighting
import metifikys.model.Article
import java.time.LocalDateTime

/**
 * Layer 1 (link dedup) split of one ingest pass, keeping what the unique-link insert used to
 * throw away: which OTHER feed also brought a link.
 *
 *  - [fresh]: links not in the DB yet, one article per link (the first feed in fetch order wins —
 *    a second copy would only be enriched / summarized for `insertIgnore` to drop it);
 *  - [sightings]: a link another feed already brought, either in an earlier pass (the stored
 *    `feed_url` differs) or earlier in this same pass. A feed re-reading its own links is not one.
 */
object IngestSightings {

    data class Split(val fresh: List<Article>, val sightings: List<FeedSighting>)

    /** [existing] = stored `feed_url` by link for the links already in the DB (null feed = pre-column row). */
    fun split(raw: List<Article>, existing: Map<String, String?>, now: LocalDateTime = LocalDateTime.now()): Split {
        val fresh = LinkedHashMap<String, Article>()
        val sightings = LinkedHashMap<Pair<String, String>, FeedSighting>()
        fun sight(a: Article, firstFeed: String?) {
            val feed = a.feedUrl ?: return
            if (firstFeed == null || firstFeed == feed) return
            sightings.putIfAbsent(a.link to feed, FeedSighting(a.link, feed, a.category, now))
        }
        for (a in raw) {
            if (a.link in existing) {
                sight(a, existing[a.link])
                continue
            }
            val first = fresh[a.link]
            if (first == null) fresh[a.link] = a else sight(a, first.feedUrl)
        }
        return Split(fresh.values.toList(), sightings.values.toList())
    }
}
