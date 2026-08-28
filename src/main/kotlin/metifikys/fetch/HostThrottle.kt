package metifikys.fetch

import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-tuning per-host request gate for the RSS fetcher. Rate limits are a property of
 * the HOST, not the feed — reddit throttles the client, so five subreddit feeds spread
 * across five categories still share one budget. All feeds pointing at the same host
 * therefore coordinate through one state object:
 *
 *  - a 429 puts the whole host into a cooldown (server `Retry-After` when present, else
 *    an exponential per-host backoff), so ONE rate-limit response informs every queued
 *    feed for that host instead of each burning its own attempts to rediscover it;
 *  - a 429 also teaches the host a minimum spacing between requests, so the next cycle
 *    doesn't reopen with the same synchronized burst that earned the ban;
 *  - successes gently decay the spacing back toward zero, so a host that stops
 *    throttling gradually returns to full speed.
 *
 * Nothing to configure: hosts that never send a 429 pay zero cost (no spacing, no
 * cooldown), and state lives for the process lifetime, so what a host taught us carries
 * over into the next fetch cycle. Constructor knobs exist only for tests.
 */
class HostThrottle(
    /** Spacing adopted the first time a host 429s (~reddit's unauthenticated 10 req/min). */
    private val initialSpacingMs: Long = 6_000L,
    private val maxSpacingMs: Long = 60_000L,
    /** Cooldown after a first 429; doubles per consecutive 429 up to [maxCooldownMs]. */
    private val initialCooldownMs: Long = 30_000L,
    private val maxCooldownMs: Long = 300_000L,
    /** Clock, injectable for tests. */
    private val nanos: () -> Long = System::nanoTime
) {

    private class HostState {
        /** Earliest nanoTime the next request to this host may start (spacing reservation). */
        var nextFreeAtNanos = Long.MIN_VALUE
        /** NanoTime until which the host is off-limits after a 429. */
        var cooldownUntilNanos = Long.MIN_VALUE
        /** Learned minimum gap between requests; 0 until the host ever 429s. */
        var spacingMs = 0L
        /** 429 events since the last success; drives the cooldown ladder. */
        var consecutiveRateLimits = 0
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    /**
     * Asks permission to hit [host] right now. Returns 0 when the request may proceed —
     * the spacing slot is then RESERVED, so the caller must actually perform the request.
     * Returns a positive delay in milliseconds when the host is spacing-busy or cooling
     * down; nothing is reserved and the caller should re-acquire after the delay.
     */
    fun acquireDelayMs(host: String): Long {
        val state = hosts.computeIfAbsent(host) { HostState() }
        synchronized(state) {
            val now = nanos()
            val earliest = maxOf(state.nextFreeAtNanos, state.cooldownUntilNanos)
            if (earliest > now) return (earliest - now) / 1_000_000 + 1
            state.nextFreeAtNanos = now + state.spacingMs * 1_000_000
            return 0
        }
    }

    /**
     * Records a 429 from [host]: the whole host cools down for `max(Retry-After, backoff)`
     * where the backoff doubles per consecutive 429 event, and the host's request spacing
     * is introduced (or doubled). A 429 that lands while a cooldown is already active came
     * from a request in flight when the cooldown was set — the same rate-limit event — so
     * it doesn't climb the ladder again. Returns the cooldown remaining, in milliseconds.
     */
    fun onRateLimit(host: String, retryAfterMs: Long?): Long {
        val state = hosts.computeIfAbsent(host) { HostState() }
        synchronized(state) {
            val now = nanos()
            if (now < state.cooldownUntilNanos) {
                // Same event as the 429 that set the active cooldown: no escalation, but
                // still honor a server Retry-After that outlasts what we already planned.
                val retryAfterUntil = now + (retryAfterMs ?: 0L).coerceAtMost(maxCooldownMs) * 1_000_000
                state.cooldownUntilNanos = maxOf(state.cooldownUntilNanos, retryAfterUntil)
                return (state.cooldownUntilNanos - now) / 1_000_000
            }
            val backoffMs = (initialCooldownMs shl state.consecutiveRateLimits.coerceAtMost(20))
                .coerceAtMost(maxCooldownMs)
            state.consecutiveRateLimits++
            val cooldownMs = maxOf(retryAfterMs ?: 0L, backoffMs).coerceAtMost(maxCooldownMs)
            state.cooldownUntilNanos = now + cooldownMs * 1_000_000
            state.spacingMs = if (state.spacingMs == 0L) initialSpacingMs
                else (state.spacingMs * 2).coerceAtMost(maxSpacingMs)
            return cooldownMs
        }
    }

    /** Records a successful response from [host]: resets the cooldown ladder, decays the spacing. */
    fun onSuccess(host: String) {
        val state = hosts[host] ?: return
        synchronized(state) {
            state.consecutiveRateLimits = 0
            state.spacingMs = (state.spacingMs * SPACING_DECAY_NUM / SPACING_DECAY_DEN)
                .let { if (it < MIN_SPACING_MS) 0L else it }
        }
    }

    companion object {
        /** Per-success spacing decay: ×0.9 per successful request to the host. */
        private const val SPACING_DECAY_NUM = 9L
        private const val SPACING_DECAY_DEN = 10L

        /** Below this the learned spacing snaps back to zero (not worth the scheduling churn). */
        private const val MIN_SPACING_MS = 500L

        /**
         * Throttle key for a URL: lowercase authority (host:port), so `https://reddit.com/a` and
         * `https://reddit.com:443/b` share one budget. Falls back to the raw URL when unparseable.
         * Shared by the RSS fetcher and the article-page fetcher so both charge the same host state.
         */
        fun hostKey(url: String): String = try {
            val parsed = URL(url)
            val port = if (parsed.port == -1) parsed.defaultPort else parsed.port
            "${parsed.host.lowercase()}:$port"
        } catch (e: Exception) {
            url
        }
    }
}
