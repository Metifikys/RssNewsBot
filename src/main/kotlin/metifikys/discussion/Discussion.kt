package metifikys.discussion

import metifikys.model.Article

/**
 * A discussion thread about an article on a link aggregator (Hacker News today; Lobsters, Lemmy
 * later). [source] is the [DiscussionSource.id], [threadId] the source's own id, [url] the page
 * people read it on, [commentCount] the count known when the ref was made (null = unknown).
 */
data class DiscussionRef(
    val source: String,
    val threadId: String,
    val url: String,
    val commentCount: Int? = null
)

/**
 * One comment of a thread, already plain text. [replies] are in the source's "best first" order
 * (HN: the rank on the page, Lobsters: score). [score] when the source exposes one (HN does not).
 */
data class DiscussionComment(
    val id: String,
    val text: String,
    val score: Int? = null,
    val replies: List<DiscussionComment> = emptyList()
) {
    /** All comments under this one, at any depth. */
    val descendants: Int get() = replies.sumOf { 1 + it.descendants }
}

/** A fetched thread: the live comment count and the top-level comments, best first. */
data class DiscussionThread(
    val ref: DiscussionRef,
    /** Display name for the reply header, e.g. `HN`. */
    val sourceName: String,
    val commentCount: Int,
    val comments: List<DiscussionComment>
)

/**
 * A link aggregator whose comment threads can be summarized under a published post. Everything
 * past the adapter — comment selection, the prompt, the reply, the `discussions` table — is shared.
 */
interface DiscussionSource {
    val id: String

    /** The thread named by the articles' feed metadata (no HTTP), or null. */
    fun locate(articles: List<Article>): DiscussionRef?

    /** A thread about [articleUrl] found by searching the source (one HTTP call), or null. */
    fun lookupByUrl(articleUrl: String, maxAgeDays: Long): DiscussionRef? = null

    /** The thread with its comments best first, or null when it is gone. Throws on transport errors. */
    fun fetch(ref: DiscussionRef): DiscussionThread?
}
