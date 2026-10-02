package metifikys.telegram

import metifikys.ai.dedup.ShortlistRanker
import metifikys.config.DigestConfig
import metifikys.db.ArticleRecord
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.ExplicitSignal
import metifikys.db.ReactionCount
import metifikys.digest.MuteRegistry
import metifikys.feedback.AffinityMath
import metifikys.feedback.MuteSuggester
import metifikys.feeds.FeedQuality
import metifikys.format.EventTypeLabels
import metifikys.format.TopicFormatter
import metifikys.model.ArticleStatus
import metifikys.model.ShortlistItem
import metifikys.story.EventCluster
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Everything the inspector card shows about one channel post, loaded by [PostInspector] and
 * rendered by [PostCard.render]. Pure data; every part may be missing (older rows, retention).
 */
data class PostInspection(
    val category: String,
    val emoji: String,
    /** The post's FIRST message (a chunked topic has several). */
    val post: DigestMessageRow,
    val chunks: Int,
    val postUrl: String?,
    /** The story's post right before this one, when the story chain has one. */
    val previous: ChainPost? = null,
    /** How many posts of the story came after this one, and a link to the newest. */
    val laterCount: Int = 0,
    val latest: ChainPost? = null,
    val telegraphUrl: String? = null,
    /** The covered event (null: legacy post, or `covered_events` already pruned). */
    val event: CoveredEventRow? = null,
    /** `event_articles.step1_status` of the post's own article, when recorded. */
    val step1Status: String? = null,
    val digest: DigestConfig? = null,
    val sources: List<Source> = emptyList(),
    /** Story cluster (`event_articles`); null without storyIntel rows. */
    val cluster: EventCluster? = null,
    val reactions: List<ReactionCount> = emptyList(),
    val adminSignal: Int? = null,
    val affinity: List<AffinityCell> = emptyList(),
    /** Keyword mutes in scope that match one of the post's articles. */
    val keywordMutes: List<MuteRegistry.Entry> = emptyList(),
    /** The type mute covering the event's type in this category. */
    val typeMute: MuteRegistry.Entry? = null,
    /** How long `covered_events` rows live (`summaryHistory.retentionDays`), for the "pruned" note. */
    val eventRetentionDays: Long = 14
) {
    data class ChainPost(val sentAt: LocalDateTime, val url: String?)

    /**
     * One article of the post: its row (null when retention already deleted it), the feed's
     * display name, and the feed's window metrics (null without `feedHealth` data or for a feed
     * already shown above).
     */
    data class Source(val link: String, val article: ArticleRecord?, val feedName: String?, val feedStats: FeedQuality.FeedMetrics?)

    /** One `audience_affinity` row of the post's dimensions; [label] is the Ukrainian dimension name. */
    data class AffinityCell(val label: String, val key: String, val score: Double, val nTone: Double, val n: Double)
}

/** The inspector card: HTML text, keyboard and keyword candidates. Pure. */
object PostCard {

    /** Telegram's message limit is 4096; leave room for the HTML-to-plain fallback. */
    const val MAX_CHARS = 3900
    const val MAX_SOURCES = 5
    const val MAX_CANDIDATES = 4
    private const val TITLE_MAX = 90
    private const val BUTTON_LABEL_MAX = 24

    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    private fun h(s: String) = TopicFormatter.escapeHtml(s)
    private fun attr(s: String) = h(s).replace("\"", "&quot;")
    private fun link(url: String?, label: String) = if (url == null) h(label) else "<a href=\"${attr(url)}\">${h(label)}</a>"
    private fun cut(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
    private fun num(v: Double) = String.format(Locale.ROOT, "%+.2f", v).replace('-', '−')
    private fun one(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun pct(v: Double) = String.format(Locale.ROOT, "%.0f%%", v * 100)

    /** The card text (Telegram HTML), at most [MAX_CHARS] characters. */
    fun render(i: PostInspection): String {
        val head = mutableListOf<String>()
        val p = i.post
        val meta = buildList {
            add("${i.emoji.ifEmpty { "📰" }} ${h(i.category)}")
            add(p.sentAt.format(stamp))
            add(link(i.postUrl, "пост ↗"))
            if (i.chunks > 1) add("${i.chunks} повідомл.")
            p.variant?.let { add(variantLabel(it)) }
        }
        head += "🔎 " + meta.joinToString(" · ")
        i.previous?.let { head += "↩ оновлення до ${link(it.url, "попереднього посту ↗")} (${it.sentAt.format(stamp)})" }
        if (i.laterCount > 0) head += "↪ далі: ${i.laterCount} ${updatesWord(i.laterCount)} " + link(i.latest?.url, "↗")
        i.telegraphUrl?.let { head += "⚡ лонгрід: ${link(it, "Telegraph ↗")}" }

        val event = eventSection(i)
        val sources = i.sources.take(MAX_SOURCES).map { sourceBlock(it) }
        val hiddenSources = (i.sources.size - MAX_SOURCES).coerceAtLeast(0)
        val cluster = clusterLine(i)
        val audience = audienceSection(i)
        val filters = filterSection(i)
        val footer = "Своє слово: <code>/mute &lt;слово&gt; ${h(i.category)}</code>"

        // Sources are the long part: drop them from the end until the card fits.
        var shown = sources.size
        while (true) {
            val text = assemble(head, event, sources.take(shown), hiddenSources + sources.size - shown, cluster, audience, filters, footer)
            if (text.length <= MAX_CHARS || shown == 0) return hardCut(text)
            shown--
        }
    }

    private fun assemble(
        head: List<String>,
        event: List<String>,
        sources: List<List<String>>,
        hidden: Int,
        cluster: String?,
        audience: List<String>,
        filters: List<String>,
        footer: String
    ): String = buildString {
        append(head.joinToString("\n"))
        append("\n\n").append(event.joinToString("\n"))
        if (sources.isNotEmpty() || hidden > 0) {
            append("\n\n<b>Джерела</b>")
            sources.forEach { append('\n').append(it.joinToString("\n")) }
            if (hidden > 0) append("\n… ще $hidden")
        }
        cluster?.let { append("\n\n").append(it) }
        append("\n\n").append(audience.joinToString("\n"))
        append("\n\n").append(filters.joinToString("\n"))
        append("\n\n").append(footer)
    }

    /** Last resort: cut at a line boundary (every line is self-contained HTML). */
    private fun hardCut(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val lines = text.lines()
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.length + l.length + 2 > MAX_CHARS) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(l)
        }
        return sb.append("\n…").toString()
    }

    private fun variantLabel(v: String): String = when (v) {
        DigestMessageRow.VARIANT_EXPLORE -> "🧭 explore"
        DigestMessageRow.VARIANT_PERSONA -> "A/B: persona"
        DigestMessageRow.VARIANT_CONTROL -> "A/B: control"
        else -> "варіант: ${h(v)}"
    }

    /** 1–4 (not 11–14) → [few], else [many]: оновлення / оновлень, видання / видань. */
    private fun plural(n: Int, few: String, many: String) = if (n % 10 in 1..4 && n % 100 !in 11..14) few else many

    private fun updatesWord(n: Int): String = plural(n, "оновлення", "оновлень")

    private fun eventSection(i: PostInspection): List<String> {
        val p = i.post
        val out = mutableListOf<String>()
        val key = p.eventKey
        if (key == null) {
            out += "<b>Подія</b>: немає (legacy-пост без Step 1)"
            return out
        }
        out += "<b>Подія</b> <code>${h(key)}</code>"
        val e = i.event
        val status = statusLabel(i)
        if (e == null) {
            out += "дані події вже видалені (covered_events живе ${i.eventRetentionDays} дн.)" + (status?.let { " · статус: $it" } ?: "")
            return out
        }
        val who = listOfNotNull(
            e.subject.takeIf { it.isNotBlank() }?.let { h(it) },
            e.franchise.takeIf { it.isNotBlank() && !it.equals(e.subject, ignoreCase = true) }?.let { "франшиза: ${h(it)}" }
        )
        if (who.isNotEmpty()) out += who.joinToString(" · ")
        val typeCell = e.eventType.takeIf { it.isNotBlank() }?.let { "тип: ${h(EventTypeLabels.label(it))} (${h(it)})" }
        out += listOfNotNull(typeCell, status?.let { "статус: $it" }).joinToString(" · ").ifEmpty { "тип невідомий" }
        if (e.coreFact.isNotBlank()) out += "Факт: ${h(cut(e.coreFact, 300))}"
        val item = itemOf(e)
        val composite = ShortlistRanker.compositeScore(item, i.digest)
        out += "Оцінки: newsworthiness ${e.newsworthiness.takeIf { it > 0 } ?: "—"} · digestFit ${e.digestFit.takeIf { it > 0 } ?: "—"} · " +
            "composite ${one(composite)}"
        val why = whyPassed(i, e)
        if (why.isNotEmpty()) out += "Чому пройшла: " + why.joinToString("; ")
        return out
    }

    private fun statusLabel(i: PostInspection): String? {
        val related = i.post.relatedEventKey
        val raw = i.step1Status ?: if (related != null) "meaningful_update" else null
        return when (raw) {
            null -> null
            "new" -> "нова"
            "meaningful_update" -> "оновлення" + (related?.let { " до <code>${h(it)}</code>" } ?: "")
            else -> h(raw)
        }
    }

    /** The shortlist item a covered event stands for, as the ranker would score it. */
    private fun itemOf(e: CoveredEventRow) = ShortlistItem(
        eventKey = e.eventKey, subject = e.subject, franchise = e.franchise, eventType = e.eventType,
        coreFact = e.coreFact, importance = e.importance, newsworthiness = e.newsworthiness,
        digestFit = e.digestFit, url = e.url, status = "new"
    )

    /** Only what the stored data proves; anything else (ranker caps, affinity) is not guessed. */
    private fun whyPassed(i: PostInspection, e: CoveredEventRow): List<String> {
        val out = mutableListOf<String>()
        if (i.post.variant == DigestMessageRow.VARIANT_EXPLORE) out += "🧭 anti-bubble — важлива подія поза звичним смаком аудиторії"
        val news = if (e.newsworthiness > 0) e.newsworthiness else e.importance
        val d = i.digest ?: return out
        if (e.digestFit in 1 until d.minDigestFit && news >= d.newsworthinessOverride) {
            out += "digestFit ${e.digestFit} < minDigestFit ${d.minDigestFit}, але newsworthiness $news ≥ ${d.newsworthinessOverride}"
        }
        d.breakingNewsworthiness?.let { b -> if (news >= b) out += "newsworthiness $news ≥ поріг breaking $b" }
        return out
    }

    private fun sourceBlock(s: PostInspection.Source): List<String> {
        val a = s.article
        val out = mutableListOf<String>()
        val title = a?.title?.takeIf { it.isNotBlank() } ?: s.link
        out += "• ${link(s.link, cut(title, TITLE_MAX))}"
        val where = listOfNotNull(
            AffinityMath.sourceDomain(s.link).takeIf { it.isNotEmpty() }?.let { h(it) },
            s.feedName?.let { "стрічка ${h(it)}" }
        )
        if (where.isNotEmpty()) out += "  " + where.joinToString(" · ")
        if (a == null) {
            out += "  статті вже немає в БД (ретеншн)"
        } else {
            val taken = a.fetchedAt?.let { f -> " → взято ${f.format(stamp)} (${delta(a.pubDate, f)})" }.orEmpty()
            out += "  опубл. ${a.pubDate.format(stamp)}$taken"
            if (a.status != ArticleStatus.PROCESSED.name || a.dropReason != null) {
                out += "  статус: ${h(a.status)}" + (a.dropReason?.let { " · ${h(it)}" } ?: "")
            }
        }
        s.feedStats?.takeIf { it.decided > 0 }?.let { m ->
            out += "  📡 стрічка: ${m.total} ст. · опубл. ${pct(m.publishRate)} · сміття ${pct(m.junkRate)}"
        }
        return out
    }

    /** `+7 год` / `+25 хв` / `+3 дн` from the feed's date to our first sighting. */
    fun delta(from: LocalDateTime, to: LocalDateTime): String {
        val minutes = Duration.between(from, to).toMinutes()
        val sign = if (minutes < 0) "−" else "+"
        val m = abs(minutes)
        return when {
            m < 60 -> "$sign$m хв"
            m < 48 * 60 -> "$sign${m / 60} год"
            else -> "$sign${m / (24 * 60)} дн"
        }
    }

    private fun clusterLine(i: PostInspection): String? {
        val c = i.cluster ?: return null
        if (c.members.isEmpty()) return null
        val first = c.earliest
        val reprints = c.members.count { it.role == metifikys.db.EventArticleRole.LAYER2 || it.step1Status == "duplicate" }
        val parts = mutableListOf("${c.outlets} ${outletsWord(c.outlets)}")
        if (first != null) parts += "першим ${h(c.domainOf(first))} (${EventCluster.timeOf(first).format(stamp)})"
        parts += "передруків $reprints"
        return "<b>Кластер</b>: " + parts.joinToString(" · ")
    }

    private fun outletsWord(n: Int): String = plural(n, "видання", "видань")

    private fun audienceSection(i: PostInspection): List<String> {
        val out = mutableListOf("<b>Аудиторія</b>")
        out += if (i.reactions.isEmpty()) "Реакції: немає" else
            "Реакції: " + i.reactions.take(8).joinToString(" · ") { "${h(emojiLabel(it.emoji))} ${it.count}" }
        when (i.adminSignal) {
            ExplicitSignal.MORE -> out += "Твій сигнал: 👍 більше такого"
            ExplicitSignal.LESS -> out += "Твій сигнал: 👎 менше такого"
            ExplicitSignal.DELETED -> out += "Твій сигнал: 🗑 видалено"
        }
        if (i.affinity.isNotEmpty()) {
            out += "Affinity: " + i.affinity.joinToString(" · ") { c ->
                "${c.label} ${h(cut(c.key, 40))} ${num(c.score)} (n ${one(c.nTone)}/${one(c.n)})"
            }
        }
        return out
    }

    private fun emojiLabel(e: String): String = when {
        e.startsWith("custom:") -> "custom"
        e == "paid" -> "⭐"
        else -> e
    }

    private fun filterSection(i: PostInspection): List<String> {
        val out = mutableListOf("<b>Фільтри</b>")
        for (m in i.keywordMutes.take(5)) {
            val origin = if (m.origin == MuteRegistry.Origin.DB) "бот" else "config.yaml"
            out += "🔇 уже глушить «${h(m.keyword)}» (${m.category?.let { h(it) } ?: "глобально"} · $origin)"
        }
        i.typeMute?.let { out += "🔇 тип «${h(EventTypeLabels.label(it.keyword))}» зам'ючено в ${h(i.category)}" }
        if (i.keywordMutes.isEmpty() && i.typeMute == null) out += "✅ жоден mute зараз не зачіпає цей пост"
        return out
    }

    // ── Keyword candidates and the keyboard ───────────────────────────────────

    /** Common title words that would mute half a channel, on top of [MuteSuggester.STOP_WORDS]. */
    private val TITLE_STOP = setOf(
        "about", "after", "again", "against", "also", "company", "companies", "confirm", "confirmed", "confirms", "patch", "patches", "team", "teams", "announce", "announced", "announcement", "announces", "available",
        "before", "being", "best", "coming", "could", "date", "dates", "details", "developer", "developers", "during",
        "early", "access", "edition", "exclusive", "exclusively", "finally", "first", "from", "full", "gameplay", "gets",
        "have", "here", "interview", "into", "just", "last", "later", "launch", "launched", "launches", "live", "more",
        "most", "next", "official", "officially", "over", "part", "players", "player", "preview", "price", "publisher",
        "release", "released", "releases", "report", "reportedly", "reveal", "revealed", "reveals", "review", "rumor",
        "says", "season", "series", "showcase", "shows", "studio", "than", "that", "their", "them", "then", "there",
        "these", "they", "this", "those", "today", "trailer", "under", "until", "upcoming", "video", "were", "what",
        "when", "where", "which", "while", "will", "with", "would", "year", "years", "your", "week", "million", "billion",
        "xbox", "playstation", "nintendo", "switch", "steam", "android", "windows", "apple", "google", "free", "sale",
        "який", "яка", "які", "яке", "щодо", "після", "перед", "через", "буде", "було", "була", "були", "нова", "новий",
        "нові", "нове", "вийде", "вийшла", "вийшов", "вийшли", "оновлення", "трейлер", "анонс", "анонсувала",
        "анонсував", "анонсували", "реліз", "релізу", "гра", "ігри", "гри", "гру", "також", "тепер", "отримала",
        "отримав", "отримає", "показала", "показав", "дата", "дату", "року", "році", "серія", "версія", "студія"
    )

    private val WORD = Regex("""[\p{L}\p{N}]+""")

    /**
     * Up to [max] mute keywords for a post: the franchise's first ` / ` segment, the subject (both
     * through [MuteSuggester.keywordFor], so short and generic keys drop out the same way), then
     * distinctive title words — 4+ characters, not a stop word or a number, not already part of
     * an earlier candidate. Lowercased, each a valid mute ([MuteRegistry.validate]).
     */
    fun keywordCandidates(franchise: String?, subject: String?, titles: List<String>, max: Int = MAX_CANDIDATES): List<String> {
        val out = mutableListOf<String>()
        fun add(kw: String?) {
            val k = kw?.trim()?.lowercase() ?: return
            if (out.size >= max || MuteRegistry.validate(k) != null || out.any { it == k }) return
            out += k
        }
        franchise?.takeIf { it.isNotBlank() }?.let { add(MuteSuggester.keywordFor(it)) }
        subject?.takeIf { it.isNotBlank() }?.let { add(MuteSuggester.keywordFor(it)) }
        for (t in titles) {
            for (m in WORD.findAll(t)) {
                if (out.size >= max) return out
                val w = m.value.lowercase()
                if (w.length < 4 || w.all { it.isDigit() } || w in TITLE_STOP || w in MuteSuggester.STOP_WORDS) continue
                if (out.any { phrase -> w in WORD.findAll(phrase).map { it.value } }) continue
                add(w)
            }
        }
        return out
    }

    /** 4 hex digits of [keyword], so a press can tell its candidate list has not changed since. */
    fun tag(keyword: String): String = "%04x".format(keyword.lowercase().hashCode() and 0xffff)

    /** What the main keyboard shows; [muted] per candidate keyword. */
    data class KeyboardState(
        val chatId: Long,
        val messageId: Long,
        val category: String,
        val candidates: List<Pair<String, Boolean>>,
        /** The event's (normalized) type and whether it is muted in the category; null = no type. */
        val eventType: Pair<String, Boolean>?,
        val note: String? = null
    )

    fun keyboard(s: KeyboardState): List<List<InlineButton>> {
        val ids = "${s.chatId}:${s.messageId}"
        val rows = mutableListOf<List<InlineButton>>()
        s.candidates.mapIndexed { idx, (kw, muted) ->
            val label = cut(kw, BUTTON_LABEL_MAX)
            if (muted) InlineButton("🔇 $label ✓", "in:noop:$ids") else InlineButton("🔇 $label ▸", "in:k:$ids:$idx:${tag(kw)}")
        }.chunked(2).forEach { rows += it }
        s.eventType?.let { (type, muted) ->
            val label = cut(EventTypeLabels.label(type), BUTTON_LABEL_MAX)
            rows += listOf(
                if (muted) InlineButton("🔇 тип: $label ✓", "in:noop:$ids") else InlineButton("🔇 тип: $label ▸", "in:t:$ids")
            )
        }
        s.note?.let { rows += listOf(InlineButton(it, "in:noop:$ids")) }
        return rows
    }

    /** In-category / global / cancel for keyword candidate [idx]. */
    fun keywordConfirm(chatId: Long, messageId: Long, category: String, idx: Int, keyword: String): List<List<InlineButton>> {
        val ids = "$chatId:$messageId"
        val t = tag(keyword)
        return listOf(
            listOf(
                InlineButton("🔇 «${cut(keyword, 16)}» у $category", "in:kc:$ids:$idx:$t"),
                InlineButton("🌐 Глобально", "in:kg:$ids:$idx:$t")
            ),
            listOf(InlineButton("↩ Скасувати", "in:back:$ids"))
        )
    }

    /** Confirm / cancel for muting the event type in the category. */
    fun typeConfirm(chatId: Long, messageId: Long, category: String, eventType: String): List<List<InlineButton>> {
        val ids = "$chatId:$messageId"
        return listOf(
            listOf(InlineButton("🔇 Усі «${cut(EventTypeLabels.label(eventType), 20)}» у $category", "in:tc:$ids")),
            listOf(InlineButton("↩ Скасувати", "in:back:$ids"))
        )
    }
}
