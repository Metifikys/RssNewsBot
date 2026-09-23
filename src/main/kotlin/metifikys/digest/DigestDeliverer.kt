package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.config.PostConfig
import metifikys.config.ThreadsConfig
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.enrich.BadgeResolver
import metifikys.enrich.WikipediaIntroProvider
import metifikys.format.Hashtags
import metifikys.format.TopicDecorator
import metifikys.format.TopicFormatter
import metifikys.model.Article
import metifikys.model.ShortlistItem
import metifikys.telegram.ModeratedPost
import metifikys.telegram.ModerationNotifier
import metifikys.telegram.SentRef
import metifikys.telegram.TelegramLinks
import metifikys.telegram.TelegramSender
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

private val THREAD_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** Any `http(s)://` occurrence. In a well-formed topic every URL lives inside a Markdown link. */
private val BARE_URL_SCHEME = Regex("""https?://""", RegexOption.IGNORE_CASE)

/**
 * Renders an LLM summary into Telegram-ready topics, posts them to the category channel,
 * and reconciles per-article status (`PROCESSED` / `UNPROCESSED`) against partial-send outcomes.
 */
class DigestDeliverer(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val sender: TelegramSender,
    /** Live-data footer badges; null when no category enables any (`post.badges`). */
    private val badgeResolver: BadgeResolver? = null,
    /** First-mention Wikipedia intro; null when no category enables it (`post.intro`). */
    private val introProvider: WikipediaIntroProvider? = null,
    /** Living tracker messages; null when no category configures `trackers`. */
    private val trackerUpdater: TrackerUpdater? = null,
    /** Moderation messages in the admin chat (`admin.moderation`); null when off. */
    private val moderationNotifier: ModerationNotifier? = null
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

        // A topic is *valid* only if it carries at least one whitelisted (in-set) article URL.
        // `hadValidTopic` distinguishes the two ways `topics` can end up empty: dropped as
        // duplicates of a prior digest (valid, already delivered → PROCESSED) vs. no valid
        // topic at all (nothing was ever delivered → UNPROCESSED for retry). See BUG-003.
        var hadValidTopic = false
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
                // BUG-011 (bare-URL bypass): extractUrls only sees Markdown [label](url) links, so an
                // injected *bare* URL (https://evil.example with no markdown) slips past the whitelist
                // above. Strip the markdown links, then reject any remaining http(s):// in the topic.
                if (BARE_URL_SCHEME.containsMatchIn(TopicFormatter.MARKDOWN_LINK.replace(topic, " "))) {
                    logger.warn {
                        "[Category:$categoryName] Dropping topic with a bare (non-markdown) URL — possible prompt injection."
                    }
                    return@filter false
                }
                // BUG-003: a digest bullet with no source link is malformed — drop it rather
                // than publish a linkless topic (the old code let these through).
                if (topicUrls.isEmpty()) {
                    logger.warn { "[Category:$categoryName] Dropping topic with no source URL (BUG-003)." }
                    return@filter false
                }
                hadValidTopic = true
                topicUrls.any { it !in previousUrls }
            }

        logger.debug { "[Category:$categoryName]$channelId $topics" }

        if (topics.isEmpty()) {
            if (hadValidTopic) {
                logger.warn { "Category '$categoryName': all topics filtered as duplicates from previous summaries." }
                db.markProcessed(resolvedArticles.map { it.link })
            } else {
                // BUG-003: every topic was invalid (no URL / prompt-injected) — nothing was
                // delivered, so keep the articles UNPROCESSED for a clean retry next cycle
                // instead of silently marking them PROCESSED.
                logger.warn { "Category '$categoryName': no valid topics (all missing/foreign URLs) — reverting articles for retry." }
                db.markUnprocessed(resolvedArticles.map { it.link })
            }
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
        val itemByUrl: Map<String, ShortlistItem> = shortlist?.associateBy { it.url } ?: emptyMap()
        val eventKeyByUrl: Map<String, String> = itemByUrl.mapValues { (_, item) -> item.eventKey }
        val postCfg = categoryConfig.post
        val replyTargets = resolveReplyTargets(categoryName, channelId, shortlist, postCfg?.threads)
        // One badge time budget per delivered digest, shared by all its bullets; one intro cap too.
        val badgeDeadline = badgeResolver?.newDeadline() ?: 0L
        val introBudget = IntroBudget(postCfg?.intro?.maxPerDigest ?: 0)

        val sentTopics = mutableListOf<String>()
        val failedTopicIdx = mutableListOf<Int>()
        val digestMessageRows = mutableListOf<DigestMessageRow>()
        val moderated = mutableListOf<ModeratedPost>()
        val moderate = moderationNotifier?.appliesTo(categoryName) == true
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
            // The Step 1 item this topic rendered (null on the legacy path). Drives the reply
            // target and the footer; the clean `topic` is what gets persisted below.
            val item = topicUrls[idx].firstNotNullOfOrNull { itemByUrl[it] }
            val eventKey = item?.eventKey
            val replyTo = eventKey?.let { replyTargets[it] }
            val topicArticles = topicUrls[idx].mapNotNull { articleByLink[it] }
            val footer = buildFooter(categoryName, item, topicArticles, postCfg, badgeDeadline, introBudget)
            // Long-form (post.longForm): a text message with the image as a large link preview
            // above it, so neither the body nor the footer is squeezed into the 1024-char caption.
            // A long post that fits a caption anyway still goes out as a normal photo post.
            val longOutgoing = if (item?.isLongForm == true && image != null) {
                TopicDecorator.decorate(topic, footer, TopicDecorator.TEXT_BUDGET)
                    .takeIf { it.length > TopicDecorator.CAPTION_BUDGET }
            } else null
            val budget = if (image != null) TopicDecorator.CAPTION_BUDGET else TopicDecorator.TEXT_BUDGET
            val outgoing = longOutgoing ?: TopicDecorator.decorate(topic, footer, budget)
            val refs: List<SentRef> = if (longOutgoing != null) {
                logger.info { "[Category:$categoryName][LongForm] $eventKey: ${outgoing.length} chars as text + image preview" }
                sender.sendToChannel(channelId, outgoing, disablePreview = true, replyToMessageId = replyTo, previewImageUrl = image)
            } else if (image != null) {
                // Single post: photo + caption. Caption is truncated to Telegram's 1024-char
                // limit inside TelegramSender (with markdown→plain-text fallback on parse errors).
                // A photo Telegram cannot fetch ("failed to get HTTP URL content") must not fail
                // the topic — it used to revert the articles and re-render them every cycle — so
                // the bullet goes out as a text message instead.
                val photoRef = sender.sendPhotoToChannel(channelId, image, caption = outgoing, replyToMessageId = replyTo)
                if (photoRef != null) {
                    listOf(photoRef)
                } else {
                    logger.warn { "[Image:$categoryName] photo post failed for $image — sending the bullet as text" }
                    sender.sendToChannel(channelId, outgoing, disablePreview = true, replyToMessageId = replyTo)
                }
            } else {
                sender.sendToChannel(channelId, outgoing, disablePreview = true, replyToMessageId = replyTo)
            }
            if (refs.isNotEmpty()) {
                sentTopics += topic
                val topicLinks = (topicUrls[idx] intersect allArticleLinks)
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
                if (moderate) {
                    val first = refs.first()
                    moderated += ModeratedPost(
                        category = categoryName,
                        chatId = first.chatId,
                        messageId = first.messageId,
                        eventKey = eventKey,
                        title = ModerationNotifier.titleOf(topic),
                        muteKeyword = item?.let { ModerationNotifier.muteKeywordFor(it.franchise, it.subject) },
                        url = TelegramLinks.messageUrl(channelId, first.chatId, first.messageId)
                    )
                }
            } else {
                failedTopicIdx += idx
            }
        }

        // Persist message→event links for every message that was sent, regardless of whether
        // sibling topics failed — a delivered message collects reactions on its own.
        if (digestMessageRows.isNotEmpty()) {
            db.insertDigestMessages(digestMessageRows)
            // Trackers see exactly the events that reached the channel.
            if (trackerUpdater != null && shortlist != null) {
                val sentKeys = digestMessageRows.mapNotNull { it.eventKey }.toSet()
                trackerUpdater.afterDigest(categoryName, shortlist.filter { it.eventKey in sentKeys }, digestMessageRows)
            }
            // After the channel sends: the admin-chat messages go out on the notifier's own thread.
            moderationNotifier?.afterDigest(categoryName, moderated)
        }

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

                // An event whose topic failed to send must NOT be recorded as covered: its
                // articles are deliberately kept UNPROCESSED for retry, but a covered_events
                // row makes Step 1 classify that retry as a duplicate and drop it — so the
                // event would silently never reach the channel. Same "success wins on overlap"
                // policy as the link reconciliation above.
                val sentEventKeys = (topics.indices - failedTopicIdx.toSet())
                    .flatMap { topicUrls[it] }.mapNotNull { eventKeyByUrl[it] }.toSet()
                val unsentEventKeys = failedTopicIdx
                    .flatMap { topicUrls[it] }.mapNotNull { eventKeyByUrl[it] }.toSet() - sentEventKeys
                persistCoveredEvents(categoryName, shortlist?.filterNot { it.eventKey in unsentEventKeys })

                logger.warn {
                    "Category '$categoryName' partial: ${sentTopics.size}/${topics.size} sent, " +
                        "${toUnprocess.size} link(s) kept UNPROCESSED for retry, ${toProcess.size} marked PROCESSED" +
                        (if (unsentEventKeys.isEmpty()) "." else ", ${unsentEventKeys.size} event(s) left uncovered for retry: $unsentEventKeys")
                }
            }
        }
    }

    /** Per-delivery counter for the intro cap (`post.intro.maxPerDigest`). */
    private class IntroBudget(var remaining: Int)

    /**
     * Footer lines for one bullet, in display order (most valuable first — [TopicDecorator]
     * drops from the end when the message budget is tight): live-data badges, the first-mention
     * intro, then the hashtag line. Lookups run under their time budgets and never throw.
     */
    private fun buildFooter(
        categoryName: String,
        item: ShortlistItem?,
        topicArticles: List<Article>,
        post: PostConfig?,
        badgeDeadline: Long,
        introBudget: IntroBudget
    ): List<String> {
        if (post == null) return emptyList()
        val lines = mutableListOf<String>()
        val enabledBadges = post.badges?.enabledIds.orEmpty()
        if (badgeResolver != null && enabledBadges.isNotEmpty()) {
            lines += badgeResolver.resolve(enabledBadges, item, topicArticles, badgeDeadline, "[Category:$categoryName]")
        }
        val introCfg = post.intro
        if (introProvider != null && introCfg != null && introCfg.enabled && item != null && introBudget.remaining > 0) {
            val line = try {
                introProvider.intro(categoryName, item, introCfg)
            } catch (e: Exception) {
                logger.warn(e) { "[Category:$categoryName][Intro] failed — sending without the intro" }
                null
            }
            if (line != null) {
                lines += line
                introBudget.remaining--
            }
        }
        val tags = Hashtags.forItem(item, post.hashtags)
        if (tags.isNotEmpty()) lines += tags.joinToString(" ")
        if (lines.isNotEmpty()) logger.debug { "[Category:$categoryName][Post] footer: $lines" }
        return lines
    }

    /**
     * Reply threading: for every `meaningful_update` item, the message id of the latest post in
     * this channel about the same story — looked up in `digest_messages` by the item's
     * `relatedPreviousEventKey` AND its own `eventKey` (Step 1 sometimes reuses the key for an
     * update, sometimes mints a new one and points `related` at the old). One query per digest;
     * any DB failure logs and yields no replies (fail-open, like Layer 3.5). Returns eventKey →
     * message id.
     */
    private fun resolveReplyTargets(
        category: String,
        channelId: String,
        shortlist: List<ShortlistItem>?,
        threads: ThreadsConfig?
    ): Map<String, Long> {
        if (threads == null || !threads.enabled || shortlist.isNullOrEmpty()) return emptyMap()
        val updates = shortlist.filter { it.status == "meaningful_update" }
        if (updates.isEmpty()) return emptyMap()
        val keys = updates.flatMap { listOfNotNull(it.relatedPreviousEventKey, it.eventKey) }.toSet()
        val since = LocalDateTime.now().minusDays(threads.maxAgeDays)
        val rows = try {
            db.fetchLatestDigestMessagesByEventKeys(category, keys, since)
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][Thread] previous-post lookup failed — sending without replies" }
            return emptyMap()
        }
        val channelNumeric = channelId.toLongOrNull()
        val out = LinkedHashMap<String, Long>()
        for (item in updates) {
            val previous = listOfNotNull(item.relatedPreviousEventKey, item.eventKey)
                .mapNotNull { rows[it] }
                .filter { channelNumeric == null || it.chatId == channelNumeric }
                .maxByOrNull { it.sentAt }
            if (previous == null) {
                logger.info {
                    "[Category:$category][Thread] no previous post for ${item.eventKey} " +
                        "(related=${item.relatedPreviousEventKey}, ${rows.size} row(s) found for ${keys.size} key(s))"
                }
                continue
            }
            out[item.eventKey] = previous.messageId
            logger.info {
                "[Category:$category][Thread] ${item.eventKey} → reply to msg ${previous.messageId} " +
                    "(sent ${previous.sentAt.format(THREAD_TS)})"
            }
        }
        return out
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
