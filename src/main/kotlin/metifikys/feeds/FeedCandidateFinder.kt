package metifikys.feeds

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.config.FeedCandidatesConfig
import metifikys.db.FeedCandidateCheck
import metifikys.db.FeedCandidateStatus
import metifikys.db.NewsDatabase
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

/**
 * Plan §24 v1 (`feedHealth.candidates`): on the maintenance tick, every `everyDays`, rank the
 * domains the channel published from without following them ([FeedCandidates]) and try feed
 * autodiscovery ([FeedDiscovery]) on the best few not checked in `recheckDays`. Results go to
 * `feed_candidates` and the log; the static report shows them as "Кандидати". Log-only: `feeds:`
 * is never touched. Network: at most `maxProbesPerRun` hosts and `maxRequestsPerRun` requests per
 * run. Never throws.
 */
class FeedCandidateFinder(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val discovery: FeedDiscovery,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    private val cfg: FeedCandidatesConfig = config.feedHealth.candidates

    fun runIfDue() {
        if (!cfg.enabled) return
        try {
            val now = clock()
            val last = db.getState(STATE_KEY)?.let {
                try { LocalDateTime.parse(it) } catch (e: DateTimeParseException) { null }
            }
            if (last != null && Duration.between(last, now) < Duration.ofDays(cfg.everyDays)) return
            run(now)
            db.setState(STATE_KEY, now.toString())
        } catch (e: Exception) {
            logger.warn(e) { "[FeedCandidates] run failed — retrying on the next maintenance tick" }
        }
    }

    /** One pass; returns how many hosts were probed. */
    internal fun run(now: LocalDateTime = clock()): Int {
        val since = now.minusDays(config.feedHealth.windowDays)
        val published = db.fetchPublishedLinkGroupsSince(since).flatten()
        val feedUrls = config.categories.values.flatMap { c -> c.feeds.map { it.url } }
        val ranked = FeedCandidates.rank(published, feedUrls, cfg.ignoreDomains, cfg.minPublished).take(MAX_TRACKED)
        db.upsertFeedCandidates(ranked, now)

        val known = db.fetchFeedCandidates().associateBy { it.domain }
        val recheckBefore = now.minusDays(cfg.recheckDays)
        val due = ranked.filter { c ->
            val row = known[c.domain]
            row == null || (row.status != FeedCandidateStatus.DISMISSED && (row.checkedAt == null || row.checkedAt < recheckBefore))
        }.take(cfg.maxProbesPerRun)

        val budget = FeedDiscovery.Budget(cfg.maxRequestsPerRun)
        var probed = 0
        for (c in due) {
            if (budget.requests <= 0) break
            probed++
            val check = when (val r = discovery.discover(c.host, budget)) {
                is FeedDiscovery.Result.Found -> {
                    val overlap = db.findExistingLinks(r.links).size
                    FeedCandidateCheck(
                        FeedCandidateStatus.FOUND, r.feedUrl, r.entries, r.itemsPerDay, overlap,
                        "$overlap of the newest ${r.links.size} links already came from other feeds"
                    )
                }
                is FeedDiscovery.Result.None ->
                    FeedCandidateCheck(FeedCandidateStatus.NONE, note = "no feed after ${r.tried} request(s) — via the converter?")
                is FeedDiscovery.Result.Error -> FeedCandidateCheck(FeedCandidateStatus.ERROR, note = r.reason)
            }
            db.recordFeedCandidateCheck(c.domain, check, now)
            logger.info {
                "[FeedCandidates] ${c.domain}: ${c.published} published in ${config.feedHealth.windowDays}d → ${check.status}" +
                    (check.feedUrl?.let { " $it (${check.entries} entries, overlap ${check.overlap})" } ?: check.note?.let { " ($it)" } ?: "")
            }
        }
        logger.info {
            "[FeedCandidates] ${ranked.size} candidate domain(s), $probed probed, " +
                "${cfg.maxRequestsPerRun - budget.requests} request(s) spent"
        }
        return probed
    }

    companion object {
        const val STATE_KEY = "feed_candidates:last_run"

        /** Candidate rows refreshed per run (the report shows them all). */
        const val MAX_TRACKED = 50
    }
}
