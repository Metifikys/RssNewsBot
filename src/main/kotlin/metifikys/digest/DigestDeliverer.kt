package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.format.TopicFormatter
import metifikys.model.Article
import metifikys.model.ShortlistItem
import metifikys.telegram.SentRef
import metifikys.telegram.TelegramSender
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * Renders an LLM summary into Telegram-ready topics, posts them to the category channel,
 * and reconciles per-article status (`PROCESSED` / `UNPROCESSED`) against partial-send outcomes.
 */
class DigestDeliverer(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val sender: TelegramSender
) {

    /**
     * Delivers a summary for a single category to Telegram and marks articles as processed.
     * [articles] is optional — if null, ready articles are fetched fresh from DB.
     */
    fun deliver(
        categoryName: String,
        summary: String,
        articles: List<Article>? = null,
        shortlist: List<ShortlistItem>? = null
    ) {
        val resolvedArticles = articles
            ?: db.fetchReadyForDigestByCategory(config.processing.staleTimeoutHours)[categoryName]
            ?: return
        val categoryConfig = config.categories[categoryName] ?: return
        val channelId = categoryConfig.channelId

        val previousUrls = db.fetchRecentSummaries(categoryName, config.summaryHistory.maxCount)
            .flatMap { TopicFormatter.extractUrls(it.summary) }
            .toSet()

        if (summary.isBlank()) {
            logger.warn { "[Category:$categoryName] LLM returned empty summary — skipping delivery, reverting articles." }
            db.markUnprocessed(resolvedArticles.map { it.link })
            return
        }

        logger.debug { "[Category:$categoryName]$channelId previousUrls: $previousUrls" }
        logger.debug { "[Category:$categoryName]$channelId summary: $summary" }

        val articleByLink = resolvedArticles.associateBy { it.link }
        val allArticleLinks = resolvedArticles.map { it.link }.toSet()

        val topics = TopicFormatter.splitTopics(summary)
            .map { TopicFormatter.replaceSourceLabel(it, articleByLink) }
            .map { TopicFormatter.applyStrictLayout(it) }
            .filter { topic ->
                val topicUrls = TopicFormatter.extractUrls(topic)
                // BUG-011: every URL must be in the current article set.
                // Drops prompt-injection payloads that coax the LLM into emitting attacker URLs.
                val foreignUrls = topicUrls - allArticleLinks
                if (foreignUrls.isNotEmpty()) {
                    logger.warn {
                        "[Category:$categoryName] Dropping topic with non-whitelisted URL(s) $foreignUrls " +
                            "— possible prompt injection."
                    }
                    return@filter false
                }
                topicUrls.isEmpty() || topicUrls.any { it !in previousUrls }
            }

        logger.debug { "[Category:$categoryName]$channelId $topics" }

        if (topics.isEmpty()) {
            logger.warn { "Category '$categoryName': all topics filtered as duplicates from previous summaries." }
            db.markProcessed(resolvedArticles.map { it.link })
            return
        }

        if (categoryConfig.enableImages) {
            val articlesWithImages = resolvedArticles.count { it.imageUrl != null }
            logger.info {
                "[Image:$categoryName] enableImages=true, ${resolvedArticles.size} articles in scope, " +
                    "$articlesWithImages have imageUrl."
            }
        }

        val topicUrls: List<Set<String>> = topics.map { TopicFormatter.extractUrls(it) }
        val referencedLinks = topicUrls.flatten().toSet() intersect allArticleLinks

        // Maps a topic's article URL back to the Step 1 event it rendered, so each delivered
        // message can be tagged with its event_key for later reaction attribution. Empty for
        // legacy single-step categories (no shortlist).
        val eventKeyByUrl: Map<String, String> = shortlist?.associate { it.url to it.eventKey } ?: emptyMap()

        val sentTopics = mutableListOf<String>()
        val failedTopicIdx = mutableListOf<Int>()
        val digestMessageRows = mutableListOf<DigestMessageRow>()
        val sentAt = LocalDateTime.now()

        for ((idx, topic) in topics.withIndex()) {
            val image = if (categoryConfig.enableImages) {
                val urls = TopicFormatter.extractUrls(topic)
                val matched = urls.map { url -> url to articleByLink[url] }
                val firstImage = matched.firstNotNullOfOrNull { (_, art) -> art?.imageUrl }
                if (firstImage == null) {
                    val diag = matched.joinToString(", ") { (url, art) ->
                        when {
                            art == null -> "$url=NO_MATCH"
                            art.imageUrl == null -> "$url=NULL_IMG"
                            else -> "$url=${art.imageUrl}"
                        }
                    }
                    logger.warn { "[Image:$categoryName] no image for topic; lookup: [$diag]" }
                } else {
                    logger.debug { "[Image:$categoryName] using image $firstImage for topic" }
                }
                firstImage
            } else null
            val refs: List<SentRef> = if (image != null) {
                // Single post: photo + caption. Caption is truncated to Telegram's 1024-char
                // limit inside TelegramSender (with markdown→plain-text fallback on parse errors).
                listOfNotNull(sender.sendPhotoToChannel(channelId, image, caption = topic))
            } else {
                sender.sendToChannel(channelId, topic, disablePreview = true)
            }
            if (refs.isNotEmpty()) {
                sentTopics += topic
                val topicLinks = (topicUrls[idx] intersect allArticleLinks)
                val eventKey = topicUrls[idx].firstNotNullOfOrNull { eventKeyByUrl[it] }
                val linksJson = topicLinks.joinToString("\n")
                // One digest_messages row per Telegram message (a chunked topic yields several,
                // all sharing the same event and article links).
                for (ref in refs) {
                    digestMessageRows += DigestMessageRow(
                        chatId = ref.chatId,
                        messageId = ref.messageId,
                        category = categoryName,
                        eventKey = eventKey,
                        articleLinks = linksJson,
                        sentAt = sentAt
                    )
                }
            } else {
                failedTopicIdx += idx
            }
        }

        // Persist message→event links for every message that was sent, regardless of whether
        // sibling topics failed — a delivered message collects reactions on its own.
        if (digestMessageRows.isNotEmpty()) db.insertDigestMessages(digestMessageRows)

        when {
            sentTopics.isEmpty() -> {
                // Total failure — revert all chunk articles for a clean retry next cycle.
                db.markUnprocessed(resolvedArticles.map { it.link })
                logger.warn { "Telegram send failed for '$categoryName' (0/${topics.size} topics). Will retry next cycle." }
            }
            failedTopicIdx.isEmpty() -> {
                db.markProcessed(resolvedArticles.map { it.link })
                // BUG-011: persist only filtered topics, never the raw summary —
                // otherwise attacker URLs dropped by the whitelist filter would still
                // reach `previousUrls` on the next cycle.
                db.saveSummary(categoryName, sentTopics.joinToString("\n• ", prefix = "• "), sentTopics.size)
                persistCoveredEvents(categoryName, shortlist)
                logger.info { "Category '$categoryName' digest sent (${topics.size} topic(s)) and marked processed." }
            }
            else -> {
                // Partial success — commit sent topics, keep failed topic's links UNPROCESSED for retry.
                // Success wins on overlap: a link that appears in both a sent and a failed topic
                // is marked PROCESSED (re-sending would be the exact duplicate this fix targets;
                // the failed topic's retry is filtered by previousUrls on the next run).
                val successLinks = sentTopics.flatMap { TopicFormatter.extractUrls(it) }.toSet() intersect allArticleLinks
                val failedLinks = failedTopicIdx.flatMap { topicUrls[it] }.toSet() intersect allArticleLinks
                val orphanLinks = allArticleLinks - referencedLinks

                val toProcess = successLinks + orphanLinks
                val toUnprocess = failedLinks - successLinks

                if (toProcess.isNotEmpty()) db.markProcessed(toProcess.toList())
                if (toUnprocess.isNotEmpty()) db.markUnprocessed(toUnprocess.toList())

                val partialSummary = sentTopics.joinToString("\n• ", prefix = "• ")
                db.saveSummary(categoryName, partialSummary, sentTopics.size)
                persistCoveredEvents(categoryName, shortlist)

                logger.warn {
                    "Category '$categoryName' partial: ${sentTopics.size}/${topics.size} sent, " +
                        "${toUnprocess.size} link(s) kept UNPROCESSED for retry, ${toProcess.size} marked PROCESSED."
                }
            }
        }
    }

    private fun persistCoveredEvents(categoryName: String, shortlist: List<ShortlistItem>?) {
        if (shortlist.isNullOrEmpty()) return
        val now = LocalDateTime.now()
        val rows = shortlist.map {
            CoveredEventRow(
                category = categoryName,
                eventKey = it.eventKey,
                subject = it.subject,
                franchise = it.franchise,
                eventType = it.eventType,
                coreFact = it.coreFact,
                importance = it.importance,
                newsworthiness = it.newsworthiness,
                digestFit = it.digestFit,
                url = it.url,
                coveredAt = now
            )
        }
        db.insertCoveredEvents(rows)
        logger.info { "[Category:$categoryName][Dedup] persisted ${rows.size} events" }
    }
}
