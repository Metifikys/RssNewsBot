package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import metifikys.ai.LlmClient
import metifikys.ai.LlmJson
import metifikys.ai.PromptBuilder
import metifikys.config.CategoryConfig
import metifikys.config.UpdateDiffConfig
import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * `dedup.digest.updateDiff` (see [UpdateDiffConfig]): checks every `meaningful_update` of the final
 * render shortlist against what the channel already posted about the story, and keeps it only
 * with the facts that are new. One LLM call per update; fail-open — any failure keeps the item.
 */
class UpdateDiff(
    private val db: NewsDatabase,
    /** The diff client of a category (`LlmClientsFactory.forUpdateDiff`, metered as UPDATE_DIFF). */
    private val clientFor: (CategoryConfig) -> LlmClient,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    /** Runs the call so a slow provider can be abandoned after `timeoutSeconds`. */
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "update-diff").also { it.isDaemon = true }
    }
) {

    /**
     * The model's answer. [useful] is null when the answer lacks it (treated as unparseable);
     * after [parse] it is true only with at least one entry in [newFacts].
     */
    @Serializable
    data class Decision(val useful: Boolean? = null, val newFacts: List<String> = emptyList(), val reason: String = "")

    data class Prompts(val system: String, val user: String)

    /** One earlier post of the story as the prompt shows it. */
    data class PreviousPost(val sentAt: LocalDateTime, val text: String)

    /**
     * [shortlist] with every `meaningful_update` checked: dropped when the model finds no new
     * fact, kept with `newFacts` (and never long-form) when it finds some, kept as it was on any
     * failure or in `logOnly` mode. Without an enabled `updateDiff` block, or without updates in
     * the shortlist, returns [shortlist] itself and makes no call. [prompts] null = built-in.
     */
    fun apply(
        category: String,
        categoryConfig: CategoryConfig,
        shortlist: List<ShortlistItem>,
        articles: List<Article>,
        prompts: Prompts? = null
    ): List<ShortlistItem> {
        val cfg = categoryConfig.dedup?.digest?.updateDiff?.takeIf { it.enabled } ?: return shortlist
        if (shortlist.none { it.status == STATUS_UPDATE }) return shortlist
        val tag = "[Category:$category][UpdateDiff]"
        val out = ArrayList<ShortlistItem>(shortlist.size)
        for (item in shortlist) {
            if (item.status != STATUS_UPDATE || Thread.currentThread().isInterrupted) {
                out += item
                continue
            }
            val decision = decide(category, categoryConfig, cfg, item, articles, prompts ?: DEFAULT_PROMPTS, tag)
            when {
                decision == null -> out += item
                cfg.logOnly -> {
                    logger.info {
                        "$tag[LOG-ONLY] ${item.eventKey} useful=${decision.useful} newFacts=${decision.newFacts} reason=${decision.reason}"
                    }
                    out += item
                }
                decision.useful != true -> logger.info { "$tag skip ${item.eventKey}: ${decision.reason.ifBlank { "no new facts" }}" }
                else -> {
                    logger.info { "$tag keep ${item.eventKey}: ${decision.newFacts.size} new fact(s) ${decision.newFacts} (${decision.reason})" }
                    out += item.copy(newFacts = decision.newFacts, depth = null)
                }
            }
        }
        return out
    }

    /** The decision for one update, or null (logged) when there is nothing to compare or the call failed. */
    private fun decide(
        category: String,
        categoryConfig: CategoryConfig,
        cfg: UpdateDiffConfig,
        item: ShortlistItem,
        articles: List<Article>,
        prompts: Prompts,
        tag: String
    ): Decision? {
        val previous = try {
            previousPosts(category, item, cfg)
        } catch (e: Exception) {
            logger.warn(e) { "$tag ${item.eventKey}: earlier-post lookup failed — kept as is" }
            return null
        }
        if (previous.isEmpty()) {
            logger.info { "$tag ${item.eventKey}: no earlier post of the story (related=${item.relatedPreviousEventKey}) — kept as is" }
            return null
        }
        val sources = sourcesText(item, articles, cfg.maxSourceChars)
        if (sources.isBlank()) {
            logger.info { "$tag ${item.eventKey}: no source text in the batch — kept as is" }
            return null
        }
        val vars = mapOf(
            "CATEGORY" to category,
            "EVENT_KEY" to item.eventKey,
            "SUBJECT" to item.subject,
            "CORE_FACT" to item.coreFact,
            "PREVIOUS_POSTS" to formatPrevious(previous),
            "NEW_SOURCES" to sources
        )
        val raw = try {
            val client = clientFor(categoryConfig)
            val future = executor.submit<String> { client.completeJson(substitute(prompts.system, vars), substitute(prompts.user, vars), 1) }
            try {
                future.get(cfg.timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                logger.warn { "$tag ${item.eventKey}: no answer within ${cfg.timeoutSeconds}s — kept as is" }
                return null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            logger.warn { "$tag ${item.eventKey}: call failed (${cause.message?.lineSequence()?.firstOrNull() ?: cause.javaClass.simpleName}) — kept as is" }
            return null
        }
        val decision = parse(raw.orEmpty())
        if (decision == null) {
            logger.warn { "$tag ${item.eventKey}: unparseable answer — kept as is. Raw (truncated): ${raw.orEmpty().take(300)}" }
        }
        return decision
    }

    /**
     * The story's last `maxPreviousPosts` posts within `windowDays`, oldest first: the posted text,
     * or — for a post written before `digest_messages.text` existed — the covered event's subject +
     * coreFact. No post at all (pruned, or posted without an event key) → the covered events of
     * the item's own and related key, the same way.
     */
    internal fun previousPosts(category: String, item: ShortlistItem, cfg: UpdateDiffConfig): List<PreviousPost> {
        val since = clock().minusDays(cfg.windowDays)
        val keys = listOfNotNull(item.eventKey, item.relatedPreviousEventKey).distinct()
        val posts = db.fetchStoryPosts(category, keys, since, cfg.maxPreviousPosts)
        val missingText = posts.filter { it.text.isNullOrBlank() }.map { it.eventKey }.toSet()
        val lookup = if (posts.isEmpty()) keys.toSet() else missingText
        val covered = if (lookup.isEmpty()) emptyMap() else {
            db.fetchCoveredEventsByKeys(category, lookup, since).associateBy { it.eventKey }
        }
        if (posts.isEmpty()) {
            return covered.values.sortedBy { it.coveredAt }.takeLast(cfg.maxPreviousPosts)
                .map { PreviousPost(it.coveredAt, factLine(it.subject, it.coreFact)) }
        }
        return posts.mapNotNull { p ->
            val text = p.text?.takeIf { it.isNotBlank() } ?: covered[p.eventKey]?.let { factLine(it.subject, it.coreFact) }
            text?.let { PreviousPost(p.sentAt, it) }
        }
    }

    companion object {
        const val STATUS_UPDATE = "meaningful_update"

        /** Cap on one earlier post in the prompt (a long-form post is ~1100 chars). */
        private const val PREVIOUS_POST_CHARS = 2500

        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Placeholders substituted in both prompts. */
        val PLACEHOLDERS: Set<String> = setOf("CATEGORY", "EVENT_KEY", "SUBJECT", "CORE_FACT", "PREVIOUS_POSTS", "NEW_SOURCES")

        val DEFAULT_PROMPTS = Prompts(
            system = """
                You decide whether a news update deserves a new post in a Ukrainian Telegram news channel.
                PREVIOUS POSTS are what the channel has ALREADY posted about this story (Ukrainian, oldest first).
                NEW SOURCES are the articles behind the candidate update (often English).

                List in newFacts ONLY concrete facts from NEW SOURCES that are absent from ALL previous posts:
                new numbers, dates, prices, decisions, an official confirmation or denial, new parties involved,
                an outcome or result. A fact any previous post already states — in other words, rounded or less
                precisely — is NOT new.
                Never count as new: a rephrasing of what was posted; another outlet repeating the same story;
                opinions, reactions, analysis or speculation; background and context; minor colour that changes
                nothing for the reader.

                Write each new fact as one short sentence in Ukrainian with the concrete detail.
                "useful" is true only when newFacts is not empty. "reason" is a short English explanation.
                Answer with JSON only, no prose, no code fence:
                {"useful": true|false, "newFacts": ["..."], "reason": "..."}
            """.trimIndent(),
            user = """
                Category: {{CATEGORY}}
                Story: {{SUBJECT}} — {{CORE_FACT}}

                PREVIOUS POSTS:
                {{PREVIOUS_POSTS}}

                NEW SOURCES:
                {{NEW_SOURCES}}
            """.trimIndent()
        )

        /**
         * The model's answer as a [Decision]: code fence stripped (and any prose around the JSON
         * object), facts trimmed and de-duplicated, `useful` forced false without facts. Null when
         * the answer is not such an object or lacks `useful`.
         */
        fun parse(raw: String): Decision? {
            val stripped = LlmJson.stripCodeFences(raw)
            val body = if (stripped.startsWith("{")) stripped else {
                val start = stripped.indexOf('{')
                val end = stripped.lastIndexOf('}')
                if (start < 0 || end <= start) return null
                stripped.substring(start, end + 1)
            }
            val d = try {
                json.decodeFromString(Decision.serializer(), body)
            } catch (e: Exception) {
                return null
            }
            val useful = d.useful ?: return null
            val facts = d.newFacts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return Decision(useful = useful && facts.isNotEmpty(), newFacts = facts, reason = d.reason.trim())
        }

        /** Earlier posts as the prompt shows them: `[1] 26.09.2026 07:27`, then the text. */
        fun formatPrevious(posts: List<PreviousPost>): String =
            posts.withIndex().joinToString("\n\n") { (i, p) -> "[${i + 1}] ${p.sentAt.format(TS)}\n${p.text.take(PREVIOUS_POST_CHARS)}" }

        /**
         * The item's source articles, numbered, title + URL + the text the render would see
         * (`promptText`), within [maxChars] in total: its `url`, its `sourceLinks`, then its
         * `articleIndices`.
         */
        fun sourcesText(item: ShortlistItem, articles: List<Article>, maxChars: Int): String {
            val byLink = articles.associateBy { it.link }
            val links = (listOf(item.url) + item.sourceLinks + PromptBuilder.sourceLinksOf(item, articles)).distinct()
            val text = links.mapNotNull { byLink[it] }.withIndex()
                .joinToString("\n\n") { (i, a) -> "[${i + 1}] ${a.title}\nURL: ${a.link}\n${a.promptText()}" }
            return text.take(maxChars)
        }

        private fun factLine(subject: String, coreFact: String): String =
            if (subject.isBlank()) coreFact else "$subject — $coreFact"

        private fun substitute(template: String, vars: Map<String, String>): String =
            vars.entries.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }
    }
}
