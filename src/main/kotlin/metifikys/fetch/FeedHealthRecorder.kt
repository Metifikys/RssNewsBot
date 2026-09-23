package metifikys.fetch

import metifikys.db.FeedStatsDelta
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-feed fetch outcomes (`feedHealth.enabled`), so [RssFetcher] stays free of the DB:
 * the fetcher reports ONE final outcome per feed per pass — a retry that later succeeds is a
 * success — and the maintenance tick [drain]s the accumulated deltas into `feed_stats`.
 *
 * A 304 is a healthy response (it counts as a success), not news; "last brought something new"
 * is read from `articles.fetched_at` instead of being tracked here.
 */
class FeedHealthRecorder(private val clock: () -> LocalDateTime = LocalDateTime::now) {

    private class State(val category: String) {
        var passes = 0
        var failures = 0
        var lastAttemptAt: LocalDateTime? = null
        var lastSuccessAt: LocalDateTime? = null
        /** Failures after the last success of this delta (or all of them when there was none). */
        var failuresSinceSuccess = 0
        var lastError: String? = null
        var lastEntries: Int? = null
    }

    private val states = ConcurrentHashMap<String, State>()

    /** A 200 that parsed into [entries] items. */
    fun onSuccess(url: String, category: String, entries: Int) = update(url, category) { now ->
        lastSuccessAt = now
        failuresSinceSuccess = 0
        lastEntries = entries
    }

    /** A 304: healthy, nothing new. */
    fun onNotModified(url: String, category: String) = update(url, category) { now ->
        lastSuccessAt = now
        failuresSinceSuccess = 0
    }

    /** The pass ended without a feed: HTTP error after the retries, parse failure, rejected URL. */
    fun onFailure(url: String, category: String, reason: String) = update(url, category) { _ ->
        failures++
        failuresSinceSuccess++
        lastError = reason.take(MAX_ERROR_CHARS)
    }

    private fun update(url: String, category: String, block: State.(LocalDateTime) -> Unit) {
        val state = states.computeIfAbsent(url) { State(category) }
        synchronized(state) {
            val now = clock()
            state.passes++
            state.lastAttemptAt = now
            state.block(now)
        }
    }

    /** Takes and resets everything recorded since the previous drain. */
    fun drain(): List<FeedStatsDelta> = states.keys.toList().mapNotNull { url ->
        val s = states.remove(url) ?: return@mapNotNull null
        synchronized(s) {
            FeedStatsDelta(
                feedUrl = url,
                category = s.category,
                passes = s.passes,
                failures = s.failures,
                lastAttemptAt = s.lastAttemptAt ?: return@mapNotNull null,
                lastSuccessAt = s.lastSuccessAt,
                failuresSinceSuccess = s.failuresSinceSuccess,
                lastError = s.lastError,
                lastEntries = s.lastEntries
            )
        }
    }

    private companion object {
        const val MAX_ERROR_CHARS = 300
    }
}
