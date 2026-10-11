package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.dedup.AntiBubble
import metifikys.config.AlsoConfig
import metifikys.config.AntiBubbleConfig
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.ConflictLineConfig
import metifikys.config.LongFormTelegraphConfig
import metifikys.config.PostConfig
import metifikys.config.PostImagesConfig
import metifikys.config.StoryLinesConfig
import metifikys.config.ThreadsConfig
import metifikys.config.VaporwareLineConfig
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.discussion.DiscussionReplier
import metifikys.enrich.BadgeResolver
import metifikys.enrich.WikipediaIntroProvider
import metifikys.format.AlsoLine
import metifikys.format.FooterLines
import metifikys.format.FooterLines.Slot
import metifikys.format.Hashtags
import metifikys.format.RichHtml
import metifikys.format.RichPost
import metifikys.format.TopicDecorator
import metifikys.format.TopicFormatter
import metifikys.model.Article
import metifikys.model.ShortlistItem
import metifikys.story.ConflictDetector
import metifikys.story.EventHistoryRecorder
import metifikys.story.Genealogy
import metifikys.story.Hype
import metifikys.story.StoryClusterLoader
import metifikys.story.StoryLines
import metifikys.story.Vaporware
import metifikys.telegram.ModeratedPost
import metifikys.telegram.ModerationNotifier
import metifikys.telegram.SentRef
import metifikys.telegram.TelegramLinks
import metifikys.telegram.TelegramSender
import metifikys.telegraph.TelegraphPage
import metifikys.telegraph.TelegraphPublisher
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
    private val moderationNotifier: ModerationNotifier? = null,
    /** og:image-first picture choice (`post.images`); null when no category sets it. */
    private val postImages: PostImages? = null,
    /** telegra.ph pages for long posts (`post.longForm.telegraph`); null when no category sets it. */
    private val telegraph: TelegraphPublisher? = null,
    /** The editor persona's remark under the top event (`post.persona`); null when no category sets it. */
    private val personaWriter: PersonaWriter? = null,
    /** Sampled, log-only judge of the sent posts against their sources (`factCheck`); null when off. */
    private val factChecker: FactChecker? = null,
    /** Replies summing up the posts' discussion threads (`post.discussion`); null when no category sets it. */
    private val discussionReplier: DiscussionReplier? = null
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

        val prepared = prepareTopics(categoryName, summary, resolvedArticles, previousUrls)
        val topics = prepared.topics
        val hadValidTopic = prepared.hadValidTopic

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
        // Story lines (📢 / 🥇 / ⚖️): one cluster lookup per digest, before the send loop; eventKey → lines.
        val storyLinesByKey = resolveStoryLines(categoryName, shortlist, resolvedArticles, postCfg?.storyLines)
        // 🔗 the related post in another category's channel: one vector / digest_messages lookup per digest.
        val crossLinkByKey = crossLinks.resolve(categoryName, shortlist, postCfg?.crossLinks)
        // Feed url → its `label`, for the names in the 📰 also line.
        val feedLabels = categoryConfig.feeds.mapNotNull { f -> f.label?.let { f.url to it } }.toMap()

        val sentTopics = mutableListOf<String>()
        val failedTopicIdx = mutableListOf<Int>()
        val digestMessageRows = mutableListOf<DigestMessageRow>()
        val moderated = mutableListOf<ModeratedPost>()
        val moderate = moderationNotifier?.appliesTo(categoryName) == true
        // factCheck: the clean topic of every sent post with the sources its render saw.
        val checkFacts = factChecker?.appliesTo(categoryName) == true
        val factCheckPosts = mutableListOf<FactChecker.Post>()
        // post.discussion: every sent post, in digest order, with all the articles of its event.
        val discuss = discussionReplier?.appliesTo(categoryConfig) == true
        val discussionPosts = mutableListOf<DiscussionReplier.Post>()
        val sentAt = LocalDateTime.now()

        // post.images: one og:image / probe budget per delivered digest.
        val imagesCfg = postCfg?.images?.takeIf { it.enabled && categoryConfig.enableImages && postImages != null }
        val imageDeadline = imagesCfg?.let { postImages?.newDeadline(it) } ?: 0L

        // post.persona: the digest's top event (first in ranker order; never the anti-bubble pick).
        // Cleared once its topic got its remark, so the digest makes one LLM call at most.
        val personaCfg = postCfg?.persona?.takeIf { it.enabled }
        var personaKey: String? = if (personaCfg != null) shortlist?.firstOrNull { !it.isExplore }?.eventKey else null

        for ((idx, topic) in topics.withIndex()) {
            val image = if (imagesCfg != null && postImages != null) {
                chooseImage(categoryName, topicUrls[idx], topicUrls[idx].firstNotNullOfOrNull { itemByUrl[it] }, articleByLink, imagesCfg, imageDeadline)
            } else if (categoryConfig.enableImages) {
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
            val storyLines = eventKey?.let { storyLinesByKey[it] }.orEmpty()
            // 🧭 the anti-bubble pick (dedup.digest.antiBubble): marked in the footer, variant explore.
            val explore = item?.isExplore == true
            // 💬 the editor persona (post.persona) under the top event only: A/B arm + remark.
            val persona = if (personaCfg != null && personaWriter != null && item != null && item.eventKey == personaKey) {
                personaKey = null
                personaWriter.write(categoryName, categoryConfig, personaCfg, item, topic)
            } else null
            val footerLines = buildFooter(
                categoryName, item, topicArticles, postCfg, badgeDeadline, introBudget, storyLines, articleByLink, feedLabels,
                eventKey?.let { crossLinkByKey[it] },
                exploreLine = if (explore) antiBubbleLabel(categoryConfig) else null,
                personaLine = persona?.line
            )
            val footer = footerLines.build()
            val variant = if (explore) DigestMessageRow.VARIANT_EXPLORE else persona?.variant
            // post.longForm.rich: the long post as one rich message (no telegra.ph page); a refusal
            // falls through to the classic long post below.
            val richRef = if (postCfg?.longForm?.rich?.enabled == true && item?.isLongForm == true) {
                sendRichLongForm(categoryName, categoryConfig, item, topic, footerLines, image, articleByLink, feedLabels, replyTo)
            } else null
            // Long-form (post.longForm): a text message with the image as a large link preview
            // above it, so neither the body nor the footer is squeezed into the 1024-char caption.
            // A long post that fits a caption anyway still goes out as a normal photo post.
            // With post.longForm.telegraph the text also goes to a telegra.ph page, and the post
            // (a teaser, or the full text) carries the page as its preview — Telegram's ⚡ Instant
            // View; no page (any Telegraph failure) → the long post as above.
            val telegraphCfg = postCfg?.longForm?.telegraph?.takeIf { it.enabled && item?.isLongForm == true && richRef == null }
            val pageUrl = if (telegraphCfg != null && telegraph != null && item != null) {
                publishPage(categoryName, categoryConfig, item, topic, image, articleByLink, feedLabels)
            } else null
            val linkOnly = pageUrl != null && telegraphCfg?.mode == LongFormTelegraphConfig.MODE_LINK
            val longOutgoing = if (pageUrl != null && linkOnly) {
                // The page is the post: its Instant View card alone, no text, no link, no footer.
                TelegraphPage.linkOnly(pageUrl)
            } else if (pageUrl != null) {
                val body = if (telegraphCfg?.mode == LongFormTelegraphConfig.MODE_FULL) {
                    TelegraphPage.withReadMore(topic, pageUrl)
                } else {
                    TelegraphPage.teaser(topic, pageUrl)
                }
                TopicDecorator.decorate(body, footer, TopicDecorator.TEXT_BUDGET)
            } else if (item?.isLongForm == true && image != null && richRef == null) {
                TopicDecorator.decorate(topic, footer, TopicDecorator.TEXT_BUDGET)
                    .takeIf { it.length > TopicDecorator.CAPTION_BUDGET }
            } else null
            val budget = if (image != null) TopicDecorator.CAPTION_BUDGET else TopicDecorator.TEXT_BUDGET
            val outgoing = longOutgoing ?: TopicDecorator.decorate(topic, footer, budget)
            val refs: List<SentRef> = if (richRef != null) {
                listOf(richRef)
            } else if (longOutgoing != null) {
                val preview = pageUrl ?: image
                logger.info {
                    "[Category:$categoryName][LongForm] $eventKey: ${outgoing.length} chars as text + " +
                        (if (pageUrl != null) "Telegraph page preview (${telegraphCfg?.mode})" else "image preview")
                }
                // A picture preview sits above the text; the Instant View card of a telegra.ph page goes
                // below it, after the ⚡ link it belongs to.
                sender.sendToChannel(
                    channelId, outgoing, disablePreview = true, replyToMessageId = replyTo,
                    previewImageUrl = preview, previewAboveText = pageUrl == null || linkOnly
                )
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
                // all sharing the same event, article links and clean text). `text` is the topic
                // without its footer — what updateDiff later compares an update against.
                for (ref in refs) {
                    digestMessageRows += DigestMessageRow(
                        chatId = ref.chatId,
                        messageId = ref.messageId,
                        category = categoryName,
                        eventKey = eventKey,
                        articleLinks = linksJson,
                        sentAt = sentAt,
                        variant = variant,
                        text = topic,
                        relatedEventKey = item?.relatedPreviousEventKey
                    )
                }
                if (checkFacts) {
                    val first = refs.first()
                    val sources = if (item != null) {
                        metifikys.ai.PromptBuilder.renderSourcesFor(item, resolvedArticles)
                    } else {
                        topicArticles.mapIndexed { i, a -> metifikys.ai.PromptBuilder.RenderSource(i, a.title, a.promptText(), a.link) }
                    }
                    factCheckPosts += FactChecker.Post(first.chatId, first.messageId, eventKey, topic, sources)
                }
                if (discuss) {
                    val first = refs.first()
                    // The topic's own links first, then the event's other batch articles (the HN copy
                    // of a story is often folded into the cluster, not linked).
                    val eventArticles = (topicArticles + item?.sourceLinks.orEmpty().mapNotNull { articleByLink[it] })
                        .distinctBy { it.link }
                    discussionPosts += DiscussionReplier.Post(first.chatId, first.messageId, eventKey, topic, eventArticles)
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
            // The anti-bubble interval starts only once its post is really in the channel.
            if (digestMessageRows.any { it.variant == DigestMessageRow.VARIANT_EXPLORE }) {
                markExploreSent(categoryName, sentAt)
            }
            // Trackers see exactly the events that reached the channel.
            if (trackerUpdater != null && shortlist != null) {
                val sentKeys = digestMessageRows.mapNotNull { it.eventKey }.toSet()
                trackerUpdater.afterDigest(categoryName, shortlist.filter { it.eventKey in sentKeys }, digestMessageRows)
            }
            // After the channel sends: the admin-chat messages go out on the notifier's own thread.
            moderationNotifier?.afterDigest(categoryName, moderated)
            // Log-only fact check of the sent posts, on the checker's own thread.
            if (factCheckPosts.isNotEmpty()) factChecker?.afterDelivery(categoryName, categoryConfig, factCheckPosts)
            // Discussion replies under the sent posts, on the replier's own thread.
            if (discussionPosts.isNotEmpty()) discussionReplier?.afterDelivery(categoryName, categoryConfig, discussionPosts)
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

    /**
     * [prepareTopics]' output. [dropped] counts the topics removed by the URL whitelist, by reason
     * (`foreign_url`, `bare_url`, `no_url`); [duplicates] those whose every link was in a recent digest.
     */
    data class PreparedTopics(
        val topics: List<String>,
        /**
         * A topic is *valid* only if it carries at least one whitelisted (in-set) article URL. This
         * tells apart the two ways [topics] can end up empty: dropped as duplicates of a prior digest
         * (valid, already delivered → PROCESSED) vs. no valid topic at all (nothing was ever
         * delivered → UNPROCESSED for retry). See BUG-003.
         */
        val hadValidTopic: Boolean,
        val dropped: Map<String, Int> = emptyMap(),
        val duplicates: Int = 0
    )

    companion object {
        const val DROP_FOREIGN_URL = "foreign_url"
        const val DROP_BARE_URL = "bare_url"
        const val DROP_NO_URL = "no_url"

        /**
         * The pure half of [deliver]: splits a render [summary] into topics, relabels and lays them
         * out, and applies the URL whitelist against [articles] and the [previousUrls] repeat check.
         * No DB, no Telegram — golden tests, shadow renders and the fact checker see exactly the
         * topics delivery would post.
         */
        fun prepareTopics(
            categoryName: String,
            summary: String,
            articles: List<Article>,
            previousUrls: Set<String> = emptySet()
        ): PreparedTopics {
            val articleByLink = articles.associateBy { it.link }
            val allArticleLinks = articleByLink.keys
            var hadValidTopic = false
            var duplicates = 0
            val dropped = mutableMapOf<String, Int>()
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
                        dropped.merge(DROP_FOREIGN_URL, 1, Int::plus)
                        return@filter false
                    }
                    // BUG-011 (bare-URL bypass): extractUrls only sees Markdown [label](url) links, so an
                    // injected *bare* URL (https://evil.example with no markdown) slips past the whitelist
                    // above. Strip the markdown links, then reject any remaining http(s):// in the topic.
                    if (BARE_URL_SCHEME.containsMatchIn(TopicFormatter.MARKDOWN_LINK.replace(topic, " "))) {
                        logger.warn {
                            "[Category:$categoryName] Dropping topic with a bare (non-markdown) URL — possible prompt injection."
                        }
                        dropped.merge(DROP_BARE_URL, 1, Int::plus)
                        return@filter false
                    }
                    // BUG-003: a digest bullet with no source link is malformed — drop it rather
                    // than publish a linkless topic (the old code let these through).
                    if (topicUrls.isEmpty()) {
                        logger.warn { "[Category:$categoryName] Dropping topic with no source URL (BUG-003)." }
                        dropped.merge(DROP_NO_URL, 1, Int::plus)
                        return@filter false
                    }
                    hadValidTopic = true
                    val fresh = topicUrls.any { it !in previousUrls }
                    if (!fresh) duplicates++
                    fresh
                }
            return PreparedTopics(topics, hadValidTopic, dropped, duplicates)
        }
    }

    /** The `🧭` footer line of the category's anti-bubble pick. */
    private fun antiBubbleLabel(categoryConfig: CategoryConfig): String =
        categoryConfig.dedup?.digest?.antiBubble?.label ?: AntiBubbleConfig().label

    /** Starts the `dedup.digest.antiBubble` interval (`bot_state`); a failure only logs. */
    private fun markExploreSent(categoryName: String, sentAt: LocalDateTime) {
        try {
            db.setState(AntiBubble.stateKey(categoryName), sentAt.toString())
            logger.info { "[Category:$categoryName][AntiBubble] explore post sent at $sentAt" }
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$categoryName][AntiBubble] could not store the explore post time" }
        }
    }

    /** Per-delivery counter for the intro cap (`post.intro.maxPerDigest`). */
    private class IntroBudget(var remaining: Int)

    private val storyClusters = StoryClusterLoader(config, db)
    private val historyRecorder = EventHistoryRecorder(config, db)
    private val crossLinks = CrossLinks(config, db)

    /**
     * `post.storyLines`: eventKey → the story footer lines, from one `event_articles` lookup per
     * digest (an update's `relatedPreviousEventKey` chain included): the 📢 / 🥇 line, then the ⚖️
     * sources-disagree line (`conflicts.mode: post`; in `log` mode it is only logged as
     * `[StoryConflict]`), then the 🌀 vaporware line (one `event_history` lookup per digest). Needs
     * `storyIntel.enabled`; any failure yields no lines, never a failed digest.
     */
    private fun resolveStoryLines(
        category: String,
        shortlist: List<ShortlistItem>?,
        batch: List<Article>,
        cfg: StoryLinesConfig?
    ): Map<String, List<String>> {
        if (!config.storyIntel.enabled || cfg == null || shortlist.isNullOrEmpty()) return emptyMap()
        val hype = cfg.hype?.takeIf { it.enabled }
        val first = cfg.first?.takeIf { it.enabled }
        val conflicts = cfg.conflicts?.takeIf { it.enabled }
        val vaporware = cfg.vaporware?.takeIf { it.enabled }
        val out = LinkedHashMap<String, List<String>>()
        val loaded = if (hype != null || first != null || conflicts != null) {
            val stories = shortlist.associate { it.eventKey to listOfNotNull(it.eventKey, it.relatedPreviousEventKey) }
            storyClusters.load(category, stories, batch, withVectors = hype != null && hype.reprintSimilarity < 1.0)
        } else emptyMap()
        for ((key, l) in loaded) {
            val lines = try {
                listOfNotNull(
                    StoryLines.line(
                        hype?.let { Hype.analyze(l.cluster, l.texts, l.vectors, it.reprintSimilarity) }, hype,
                        first?.let { Genealogy.first(l.cluster) }, first
                    ),
                    conflicts?.let { conflictLine(category, key, l, it) }
                )
            } catch (e: Exception) {
                logger.warn(e) { "[Category:$category][Story] $key: story lines failed — sending without them" }
                emptyList()
            }
            if (lines.isEmpty()) continue
            out[key] = lines
            logger.info { "[Category:$category][Story] $key: ${lines.joinToString(" / ")} (${l.cluster.members.size} article(s))" }
        }
        if (vaporware != null) {
            for ((key, line) in vaporwareLines(category, shortlist, vaporware)) out[key] = out[key].orEmpty() + line
        }
        return out
    }

    /**
     * `post.storyLines.vaporware`: eventKey → `🌀 анонсовано вчетверте…` for the undated announcements
     * whose entity keeps being announced ([Vaporware]); one `event_history` lookup, fail-open.
     */
    private fun vaporwareLines(category: String, shortlist: List<ShortlistItem>, cfg: VaporwareLineConfig): Map<String, String> {
        val types = cfg.announceTypes.map { it.lowercase() }.toSet()
        val candidates = shortlist.filter { it.eventType.lowercase() in types }
            .mapNotNull { item -> EventHistoryRecorder.entityKeyOf(item.subject, item.franchise).takeIf { it.isNotEmpty() }?.let { item to it } }
        if (candidates.isEmpty()) return emptyMap()
        return try {
            val history = db.fetchEventHistoryByEntities(category, candidates.map { it.second })
            val now = LocalDateTime.now()
            val out = LinkedHashMap<String, String>()
            for ((item, entity) in candidates) {
                val r = Vaporware.count(item.eventType, item.eventDate, history[entity].orEmpty(), cfg, now) ?: continue
                val line = StoryLines.vaporware(r) ?: continue
                out[item.eventKey] = line
                logger.info { "[Category:$category][Story] ${item.eventKey}: $line (entity $entity)" }
            }
            out
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][Story] vaporware lookup failed — sending without it" }
            emptyMap()
        }
    }

    /**
     * `post.storyLines.conflicts`: logs every slot the cluster's sources disagree on as
     * `[StoryConflict]`; returns the footer line only in `post` mode.
     */
    private fun conflictLine(category: String, key: String, l: StoryClusterLoader.Loaded, cfg: ConflictLineConfig): String? {
        val found = ConflictDetector.detect(l.cluster, l.texts, cfg.minSupport, cfg.dateToleranceDays)
        for (c in found) {
            logger.info { "[StoryConflict] cat=$category $key ${ConflictDetector.describe(c.slot, c.unit, c.values)} mode=${cfg.mode}" }
        }
        return if (cfg.mode == ConflictLineConfig.MODE_POST) StoryLines.conflict(found) else null
    }

    /**
     * Footer lines for one bullet, in [FooterLines.Slot] order — display order and priority at
     * once ([TopicDecorator] drops from the end when the message budget is tight): 💬 persona,
     * badges (▶️ trailer included), 📚 intro, 🧭 anti-bubble, 🔗 other channel, the story lines
     * (📢 / 🥇, ⚖️, 🌀 — resolved per digest), 📰 also, hashtags. Every URL a line carries is
     * deterministic and added after the whitelist filter, so none reaches `saveSummary`,
     * `previousUrls` or `digest_messages.article_links`. Lookups run under their time budgets
     * and never throw.
     */
    private fun buildFooter(
        categoryName: String,
        item: ShortlistItem?,
        topicArticles: List<Article>,
        post: PostConfig?,
        badgeDeadline: Long,
        introBudget: IntroBudget,
        storyLines: List<String> = emptyList(),
        articleByLink: Map<String, Article> = emptyMap(),
        feedLabels: Map<String, String> = emptyMap(),
        crossLink: String? = null,
        exploreLine: String? = null,
        personaLine: String? = null
    ): FooterLines {
        if (post == null) return FooterLines().add(Slot.EXPLORE, exploreLine)
        val footer = FooterLines()
        footer.add(Slot.PERSONA, personaLine)
        footer.add(Slot.EXPLORE, exploreLine)
        val enabledBadges = post.badges?.enabledIds.orEmpty()
        if (badgeResolver != null && enabledBadges.isNotEmpty()) {
            footer.addAll(Slot.BADGES, badgeResolver.resolve(enabledBadges, item, topicArticles, badgeDeadline, "[Category:$categoryName]"))
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
                footer.add(Slot.INTRO, line)
                introBudget.remaining--
            }
        }
        footer.add(Slot.CROSS_LINK, crossLink)
        footer.addAll(Slot.STORY, storyLines)
        val alsoCfg = post.also
        if (alsoCfg != null && alsoCfg.enabled && item != null) {
            footer.add(Slot.ALSO, alsoLine(categoryName, item, alsoCfg, articleByLink, feedLabels))
        }
        val tags = Hashtags.forItem(item, post.hashtags)
        if (tags.isNotEmpty()) footer.add(Slot.HASHTAGS, tags.joinToString(" "))
        val lines = footer.build()
        if (lines.isNotEmpty()) logger.debug { "[Category:$categoryName][Post] footer: $lines" }
        return footer
    }

    /**
     * `post.images`: the post's picture — the post article's og:image (`ogFirst`), the topic
     * articles' stored images, then (`fromCluster`) those of the event's other cluster articles,
     * the first one [PostImages] accepts. The post article is the item's `url` when the topic
     * links it, else the topic's first link.
     */
    private fun chooseImage(
        categoryName: String,
        topicLinks: Set<String>,
        item: ShortlistItem?,
        articleByLink: Map<String, Article>,
        cfg: PostImagesConfig,
        deadline: Long
    ): String? {
        val postUrl = item?.url?.takeIf { it in topicLinks } ?: topicLinks.firstOrNull { it in articleByLink }
        val clusterLinks = if (cfg.fromCluster && item != null) item.sourceLinks else emptyList()
        val feedImages = (topicLinks + clusterLinks).distinct().mapNotNull { articleByLink[it]?.imageUrl }
        val label = "[Category:$categoryName]" + (item?.eventKey?.let { "[$it]" } ?: "")
        val image = postImages?.choose(label, postUrl, feedImages, cfg, deadline)
        if (image != null) logger.debug { "$label[Image] using $image" }
        return image
    }

    /**
     * `post.longForm.telegraph`: publishes [topic] as a telegra.ph page — the picture, every
     * paragraph, «Джерела» (the topic's links, then the event's other batch articles named as in
     * the 📰 line) and the channel link — and returns its URL, or null (the publisher logs why).
     */
    private fun publishPage(
        categoryName: String,
        categoryConfig: CategoryConfig,
        item: ShortlistItem,
        topic: String,
        image: String?,
        articleByLink: Map<String, Article>,
        feedLabels: Map<String, String>
    ): String? {
        val publisher = telegraph ?: return null
        val channel = categoryConfig.channelId.trim().takeIf { it.startsWith("@") && it.length > 1 }
            ?.let { TelegraphPage.Link(it, "https://t.me/${it.drop(1)}") }
        val content = TelegraphPage.content(topic, image, eventSources(categoryConfig, item, topic, articleByLink, feedLabels), channel)
        return publisher.publish(categoryName, item.eventKey, TelegraphPage.title(topic), content, channel?.url)
    }

    /**
     * The «Джерела» of a long post: the topic's links, then the event's other batch articles named
     * as in the 📰 line (`post.also.names`, else the feed label, else the domain) with their titles.
     */
    private fun eventSources(
        categoryConfig: CategoryConfig,
        item: ShortlistItem,
        topic: String,
        articleByLink: Map<String, Article>,
        feedLabels: Map<String, String>
    ): List<TelegraphPage.Link> {
        val names = categoryConfig.post?.also?.takeIf { it.enabled }?.names.orEmpty()
        val others = item.sourceLinks.map { link ->
            val host = AlsoLine.hostOf(link)
            val feedLabel = articleByLink[link]?.feedUrl?.let { feed -> feedLabels[feed]?.takeIf { AlsoLine.hostOf(feed) == host } }
            val name = AlsoLine.nameOf(host, names, feedLabel)
            val title = articleByLink[link]?.title?.replace(Regex("""\s+"""), " ")?.trim()
            TelegraphPage.Link(if (title.isNullOrEmpty()) name else "$name — $title", link)
        }
        return TelegraphPage.topicLinks(topic) + others
    }

    /**
     * `post.longForm.rich`: [topic] as one rich message ([RichPost.longForm]) — sources collapsed,
     * footer facts as paragraphs. A refusal is retried once without the picture (Telegram may not
     * be able to fetch it); null after that, and the caller sends the classic long post.
     */
    private fun sendRichLongForm(
        categoryName: String,
        categoryConfig: CategoryConfig,
        item: ShortlistItem,
        topic: String,
        footer: FooterLines,
        image: String?,
        articleByLink: Map<String, Article>,
        feedLabels: Map<String, String>,
        replyTo: Long?
    ): SentRef? {
        val label = "[Category:$categoryName][LongForm][Rich] ${item.eventKey}"
        val sources = eventSources(categoryConfig, item, topic, articleByLink, feedLabels).map { RichHtml.Link(it.label, it.url) }
        for (picture in listOfNotNull(image).plus<String?>(null)) {
            val html = RichPost.longForm(topic, footer, picture, sources)
            val ref = sender.sendRichMessage(categoryConfig.channelId, html, replyToMessageId = replyTo)
            if (ref != null) {
                logger.info { "$label: rich message, ${html.length} chars, ${sources.size} source(s)" + if (picture == null && image != null) ", without the picture $image" else "" }
                return ref
            }
        }
        logger.warn { "$label: Telegram refused the rich message — sending the classic long post" }
        return null
    }

    /**
     * `post.also`: logs every event's other outlets as `[Also]` (the batch cluster's size is what
     * decides whether the line is worth having) and returns the line unless `logOnly`.
     */
    private fun alsoLine(
        categoryName: String,
        item: ShortlistItem,
        cfg: AlsoConfig,
        articleByLink: Map<String, Article>,
        feedLabels: Map<String, String>
    ): String? {
        val all = AlsoLine.sources(item, cfg.copy(max = Int.MAX_VALUE)) { link ->
            articleByLink[link]?.feedUrl?.let { feed -> feedLabels[feed]?.let { feed to it } }
        }
        logger.info {
            "[Category:$categoryName][Also] ${item.eventKey}: ${all.size} other source(s) of ${item.sourceLinks.size} article(s)" +
                (if (all.isEmpty()) "" else " — ${all.joinToString(", ") { it.host }}") + (if (cfg.logOnly) " (logOnly)" else "")
        }
        return if (cfg.logOnly) null else AlsoLine.line(all.take(cfg.max))
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
        // storyIntel: the long-lived first-appearance history (🌀 vaporware, weekly barometer).
        historyRecorder.record(categoryName, shortlist, now)
        logger.info { "[Category:$categoryName][Dedup] persisted ${rows.size} events" }
    }
}
