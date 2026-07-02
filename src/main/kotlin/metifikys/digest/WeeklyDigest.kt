package metifikys.digest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.ai.BillingException
import metifikys.ai.Embedder
import metifikys.ai.LlmClientsFactory
import metifikys.config.AppConfig
import metifikys.config.WeeklyConfig
import metifikys.db.CoveredEventRow
import metifikys.db.NewsDatabase
import metifikys.db.RejectedEventRow
import metifikys.format.TopicFormatter
import metifikys.model.Article
import metifikys.telegram.TelegramSender
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * One event participating in the weekly clustering pass. `isCovered` distinguishes a canonical
 * delivered event (from `covered_events`, a valid story representative) from a later duplicate
 * detection (from `rejected_events`, only a repeat-count signal). [vector] is L2-normalized.
 */
data class WeeklyEvent(
    val eventKey: String,
    val subject: String,
    val franchise: String,
    val eventType: String,
    val coreFact: String,
    val url: String,
    val newsworthiness: Int,
    val importance: Int,
    val digestFit: Int,
    val coveredAt: LocalDateTime?,
    val isCovered: Boolean,
    val vector: FloatArray
)

/**
 * A merged story for the weekly roundup. [mentionCount] is the cluster size — the canonical
 * coverage plus every near-duplicate phrasing and later duplicate detection — i.e. the
 * "how many times did this story show up this week" measure the ranking is built on.
 */
data class WeeklyStory(
    val eventKey: String,
    val subject: String,
    val franchise: String,
    val eventType: String,
    val coreFact: String,
    val url: String,
    val newsworthiness: Int,
    val importance: Int,
    val digestFit: Int,
    val mentionCount: Int,
    val coveredAt: LocalDateTime?,
    /** A few alternative phrasings/headlines for the same story, for the LLM's context. */
    val alsoReportedAs: List<String>
)

/**
 * Pure greedy clusterer. Covered events (the only valid representatives) are seeded
 * highest-newsworthiness-first; each subsequent event — covered or duplicate — joins the nearest
 * existing cluster when their cosine similarity meets `threshold`, otherwise (for a covered event)
 * starts a new cluster. Duplicate events with no covered cluster within threshold are dropped: a
 * story we never covered has no representative to post. Output is sorted most-repeated-first.
 */
object WeeklyClusterer {

    private class Cluster(val rep: WeeklyEvent) {
        val members = mutableListOf(rep)
    }

    fun cluster(events: List<WeeklyEvent>, threshold: Double): List<WeeklyStory> {
        val covered = events.filter { it.isCovered }
            .sortedWith(
                compareByDescending<WeeklyEvent> { it.newsworthiness }
                    .thenByDescending { it.importance }
                    .thenByDescending { it.coveredAt ?: LocalDateTime.MIN }
            )
        val duplicates = events.filter { !it.isCovered }
        val clusters = mutableListOf<Cluster>()

        for (ev in covered) {
            val best = nearestWithinThreshold(clusters, ev, threshold)
            if (best != null) best.members.add(ev) else clusters.add(Cluster(ev))
        }
        for (dup in duplicates) {
            nearestWithinThreshold(clusters, dup, threshold)?.members?.add(dup)
        }

        return clusters.map { c ->
            val others = c.members.asSequence()
                .filter { it !== c.rep }
                .map { it.subject.trim() }
                .filter { it.isNotEmpty() && !it.equals(c.rep.subject.trim(), ignoreCase = true) }
                .distinct()
                .take(8)
                .toList()
            WeeklyStory(
                eventKey = c.rep.eventKey,
                subject = c.rep.subject,
                franchise = c.rep.franchise,
                eventType = c.rep.eventType,
                coreFact = c.rep.coreFact,
                url = c.rep.url,
                newsworthiness = c.rep.newsworthiness,
                importance = c.rep.importance,
                digestFit = c.rep.digestFit,
                mentionCount = c.members.size,
                coveredAt = c.rep.coveredAt,
                alsoReportedAs = others
            )
        }.sortedWith(
            compareByDescending<WeeklyStory> { it.mentionCount }
                .thenByDescending { it.newsworthiness }
                .thenByDescending { it.importance }
        )
    }

    private fun nearestWithinThreshold(clusters: List<Cluster>, ev: WeeklyEvent, threshold: Double): Cluster? {
        var best: Cluster? = null
        var bestSim = threshold
        for (c in clusters) {
            val sim = VectorMath.cosine(c.rep.vector, ev.vector)
            if (sim >= bestSim) {
                bestSim = sim
                best = c
            }
        }
        return best
    }
}

/**
 * Splits a list of embedding inputs into consecutive sub-lists each under a total-character budget,
 * so a large batch (the article fallback can embed up to `maxEvents` texts) is sent as several
 * requests instead of one that exceeds the provider's per-request token cap. Pure and order-preserving:
 * a single over-budget text is emitted alone rather than dropped.
 */
object WeeklyEmbedChunker {
    fun chunk(texts: List<String>, maxChars: Int): List<List<String>> {
        if (texts.isEmpty()) return emptyList()
        val result = mutableListOf<List<String>>()
        var i = 0
        while (i < texts.size) {
            var end = i
            var chars = 0
            while (end < texts.size) {
                val next = texts[end].length
                if (end > i && chars + next > maxChars) break
                chars += next
                end++
            }
            result.add(texts.subList(i, end))
            i = end
        }
        return result
    }
}

/**
 * Weekly "top story of the week" roundup. For each participating category it ranks the week's
 * covered events by how many times each story was seen (canonical coverage + later duplicate
 * detections + near-duplicate phrasings merged via embeddings), hands the top candidates to the
 * render LLM, and posts the roundup to the category's own channel.
 *
 * IO-bound; the pure ranking lives in [WeeklyClusterer] and is unit-tested independently. Every
 * per-category failure is contained and logged — one bad category never aborts the others.
 */
class WeeklyDigest(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val llmClientsFactory: LlmClientsFactory,
    private val embedder: Embedder,
    private val sender: TelegramSender,
    private val weekly: WeeklyConfig = config.weekly ?: WeeklyConfig(),
    private val baseDir: File = File(".").canonicalFile
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /** Categories that participate: the explicit allowlist, else every category with a `dedup:` block. */
    private fun participatingCategories(): List<String> =
        if (weekly.categories.isNotEmpty()) {
            weekly.categories.filter { config.categories.containsKey(it) }
        } else {
            config.categories.filter { it.value.dedup != null }.keys.toList()
        }

    /** Runs the weekly roundup for every participating category. Called by the weekly scheduler. */
    fun run() {
        val cats = participatingCategories()
        if (cats.isEmpty()) {
            logger.info { "[Weekly] No participating categories (need a dedup: block, or a valid weekly.categories list). Skipping." }
            return
        }
        logger.info { "[Weekly] Starting weekly roundup for ${cats.size} category(ies): $cats" }
        for (name in cats) {
            try {
                runForCategory(name)
            } catch (e: Exception) {
                logger.error(e) { "[Weekly:$name] failed — other categories unaffected." }
            }
        }
        logger.info { "[Weekly] Weekly roundup finished." }
    }

    /**
     * Gathers, clusters, ranks, renders, and posts the roundup for a single category.
     *
     * Event path (categories with a `dedup:` block): rank the week's covered events + duplicate
     * detections. Article fallback (event-less categories such as a legacy single-step one):
     * when there are no covered events, rank the week's raw articles instead, with a wider
     * candidate pool so a low-duplication (e.g. single-source) category still hands the LLM a
     * broad weekly sample to pick the most significant stories from.
     */
    fun runForCategory(name: String) {
        val catCfg = config.categories[name] ?: return

        val covered = db.fetchRecentEvents(name, weekly.lookbackDays, weekly.maxEvents)
        val events: List<WeeklyEvent>
        val poolSize: Int
        if (covered.isEmpty()) {
            val articles = db.fetchRecentArticles(name, weekly.lookbackDays, weekly.maxEvents)
            if (articles.isEmpty()) {
                logger.info { "[Weekly:$name] No covered events and no recent articles in the last ${weekly.lookbackDays}d. Skipping." }
                return
            }
            logger.info { "[Weekly:$name] No covered events — article-level fallback over ${articles.size} article(s)." }
            events = buildEventsFromArticles(name, articles) ?: return
            poolSize = weekly.articleCandidatePoolSize
        } else {
            val duplicates = db.fetchRecentRejectedEvents(name, weekly.lookbackDays, weekly.maxEvents)
            logger.info { "[Weekly:$name] ${covered.size} covered + ${duplicates.size} duplicate event(s) (event path)." }
            events = buildEvents(name, covered, duplicates) ?: return
            poolSize = weekly.candidatePoolSize
        }

        val stories = WeeklyClusterer.cluster(events, weekly.clusterThreshold)
        if (stories.isEmpty()) {
            logger.info { "[Weekly:$name] No story clusters formed. Skipping." }
            return
        }

        // Prefer stories that actually repeated; if nothing reached minMentions, fall back to the
        // strongest stories so the weekly post still goes out.
        val repeated = stories.filter { it.mentionCount >= weekly.minMentions }
        val candidates = (repeated.ifEmpty {
            logger.info { "[Weekly:$name] Nothing reached minMentions=${weekly.minMentions}; falling back to top stories." }
            stories
        }).take(poolSize)

        logger.info {
            "[Weekly:$name] ${stories.size} clusters; top=${stories.take(5).map { "${it.subject.take(40)}×${it.mentionCount}" }}"
        }

        val post = renderPost(name, catCfg.emoji, candidates) ?: return
        deliver(name, catCfg.channelId, post, candidates.associateBy { it.url })
    }

    /**
     * Embeds [texts] via [weekly.embeddingModel], split into sub-requests that stay under the
     * provider's per-request token cap (the article fallback can embed up to `maxEvents` texts at
     * once — a single call blows past OpenAI's 300k-tokens-per-request limit). Chunks by total
     * character budget (worst-case ~1 token/char keeps each request safely under the cap) and
     * concatenates the vectors in input order. Returns null (fail-open) on a billing/quota limit,
     * any embed failure, or a result-size mismatch — a transient error must never produce a
     * partial/empty roundup.
     */
    private fun embedAll(name: String, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val chunks = WeeklyEmbedChunker.chunk(texts, EMBED_CHARS_PER_REQUEST)
        if (chunks.size > 1) logger.info { "[Weekly:$name] embedding ${texts.size} text(s) in ${chunks.size} request(s)." }
        val out = ArrayList<FloatArray>(texts.size)
        for (chunk in chunks) {
            val vectors = try {
                embedder.embed(chunk, weekly.embeddingModel)
            } catch (e: BillingException) {
                logger.warn { "[Weekly:$name] billing/quota limit while embedding — skipping category." }
                return null
            } catch (e: Exception) {
                logger.warn(e) { "[Weekly:$name] embedding failed — skipping category." }
                return null
            }
            if (vectors.size != chunk.size) {
                logger.warn { "[Weekly:$name] expected ${chunk.size} vectors, got ${vectors.size} — skipping." }
                return null
            }
            out += vectors
        }
        return out
    }

    /**
     * Article-level fallback assembler: maps each recent [Article] to a [WeeklyEvent] with
     * `isCovered=true` and zero scores, so every article is a cluster representative and
     * [WeeklyClusterer] ranks purely by how many articles cover the same story. `coreFact` prefers
     * the LLM summary, falling back to the raw description.
     */
    private fun buildEventsFromArticles(name: String, articles: List<Article>): List<WeeklyEvent>? {
        val texts = articles.map { embedText(it.title, it.summary ?: it.description, it.link) }
        val vectors = embedAll(name, texts) ?: return null
        return articles.mapIndexed { i, a ->
            WeeklyEvent(
                eventKey = a.link,
                subject = a.title,
                franchise = "",
                eventType = "",
                coreFact = (a.summary?.takeIf { it.isNotBlank() } ?: a.description).trim(),
                url = a.link,
                newsworthiness = 0,
                importance = 0,
                digestFit = 0,
                coveredAt = a.pubDate,
                isCovered = true,
                vector = VectorMath.l2Normalize(vectors[i])
            )
        }
    }

    /** Embeds covered + duplicate events in one call and assembles normalized [WeeklyEvent]s. */
    private fun buildEvents(
        name: String,
        covered: List<CoveredEventRow>,
        duplicates: List<RejectedEventRow>
    ): List<WeeklyEvent>? {
        val coveredTexts = covered.map { embedText(it.subject, it.coreFact, it.eventKey) }
        val dupTexts = duplicates.map { embedText(it.subject, it.coreFact, it.eventKey) }
        val vectors = embedAll(name, coveredTexts + dupTexts) ?: return null

        val coveredEvents = covered.mapIndexed { i, row ->
            WeeklyEvent(
                eventKey = row.eventKey,
                subject = row.subject,
                franchise = row.franchise,
                eventType = row.eventType,
                coreFact = row.coreFact,
                url = row.url,
                newsworthiness = row.newsworthiness,
                importance = row.importance,
                digestFit = row.digestFit,
                coveredAt = row.coveredAt,
                isCovered = true,
                vector = VectorMath.l2Normalize(vectors[i])
            )
        }
        val dupEvents = duplicates.mapIndexed { i, row ->
            WeeklyEvent(
                eventKey = row.eventKey,
                subject = row.subject,
                franchise = row.franchise,
                eventType = row.eventType,
                coreFact = row.coreFact,
                url = row.url,
                newsworthiness = row.newsworthiness,
                importance = row.importance,
                digestFit = row.digestFit,
                coveredAt = null,
                isCovered = false,
                vector = VectorMath.l2Normalize(vectors[covered.size + i])
            )
        }
        return coveredEvents + dupEvents
    }

    /** Builds the prompt, calls the render LLM, and returns its raw post text (null on failure). */
    private fun renderPost(name: String, emoji: String, candidates: List<WeeklyStory>): String? {
        val prompts = resolvePrompts()
        val candidateJson = json.encodeToString(candidates.mapIndexed { i, s ->
            WeeklyCandidateJson(
                rank = i + 1,
                subject = s.subject,
                franchise = s.franchise,
                eventType = s.eventType,
                // Cap: event coreFacts are short, but article descriptions can be long and the
                // article path uses a larger candidate pool — keep the prompt bounded.
                coreFact = s.coreFact.take(MAX_CANDIDATE_COREFACT_CHARS),
                url = s.url,
                mentions = s.mentionCount,
                newsworthiness = s.newsworthiness,
                importance = s.importance,
                digestFit = s.digestFit,
                alsoReportedAs = s.alsoReportedAs
            )
        })
        val vars = mapOf(
            "CATEGORY" to name,
            "EMOJI" to emoji,
            "TOP_N" to weekly.topN.toString(),
            "WEEK_RANGE" to weekRange(),
            "CANDIDATES_JSON" to candidateJson
        )
        val system = substitute(prompts.first, vars)
        val user = substitute(prompts.second, vars)

        return try {
            val out = llmClientsFactory.forRender(config.categories[name]).complete(system, user)
            if (out.isBlank()) {
                logger.warn { "[Weekly:$name] LLM returned blank post — skipping." }
                null
            } else out
        } catch (e: BillingException) {
            logger.warn { "[Weekly:$name] billing/quota limit while rendering — skipping." }
            null
        } catch (e: Exception) {
            logger.warn(e) { "[Weekly:$name] render LLM call failed — skipping." }
            null
        }
    }

    /**
     * Post-processes the LLM output and posts it as one message:
     *  - the leading (pre-first-bullet) header line is kept verbatim;
     *  - any bullet whose link is not a candidate URL is dropped (the prompt-injection guard
     *    [DigestDeliverer] also applies);
     *  - each surviving bullet gets a "🔁 N" mention badge, matched to its story by URL (so the
     *    count is exact regardless of how the LLM phrased the bullet) when `weekly.showMentions`;
     *  - `weekly.hashtag`, when set, is appended on its own line at the end.
     *
     * [TopicFormatter.toHtml] (invoked inside [TelegramSender.sendToChannel]) renders every
     * `[label](url)` link, so the multi-link message formats correctly as a single post.
     */
    private fun deliver(name: String, channelId: String, post: String, candidatesByUrl: Map<String, WeeklyStory>) {
        val allowedUrls = candidatesByUrl.keys
        val segments = post.split("•").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            logger.warn { "[Weekly:$name] LLM post had no content — skipping." }
            return
        }

        val out = StringBuilder()
        var bullets = 0
        for ((i, seg) in segments.withIndex()) {
            val urls = TopicFormatter.extractUrls(seg)
            // The first segment before any bullet, if it carries no link, is the header line.
            if (i == 0 && urls.isEmpty()) {
                out.append(seg)
                continue
            }
            val foreign = urls - allowedUrls
            if (foreign.isNotEmpty()) {
                logger.warn { "[Weekly:$name] dropping bullet with non-candidate URL(s) $foreign — possible prompt injection." }
                continue
            }
            val story = urls.firstNotNullOfOrNull { candidatesByUrl[it] }
            val bullet = if (weekly.showMentions && story != null) "$seg 🔁 ${story.mentionCount}" else seg
            if (out.isNotEmpty()) out.append("\n\n")
            out.append("• ").append(bullet)
            bullets++
        }

        if (bullets == 0) {
            logger.warn { "[Weekly:$name] no postable bullets after filtering — skipping post." }
            return
        }
        weekly.hashtag?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append("\n\n").append(it) }

        val sent = sender.sendToChannel(channelId, out.toString(), disablePreview = true).isNotEmpty()
        if (sent) {
            logger.info { "[Weekly:$name] weekly roundup posted to $channelId ($bullets bullet(s))." }
        } else {
            logger.warn { "[Weekly:$name] failed to post weekly roundup to $channelId." }
        }
    }

    private fun embedText(subject: String, coreFact: String, eventKey: String): String {
        val full = buildString {
            append(subject.trim())
            if (coreFact.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(coreFact.trim())
            }
        }.ifBlank { eventKey }
        return if (full.length <= MAX_EMBED_INPUT_CHARS) full else full.take(MAX_EMBED_INPUT_CHARS)
    }

    private fun weekRange(): String {
        val end = LocalDate.now()
        val start = end.minusDays(weekly.lookbackDays)
        val fmt = DateTimeFormatter.ofPattern("dd.MM")
        return "${start.format(fmt)} – ${end.format(fmt)}"
    }

    private fun substitute(template: String, vars: Map<String, String>): String {
        var result = template
        for ((k, v) in vars) result = result.replace("{{$k}}", v)
        return result
    }

    /** Resolves (system, user) prompts: inline override → prompt file → built-in default. */
    private fun resolvePrompts(): Pair<String, String> {
        val fileDoc = weekly.promptFile?.let { loadPromptFile(it) }
        val system = weekly.prompts?.system?.takeIf { it.isNotBlank() }
            ?: fileDoc?.first?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SYSTEM
        val user = weekly.prompts?.user?.takeIf { it.isNotBlank() }
            ?: fileDoc?.second?.takeIf { it.isNotBlank() }
            ?: DEFAULT_USER
        return system to user
    }

    private fun loadPromptFile(rawPath: String): Pair<String?, String?>? {
        if (rawPath.contains("..")) {
            logger.warn { "[Weekly] Rejecting prompt file path with '..': $rawPath" }
            return null
        }
        val file = if (File(rawPath).isAbsolute) File(rawPath) else File(baseDir, rawPath)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            logger.warn { "[Weekly] Prompt file missing or unreadable: ${file.absolutePath} — using defaults." }
            return null
        }
        return try {
            val root = yamlMapper.readTree(file) ?: return null
            root.at("/weekly/system").asText(null)?.takeIf { it.isNotEmpty() } to
                root.at("/weekly/user").asText(null)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.warn(e) { "[Weekly] Failed to parse prompt file ${file.absolutePath} — using defaults." }
            null
        }
    }

    @Serializable
    private data class WeeklyCandidateJson(
        val rank: Int,
        val subject: String,
        val franchise: String,
        val eventType: String,
        val coreFact: String,
        val url: String,
        val mentions: Int,
        val newsworthiness: Int,
        val importance: Int,
        val digestFit: Int,
        val alsoReportedAs: List<String>
    )

    companion object {
        private const val MAX_EMBED_INPUT_CHARS = 6000

        /** Per-candidate `coreFact` cap in the LLM prompt JSON (article descriptions can be long). */
        private const val MAX_CANDIDATE_COREFACT_CHARS = 400

        /**
         * Max characters per embeddings request. Embeddings APIs cap tokens per request (OpenAI:
         * 300k); worst-case ~1 token/char keeps a chunk this size safely under that, with margin.
         */
        private const val EMBED_CHARS_PER_REQUEST = 200_000

        /**
         * Built-in weekly editor prompt (Ukrainian, matching the channels' house style). Overridable
         * via `weekly.promptFile` (keys `weekly.system` / `weekly.user`) or inline `weekly.prompts`.
         */
        val DEFAULT_SYSTEM: String = """
            Ти — головний редактор україномовного Telegram-каналу. Раз на тиждень ти готуєш дайджест
            «Головне за тиждень» — підсумок найважливіших та найбільш обговорюваних новин.

            На вхід ти отримуєш CANDIDATES — заздалегідь дедуплікований список подій тижня. Поле
            `mentions` показує, скільки разів цю історію згадували різні джерела за тиждень: більше
            згадувань → ширший резонанс. Поле `newsworthiness` — редакційна вага самої події.

            Завдання:
              1. Обери {{TOP_N}} НАЙВАЖЛИВІШИХ історій. Орієнтуйся передусім на резонанс (`mentions`)
                 та вагу (`newsworthiness`); відкидай дрібні або вузькі теми.
              2. Впорядкуй їх від головної до менш значущої.
              3. Напиши короткий, щільний дайджест — по одному пункту на історію.

            ФОРМАТ (кожен пункт у такому вигляді, нічого зайвого):

                • <емодзі> **<Коротка тема українською>.** 1–2 речення суті. [джерело](url)

            Правила:
              - Пиши українською. Заголовки — описові іменникові фрази, без сенсаційних дієслів.
              - Рівно одне посилання на пункт, у формі [джерело](url), і ТІЛЬКИ той `url`, що належить
                цій історії. Ніколи не вигадуй і не змішуй посилання.
              - 1–2 речення на пункт. Розділювач між пунктами — символ «•».
              - Почни з короткого рядка-заголовка: «{{EMOJI}} **Головне за тиждень** ({{WEEK_RANGE}})»,
                далі — пункти. Заголовок без посилання.
              - Жодного JSON, жодних коментарів до чи після дайджесту — лише готовий текст для каналу.
        """.trimIndent()

        val DEFAULT_USER: String = """
            Категорія: {{CATEGORY}} {{EMOJI}}
            Період: {{WEEK_RANGE}}
            Скільки історій відібрати: {{TOP_N}}

            CANDIDATES (відсортовані за кількістю згадувань):
            {{CANDIDATES_JSON}}
        """.trimIndent()
    }
}
