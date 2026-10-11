package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.DigestMessageRow
import metifikys.db.DropReason
import metifikys.db.EventArticleRow
import metifikys.db.NewsDatabase
import metifikys.db.RawArticle
import metifikys.db.RejectedEventRow
import metifikys.feedback.AffinityMath
import metifikys.feeds.FeedQuality
import metifikys.format.TopicFormatter
import metifikys.model.ArticleStatus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * `/raw`: what the feeds actually bring, before any LLM. The owner sends `/raw` (or `/raw <cat>`)
 * and walks a keyboard — category → its configured feeds → a feed — and the bot sends that feed's
 * [LIMIT] latest articles, one message each: title and link, clocks, the article's status (queue,
 * Step 0 mute, semantic duplicate, Step 1 verdict, the channel post it went into) and the stored
 * text ([RawArticle.text]) in a collapsed quote. Read-only.
 *
 * The keyboard is edited in place (`raw:c:<cat idx>`, `raw:home`); a feed press
 * (`raw:f:<cat idx>:<feed idx>:<tag>`) sends the articles and leaves the keyboard, so another feed
 * is one press away. Indexes follow the config order; [PostCard.tag] of the feed url catches a
 * config that changed under an old keyboard. Everything goes to `admin.threads.raw` in the admin
 * chat (else the thread the request came from). Runs on the `admin-commands` thread.
 */
class RawBrowser(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val sender: TelegramSender,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    private val categories: List<String> get() = config.categories.keys.toList()

    fun registerAll(router: AdminCommandRouter) {
        router.register("raw", "/raw [cat]", "сирі статті: топік → фід → $LIMIT останніх") { cmd, ctx ->
            val thread = config.admin.threadFor(ctx.chatId, config.admin.threads.raw, ctx.threadId)
            val arg = cmd.args.firstOrNull()
            val ci = arg?.let { categories.indexOf(it) }
            when {
                arg == null -> sender.sendMessageWithKeyboard(ctx.chatId, HOME_TEXT, homeKeyboard(), html = true, threadId = thread)
                ci == null || ci < 0 -> ctx.reply("Невідома категорія «$arg». Є: ${categories.joinToString(", ")}")
                else -> sender.sendMessageWithKeyboard(ctx.chatId, feedsText(ci), feedsKeyboard(ci), html = true, threadId = thread)
            }
        }
    }

    fun homeKeyboard(): List<List<InlineButton>> =
        categories.mapIndexed { ci, name ->
            val emoji = config.categories[name]?.emoji.orEmpty()
            InlineButton("$emoji $name".trim(), "$NAMESPACE:c:$ci")
        }.chunked(2)

    private fun feedsText(ci: Int): String {
        val name = categories[ci]
        val cat = config.categories.getValue(name)
        return "📂 ${cat.emoji} <b>${h(name)}</b> — ${cat.feeds.size} ${feedsWord(cat.feeds.size)}. Обери фід:"
    }

    fun feedsKeyboard(ci: Int): List<List<InlineButton>> {
        val feeds = config.categories.getValue(categories[ci]).feeds
        val rows = feeds.take(MAX_FEED_BUTTONS).mapIndexed { fi, f ->
            listOf(InlineButton(FeedQuality.displayName(f.url, f.label, BUTTON_LABEL_MAX), "$NAMESPACE:f:$ci:$fi:${PostCard.tag(f.url)}"))
        }
        val more = (feeds.size - MAX_FEED_BUTTONS).coerceAtLeast(0)
        return rows +
            (if (more > 0) listOf(listOf(InlineButton("… ще $more не влізли в клавіатуру", "$NAMESPACE:noop"))) else emptyList()) +
            listOf(listOf(InlineButton("↩ топіки", "$NAMESPACE:home")))
    }

    /** `raw:<action>[:<cat idx>[:<feed idx>:<tag>]]` from the browser's keyboard. */
    fun handleCallback(cb: IncomingCallback, parts: List<String>) {
        val chat = cb.chatId?.toString() ?: return
        val msg = cb.messageId ?: return
        fun show(text: String, keyboard: List<List<InlineButton>>) {
            sender.editMessageWithKeyboard(chat, msg, text, keyboard, html = true)
        }
        val ci = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in categories.indices }
        when (parts.getOrNull(0)) {
            "noop" -> return
            "home" -> show(HOME_TEXT, homeKeyboard())
            "c" -> if (ci == null) show(STALE, homeKeyboard()) else show(feedsText(ci), feedsKeyboard(ci))
            "f" -> {
                val feeds = ci?.let { config.categories.getValue(categories[it]).feeds }
                val feed = parts.getOrNull(2)?.toIntOrNull()?.let { feeds?.getOrNull(it) }
                if (ci == null || feed == null || PostCard.tag(feed.url) != parts.getOrNull(3)) {
                    show(STALE, homeKeyboard())
                    return
                }
                val thread = config.admin.threadFor(chat, config.admin.threads.raw, cb.threadId)
                send(categories[ci], feed.url, FeedQuality.displayName(feed.url, feed.label), chat, thread)
            }
            else -> logger.warn { "[Raw] unknown action '${parts.firstOrNull()}'" }
        }
    }

    /** The [LIMIT] latest articles of [feedUrl] in [category]: a header, then one message per article. */
    fun send(category: String, feedUrl: String, feedName: String, chatId: String, threadId: Long?) {
        val articles = db.fetchLatestArticlesOfFeed(category, feedUrl, LIMIT)
        val emoji = config.categories[category]?.emoji.orEmpty()
        logger.info { "[Raw] $category / $feedUrl: ${articles.size} article(s) to $chatId" }
        if (articles.isEmpty()) {
            sender.sendMessageWithKeyboard(
                chatId,
                "📭 $emoji ${h(category)} · <b>${h(feedName)}</b>: у БД немає жодної статті цієї стрічки " +
                    "(ще нічого не принесла, або все старше за ретеншн articles).",
                html = true, threadId = threadId
            )
            return
        }
        val steps = loadSteps(category, articles)
        sender.sendMessageWithKeyboard(
            chatId,
            "📡 $emoji ${h(category)} · <b>${h(feedName)}</b> — ${articles.size} ${lastWord(articles.size)}, нові згори\n" +
                "<code>${h(feedUrl)}</code>",
            html = true, threadId = threadId
        )
        articles.forEachIndexed { i, a ->
            val text = RawCard.render(a, i + 1, articles.size, steps[a.record.id] ?: RawCard.Step1())
            if (sender.sendMessageWithKeyboard(chatId, text, silent = true, html = true, threadId = threadId) == null) {
                logger.warn { "[Raw] article ${a.record.link} not sent" }
            }
        }
    }

    /** What Step 1 and delivery did with each article (by article id); a failing lookup is left out. */
    private fun loadSteps(category: String, articles: List<RawArticle>): Map<Int, RawCard.Step1> {
        val events = safe("event_articles") { db.fetchEventArticlesByArticleIds(category, articles.map { it.record.id }) }.orEmpty()
        val rejected = safe("rejected_events") { db.fetchRejectedEventsByUrls(category, articles.map { it.record.link }) }.orEmpty()
        val keys = events.values.map { it.eventKey }.distinct()
        val posts: Map<String, DigestMessageRow> = if (keys.isEmpty()) emptyMap() else
            safe("digest posts") { db.fetchLatestDigestMessagesByEventKeys(category, keys, clock().minusDays(POST_LOOKBACK_DAYS)) }.orEmpty()
        val channel = config.categories[category]?.channelId.orEmpty()
        return articles.associate { a ->
            val ea = events[a.record.id]
            val post = ea?.let { posts[it.eventKey] }
            a.record.id to RawCard.Step1(
                event = ea,
                rejected = rejected[a.record.link],
                post = post?.let { RawCard.Post(it.sentAt, TelegramLinks.messageUrl(channel, it.chatId, it.messageId)) }
            )
        }
    }

    private inline fun <T> safe(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        logger.warn(e) { "[Raw] $what lookup failed — left out" }
        null
    }

    private fun h(s: String) = TopicFormatter.escapeHtml(s)

    private fun plural(n: Int, one: String, few: String, many: String) = when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }

    private fun feedsWord(n: Int) = plural(n, "фід", "фіди", "фідів")
    private fun lastWord(n: Int) = plural(n, "остання стаття", "останні статті", "останніх статей")

    companion object {
        /** Callback namespace of the browser's buttons. */
        const val NAMESPACE = "raw"
        /** Articles per feed press. */
        const val LIMIT = 10
        /** Telegram caps an inline keyboard at 100 buttons; the rest are the "more" and "back" rows. */
        const val MAX_FEED_BUTTONS = 95
        private const val BUTTON_LABEL_MAX = 40
        /** How far back the channel post of an article's event is looked up (digest_messages keeps 90 days). */
        private const val POST_LOOKBACK_DAYS = 90L
        private const val HOME_TEXT = "📂 <b>/raw</b> — сирі статті зі стрічок. Обери топік:"
        private const val STALE = "⚠️ конфіг змінився після цієї клавіатури — обери ще раз:"
    }
}

/** One `/raw` article message (Telegram HTML). Pure. */
object RawCard {

    /** Visible characters per message; the sender cuts admin messages at 4000 as well. */
    const val MAX_CHARS = 4000
    private const val TITLE_MAX = 200
    private const val SUMMARY_MAX = 700
    private const val KEY_MAX = 80
    private const val CORE_FACT_MAX = 300

    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** The channel post an article's event went into. */
    data class Post(val sentAt: LocalDateTime, val url: String?)

    /** What Step 1 recorded about an article: its event row, its rejection row, the event's post. */
    data class Step1(val event: EventArticleRow? = null, val rejected: RejectedEventRow? = null, val post: Post? = null)

    private fun h(s: String) = TopicFormatter.escapeHtml(s)
    private fun visible(s: String) = TopicFormatter.htmlVisibleLength(s)
    private fun attr(s: String) = h(s).replace("\"", "&quot;")
    private fun link(url: String?, label: String) = if (url == null) h(label) else "<a href=\"${attr(url)}\">${h(label)}</a>"
    private fun cut(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
    private fun score(v: Int) = if (v > 0) v.toString() else "—"

    fun render(a: RawArticle, n: Int, total: Int, step: Step1): String {
        val r = a.record
        val head = mutableListOf<String>()
        head += "<b>$n/$total</b> · " + link(r.link, cut(r.title.ifBlank { r.link }, TITLE_MAX))
        AffinityMath.sourceDomain(r.link).takeIf { it.isNotEmpty() }?.let { head += h(it) + " · #${r.id}" }
        val taken = r.fetchedAt?.let { f -> " → взято ${f.format(stamp)} (${PostCard.delta(r.pubDate, f)})" }.orEmpty()
        head += "опубл. ${r.pubDate.format(stamp)}$taken"
        head += "статус: " + statusLabel(a)
        head += stepLines(a, step)

        val summary = r.summary?.replace(SPACES, " ")?.trim()?.takeIf { it.isNotEmpty() }
        val summaryLine = summary?.let { "🤖 AI-саммарі (${it.length} симв.):<blockquote expandable>${h(cut(it, SUMMARY_MAX))}</blockquote>" }

        val text = a.text.trim()
        val top = (head + listOfNotNull(summaryLine)).joinToString("\n")
        if (text.isEmpty()) return "$top\n📄 тексту немає (стрічка дала лише заголовок)"
        val label = "📄 Текст (${text.length} симв.):"
        // Escapes count as one character each, so the raw length is the visible length of the quote.
        val budget = MAX_CHARS - visible(top) - visible(label) - 4
        val body = if (text.length <= budget) text else cut(text, budget.coerceAtLeast(1))
        return "$top\n$label<blockquote expandable>${h(body)}</blockquote>"
    }

    /** The row's lifecycle state in words; `PROCESSED` alone says nothing about what Step 1 decided. */
    fun statusLabel(a: RawArticle): String {
        val r = a.record
        return when (r.status) {
            ArticleStatus.UNPROCESSED.name -> "⏳ UNPROCESSED — чекає на Step 1"
            ArticleStatus.PROCESSING.name -> "⚙️ PROCESSING — зараз у Step 1"
            ArticleStatus.DUPLICATE.name -> "🔁 DUPLICATE — semantic dedup, у Step 1 не пішла" +
                (a.duplicateOf?.let { " (дубль статті #$it)" } ?: "")
            ArticleStatus.PROCESSED.name -> when (r.dropReason) {
                null -> "☑️ PROCESSED — Step 1 її розглянув"
                DropReason.MUTE -> "🔇 PROCESSED · mute — відсіяна до Step 1 (Step 0, mute-слово)"
                DropReason.NEW_FEED -> "👀 PROCESSED · new_feed — давній хвіст нової стрічки, у Step 1 не йшла"
                else -> "PROCESSED · ${h(r.dropReason)}"
            }
            else -> h(r.status) + (r.dropReason?.let { " · ${h(it)}" } ?: "")
        }
    }

    private fun stepLines(a: RawArticle, s: Step1): List<String> {
        val out = mutableListOf<String>()
        s.event?.let { e ->
            val role = if (e.role == metifikys.db.EventArticleRole.STEP1) "" else " · ${h(e.role)}"
            out += "🧩 подія <code>${h(cut(e.eventKey, KEY_MAX))}</code>" + (e.step1Status?.let { " · step1: ${h(it)}" } ?: "") + role
        }
        s.post?.let { p -> out += "📣 у каналі: ${link(p.url, "пост ↗")} (${p.sentAt.format(stamp)})" }
        s.rejected?.let { rj ->
            out += "🤖 Step 1: <b>${h(rj.status)}</b> · imp ${score(rj.importance)} · news ${score(rj.newsworthiness)} · fit ${score(rj.digestFit)}"
            val what = listOf(rj.subject, rj.franchise).filter { it.isNotBlank() }.distinct().joinToString(" / ")
            if (what.isNotEmpty()) out += "   ${h(cut(what, KEY_MAX))}"
            if (rj.coreFact.isNotBlank()) out += "   ${h(cut(rj.coreFact.trim(), CORE_FACT_MAX))}"
        }
        if (out.isEmpty() && a.record.status == ArticleStatus.PROCESSED.name && a.record.dropReason == null) {
            out += "🤖 Step 1: слідів немає (storyIntel вимкнено або записи вже прибрані ретеншном)"
        }
        return out
    }

    private val SPACES = Regex("""\s+""")
}
