package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.EventArticleRole
import metifikys.db.NewsDatabase
import metifikys.digest.MuteRegistry
import metifikys.digest.TopicMuteFilter
import metifikys.feedback.AffinityMath
import metifikys.feeds.FeedHealthSection
import metifikys.feeds.FeedQuality
import metifikys.format.EventTypeLabels
import metifikys.story.EventCluster
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * Post inspector (`admin.inspect`): the owner sends `/inspect <link>`, a message with channel-post
 * links, or forwards a channel post, and gets one card per post ([PostCard]) — what the bot knows
 * about it (event, scores, sources, cluster, audience, the mutes that already hit it) — with 🔇
 * buttons:
 *
 *  - a keyword ▸ (the franchise, the subject, distinctive title words) → in-category / global,
 *    the same DB mute as `/mute` (source `inspect`);
 *  - the event type ▸ → muted in this category: Step 1 post-processing drops such items
 *    ([MuteRegistry.addEventType]).
 *
 * Callback data is `in:<action>:<post chat>:<post message>[:<idx>:<tag>]`; the candidates are
 * re-derived on a press and [PostCard.tag] makes sure index `idx` still names the same word.
 * Every outcome edits the card's keyboard; the text is never rebuilt. Runs on the
 * `admin-commands` thread like every command.
 */
class PostInspector(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val mutes: MuteRegistry,
    private val sender: TelegramSender,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    /** Registers `/inspect` and the link / forward route on [router]. */
    fun registerAll(router: AdminCommandRouter) {
        router.register("inspect", "/inspect <посилання на пост>", "усе про пост каналу + 🔇 кнопки") { cmd, ctx ->
            val refs = PostLinks.parse(cmd.args.joinToString(" "))
            if (refs.isEmpty()) {
                ctx.reply("Використання: /inspect https://t.me/c/<id>/<msg> — або просто надішли посилання на пост чи перешли його сюди.")
            } else {
                inspect(refs, ctx.chatId)
            }
        }
        router.registerMessageHandler("inspect", ::accepts) { m, ctx -> inspect(refsOf(m), ctx.chatId) }
    }

    /** A forwarded channel post or a message with post links; cheap, runs on the poller thread. */
    fun accepts(m: IncomingMessage): Boolean = PostLinks.forwarded(m) != null || PostLinks.parse(m.text).isNotEmpty()

    private fun refsOf(m: IncomingMessage): List<PostRef> =
        (listOfNotNull(PostLinks.forwarded(m)) + PostLinks.parse(m.text)).distinct().take(PostLinks.MAX_LINKS)

    /** One card (or a "not found" line) per ref, sent to [chatId]. */
    fun inspect(refs: List<PostRef>, chatId: String) {
        val misses = mutableListOf<String>()
        for (ref in refs) {
            val found = try {
                resolve(ref)
            } catch (e: Exception) {
                logger.warn(e) { "[Inspect] lookup of ${ref.display()} failed" }
                misses += "⚠️ ${ref.display()}: помилка — ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            when (found) {
                is Resolved.UnknownChannel -> misses += "🔎 ${ref.display()}: @${ref.username} — не канал жодної категорії бота"
                Resolved.NotFound -> misses += "🔎 ${ref.display()}: не знайшов — це не дайджест-пост каналів бота, " +
                    "або він старший за ретеншн digest_messages (90 дн.)"
                is Resolved.Post -> {
                    val inspection = load(found.first, found.chunks)
                    val kb = keyboardFor(found.first, inspection)
                    if (sender.sendMessageWithKeyboard(chatId, PostCard.render(inspection), kb, html = true) == null) {
                        logger.warn { "[Inspect] card for ${ref.display()} not sent" }
                    }
                    logger.info { "[Inspect] card for ${found.first.category} ${found.first.chatId}/${found.first.messageId} (${found.first.eventKey})" }
                }
            }
        }
        if (misses.isNotEmpty()) sender.sendMessageWithKeyboard(chatId, misses.joinToString("\n"))
    }

    sealed interface Resolved {
        /** [first] is the post's first message; [chunks] all of its messages. */
        data class Post(val first: DigestMessageRow, val chunks: List<DigestMessageRow>) : Resolved
        data class UnknownChannel(val username: String) : Resolved
        object NotFound : Resolved
    }

    /** The post [ref] points at; a later chunk of a chunked post resolves to the same post. */
    fun resolve(ref: PostRef): Resolved {
        val row = if (ref.chatId != null) {
            db.fetchDigestMessage(ref.chatId, ref.messageId)
        } else {
            val username = ref.username ?: return Resolved.NotFound
            val categories = config.categories.filterValues { it.channelId.trim().removePrefix("@").equals(username, ignoreCase = true) }.keys
            if (categories.isEmpty()) return Resolved.UnknownChannel(username)
            db.fetchDigestMessagesByMessageId(ref.messageId, categories).firstOrNull()
        } ?: return Resolved.NotFound
        val chunks = db.fetchDigestMessagesOfPost(row).ifEmpty { listOf(row) }
        return Resolved.Post(chunks.minBy { it.messageId }, chunks)
    }

    /** Everything the card shows; a failing part is left out (logged), never the whole card. */
    fun load(first: DigestMessageRow, chunks: List<DigestMessageRow>): PostInspection {
        val category = first.category
        val cat = config.categories[category]
        fun url(row: DigestMessageRow) = TelegramLinks.messageUrl(cat?.channelId.orEmpty(), row.chatId, row.messageId)
        val key = first.eventKey
        val related = first.relatedEventKey

        val chain = safe("story chain") {
            if (key == null) emptyList()
            else db.fetchStoryMessages(category, listOfNotNull(key, related), first.sentAt.minusDays(CHAIN_LOOKBACK_DAYS))
                .filter { it.chatId == first.chatId }
        }.orEmpty()
        val previous = chain.lastOrNull { it.sentAt < first.sentAt }
        val later = chain.filter { it.sentAt > first.sentAt }

        val event = key?.let { k -> safe("covered event") { db.fetchCoveredEventsByKeys(category, listOf(k), EPOCH).firstOrNull() } }
        val links = linksOf(first, event)
        val records = safe("articles") { db.fetchArticleRecordsByLinks(links) }.orEmpty().associateBy { it.link }
        val articles = safe("article texts") { db.fetchArticlesByLinks(links) }.orEmpty()

        val clusterRows = key?.let { k -> safe("cluster") { db.fetchEventClusters(category, listOfNotNull(k, related)) } }.orEmpty()
        val step1Status = clusterRows[key].orEmpty()
            .firstOrNull { it.role == EventArticleRole.STEP1 && it.link in links && it.step1Status != null }?.step1Status
        val cluster = if (key != null && clusterRows.isNotEmpty()) {
            EventCluster.of(key, clusterRows, listOf(related), config.storyIntel.domainAliases).takeIf { it.members.isNotEmpty() }
        } else null

        val labels = FeedHealthSection.labels(config)
        val metrics = feedMetrics()
        val statsShown = mutableSetOf<String>()
        val sources = links.map { link ->
            val a = records[link]
            val feed = a?.feedUrl
            PostInspection.Source(
                link = link,
                article = a,
                feedName = feed?.let { FeedQuality.displayName(it, labels[it]) },
                feedStats = feed?.takeIf { statsShown.add(it) }?.let { metrics[it] }
            )
        }

        val affinity = event?.let { e -> safe("affinity") { affinityCells(category, e.franchise, e.eventType, e.subject, e.url) } }.orEmpty()
        val inScope = mutes.entries().filter { it.category == null || it.category == category }
        val keywordMutes = inScope.filter { m -> TopicMuteFilter.of(listOf(m.keyword)).let { f -> articles.any { f.matches(it) } } }

        return PostInspection(
            category = category,
            emoji = cat?.emoji.orEmpty(),
            post = first,
            chunks = chunks.size,
            postUrl = url(first),
            previous = previous?.let { PostInspection.ChainPost(it.sentAt, url(it)) },
            laterCount = later.size,
            latest = later.lastOrNull()?.let { PostInspection.ChainPost(it.sentAt, url(it)) },
            telegraphUrl = key?.let { k -> safe("telegraph") { db.fetchLatestTelegraphPage(category, k) }?.url },
            event = event,
            step1Status = step1Status,
            digest = cat?.dedup?.digest,
            sources = sources,
            cluster = cluster,
            reactions = safe("reactions") { db.fetchReactionCounts(chunks.map { it.chatId to it.messageId }) }.orEmpty(),
            adminSignal = safe("admin feedback") { db.fetchAdminFeedback(first.chatId, first.messageId) }?.signal,
            affinity = affinity,
            keywordMutes = keywordMutes,
            typeMute = event?.eventType?.takeIf { it.isNotBlank() }?.let { mutes.coveringType(it, category) },
            eventRetentionDays = config.summaryHistory.retentionDays
        )
    }

    /** The post's affinity rows: franchise, event type, subject, source domain (missing ones skipped). */
    private fun affinityCells(category: String, franchise: String, eventType: String, subject: String, url: String): List<PostInspection.AffinityCell> {
        val rows = db.fetchAudienceAffinity().filter { it.category == category }
        val wanted = listOf(
            Triple("франшиза", AffinityMath.DIM_FRANCHISE, franchise),
            Triple("тип", AffinityMath.DIM_EVENT_TYPE, eventType),
            Triple("тема", AffinityMath.DIM_SUBJECT, subject),
            Triple("джерело", AffinityMath.DIM_SOURCE, AffinityMath.sourceDomain(url))
        )
        return wanted.mapNotNull { (label, dim, raw) ->
            val k = raw.trim().lowercase().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            rows.firstOrNull { it.dimension == dim && it.key == k }?.let { PostInspection.AffinityCell(label, it.key, it.score, it.nTone, it.n) }
        }
    }

    @Volatile private var metricsCache: Pair<LocalDateTime, Map<String, FeedQuality.FeedMetrics>>? = null

    /** `feedHealth` metrics by feed url, cached for a minute (a 5-link message reuses one snapshot). */
    private fun feedMetrics(): Map<String, FeedQuality.FeedMetrics> {
        if (!config.feedHealth.enabled) return emptyMap()
        val now = clock()
        metricsCache?.let { (at, m) -> if (at.isAfter(now.minusMinutes(1))) return m }
        val fresh = safe("feed metrics") { FeedHealthSection.metrics(config, db, now).associateBy { it.feedUrl } }.orEmpty()
        metricsCache = now to fresh
        return fresh
    }

    private inline fun <T> safe(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        logger.warn(e) { "[Inspect] $what lookup failed — left out of the card" }
        null
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    /** The keyword candidates and the event type of a post, as its buttons offer them. */
    private data class Offer(val candidates: List<String>, val eventType: String?)

    private fun offerFor(row: DigestMessageRow, event: CoveredEventRow? = null): Offer {
        val e = event ?: row.eventKey?.let { k ->
            safe("covered event") { db.fetchCoveredEventsByKeys(row.category, listOf(k), EPOCH).firstOrNull() }
        }
        val titles = safe("article texts") { db.fetchArticlesByLinks(linksOf(row, e)) }.orEmpty().map { it.title }
        return Offer(
            candidates = PostCard.keywordCandidates(e?.franchise, e?.subject, titles),
            eventType = e?.eventType?.takeIf { it.isNotBlank() }?.let { EventTypeLabels.normalize(it) }
        )
    }

    private fun keyboardFor(row: DigestMessageRow, inspection: PostInspection): List<List<InlineButton>> =
        mainKeyboard(row, offerFor(row, inspection.event))

    /** The post's article links; a row without them (older rows) falls back to the event's url. */
    private fun linksOf(row: DigestMessageRow, event: CoveredEventRow?): List<String> =
        row.articleLinks.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOfNotNull(event?.url?.takeIf { it.isNotBlank() }) }

    private fun mainKeyboard(row: DigestMessageRow, offer: Offer, note: String? = null): List<List<InlineButton>> =
        PostCard.keyboard(
            PostCard.KeyboardState(
                chatId = row.chatId,
                messageId = row.messageId,
                category = row.category,
                candidates = offer.candidates.map { it to (mutes.covering(it, row.category) != null) },
                eventType = offer.eventType?.let { it to (mutes.coveringType(it, row.category) != null) },
                note = note
            )
        )

    /** `in:<action>:<post chat>:<post message>[:<idx>:<tag>]` from a card's keyboard. */
    fun handleCallback(cb: IncomingCallback, parts: List<String>) {
        val action = parts.getOrNull(0) ?: return
        if (action == "noop") return
        val chat = parts.getOrNull(1)?.toLongOrNull() ?: return
        val msg = parts.getOrNull(2)?.toLongOrNull() ?: return
        val adminChat = cb.chatId?.toString() ?: return
        val adminMsg = cb.messageId ?: return
        fun show(keyboard: List<List<InlineButton>>) {
            sender.editMessageReplyMarkup(adminChat, adminMsg, keyboard)
        }
        val row = db.fetchDigestMessage(chat, msg)
        if (row == null) {
            show(listOf(listOf(InlineButton("пост уже не в БД (видалено або ретеншн)", "in:noop:$chat:$msg"))))
            return
        }
        val offer = offerFor(row)
        fun candidate(): String? {
            val idx = parts.getOrNull(3)?.toIntOrNull() ?: return null
            val kw = offer.candidates.getOrNull(idx) ?: return null
            return kw.takeIf { PostCard.tag(it) == parts.getOrNull(4) }
        }
        val stale = "⚠️ дані поста змінились — надішли посилання ще раз"
        when (action) {
            "back" -> show(mainKeyboard(row, offer))
            "k" -> {
                val kw = candidate() ?: return show(mainKeyboard(row, offer, stale))
                show(PostCard.keywordConfirm(chat, msg, row.category, parts[3].toInt(), kw))
            }
            "kc", "kg" -> {
                val kw = candidate() ?: return show(mainKeyboard(row, offer, stale))
                val scope = if (action == "kc") row.category else null
                val note = when (val res = mutes.add(kw, scope, cb.fromId, SOURCE)) {
                    is MuteRegistry.AddResult.Added -> "🔇 «$kw» зам'ючено: ${scope ?: "глобально"}"
                    is MuteRegistry.AddResult.AlreadyMuted -> "🔇 «$kw» уже зам'ючено"
                    is MuteRegistry.AddResult.Invalid -> "⚠️ ${res.reason}"
                }
                show(mainKeyboard(row, offer, note))
            }
            "t" -> {
                val type = offer.eventType ?: return show(mainKeyboard(row, offer, stale))
                show(PostCard.typeConfirm(chat, msg, row.category, type))
            }
            "tc" -> {
                val type = offer.eventType ?: return show(mainKeyboard(row, offer, stale))
                val label = EventTypeLabels.label(type)
                val note = when (val res = mutes.addEventType(type, row.category, cb.fromId, SOURCE)) {
                    is MuteRegistry.AddResult.Added -> "🔇 тип «$label» зам'ючено в ${row.category}"
                    is MuteRegistry.AddResult.AlreadyMuted -> "🔇 тип «$label» уже зам'ючено"
                    is MuteRegistry.AddResult.Invalid -> "⚠️ ${res.reason}"
                }
                show(mainKeyboard(row, offer, note))
            }
            else -> logger.warn { "[Inspect] unknown action '$action'" }
        }
    }

    companion object {
        const val SOURCE = "inspect"
        /** Callback namespace of the card's buttons. */
        const val NAMESPACE = "in"
        /** How far back the story chain is followed from the post (digest_messages keeps 90 days). */
        const val CHAIN_LOOKBACK_DAYS = 90L
        private val EPOCH: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)
    }
}
