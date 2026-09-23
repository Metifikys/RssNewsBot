package metifikys.ai.dedup.golden

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import metifikys.ai.LlmClient
import metifikys.ai.LlmEndpoint
import metifikys.ai.dedup.EventExtractor
import metifikys.ai.dedup.ExtractOutcome
import metifikys.ai.dedup.PromptLoader
import metifikys.config.CategoryConfig
import metifikys.config.DedupConfig
import metifikys.config.DedupPromptsInline
import metifikys.config.DigestConfig
import metifikys.config.FeedConfig
import metifikys.db.AudienceAffinityRow
import metifikys.db.CoveredEventRow
import metifikys.db.NewsDatabase
import metifikys.db.RejectedEventRow
import metifikys.digest.DigestDeliverer
import metifikys.model.Article
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * One Step 1 golden case: `src/test/resources/golden/step1/<id>/`
 *
 * - `case.json` — the input ([GoldenCase]): category, pinned `now`, `digest` config (the YAML
 *   keys of `dedup.digest`), the batch `articles`, the prompt-context `covered` events, and the
 *   DB answers the post-processing reads (`coveredArchive` for the keyed cooldown lookup,
 *   `postCounts` for `maxPostsPerEvent`, `affinity`).
 * - `response.json` — the model's raw output exactly as the client returned it.
 * - `expected.json` — [GoldenExpected], written by `GOLDEN_UPDATE=1`.
 * - optional `render.txt` (a Step 2 output) + `expected-topics.json` ([GoldenTopics]).
 */
data class GoldenCase(
    val category: String,
    val now: String,
    val description: String = "",
    /** `synthetic`, `reconstructed:prod-db` or `<provider>:<model>` of a real recording. */
    val recordedWith: String = "synthetic",
    /** Prompt file used for a real recording, and a hash of its content then (re-record reports a change). */
    val promptFile: String? = null,
    val promptSha: String? = null,
    val emoji: String = "📰",
    val digest: JsonNode? = null,
    val articles: List<GoldenArticle>,
    val covered: List<GoldenCovered> = emptyList(),
    /** Rows only the keyed cooldown lookup (`fetchCoveredEventsByKeys`) finds, not the prompt context. */
    val coveredArchive: List<GoldenCovered> = emptyList(),
    /** `digest_messages` post counts per event key inside `updateWindowDays`. */
    val postCounts: Map<String, Int> = emptyMap(),
    val affinity: List<GoldenAffinity> = emptyList()
) {
    @get:JsonIgnore
    val nowTime: LocalDateTime get() = LocalDateTime.parse(now)

    fun digestConfig(): DigestConfig? = digest?.takeIf { !it.isNull }?.let { GoldenIo.mapper.treeToValue(it, DigestConfig::class.java) }

    fun articleModels(): List<Article> = articles.map { it.toArticle(category) }
}

data class GoldenArticle(
    val title: String,
    val link: String,
    val description: String = "",
    val pubDate: String,
    val summary: String? = null
) {
    fun toArticle(category: String) = Article(
        category = category,
        title = title,
        link = link,
        description = description,
        pubDate = LocalDateTime.parse(pubDate),
        summary = summary
    )
}

data class GoldenCovered(
    val eventKey: String,
    val subject: String = "",
    val franchise: String = "",
    val eventType: String = "",
    val coreFact: String,
    val importance: Int = 0,
    val newsworthiness: Int = 0,
    val digestFit: Int = 0,
    val url: String = "",
    val coveredAt: String
) {
    fun toRow(category: String) = CoveredEventRow(
        category = category, eventKey = eventKey, subject = subject, franchise = franchise, eventType = eventType,
        coreFact = coreFact, importance = importance, newsworthiness = newsworthiness, digestFit = digestFit,
        url = url, coveredAt = LocalDateTime.parse(coveredAt)
    )
}

data class GoldenAffinity(val dimension: String, val key: String, val score: Double, val n: Double = 10.0)

/** `expected.json`: what Step 1 post-processing made of the response. Matched by `url`, never `eventKey`. */
data class GoldenExpected(
    val fallbackToLegacy: Boolean,
    val shortlist: List<GoldenShortlistItem> = emptyList(),
    val drops: List<GoldenDrop> = emptyList(),
    val rejectedSaved: Int = 0
)

data class GoldenShortlistItem(
    val url: String,
    val status: String,
    val newsworthiness: Int,
    val digestFit: Int,
    val eventDate: String? = null,
    val lane: String? = null,
    /** The batch articles the event was built from (`ShortlistItem.sourceLinks`). */
    val sourceLinks: List<String> = emptyList()
)

data class GoldenDrop(val url: String, val reason: String)

/** `expected-topics.json`: the Step 2 output after [DigestDeliverer.prepareTopics]. */
data class GoldenTopics(
    val topics: List<String>,
    val hadValidTopic: Boolean,
    val dropped: Map<String, Int> = emptyMap()
)

/** The outcome of one run of a case: the golden view plus what the harness saw on the way. */
data class GoldenRun(
    val expected: GoldenExpected,
    val systemPrompt: String?,
    val userPrompt: String?,
    val rawResponse: String?
)

object GoldenIo {
    val mapper: ObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    /** Where the cases live in the source tree (the working directory is the project root under Gradle and IDEA). */
    val root = File("src/test/resources/golden/step1")

    const val CASE = "case.json"
    const val RESPONSE = "response.json"
    const val EXPECTED = "expected.json"
    const val RENDER = "render.txt"
    const val EXPECTED_TOPICS = "expected-topics.json"

    fun caseDirs(): List<File> =
        root.listFiles { f -> f.isDirectory && File(f, CASE).isFile }?.sortedBy { it.name }.orEmpty()

    fun readCase(dir: File): GoldenCase = mapper.readValue(File(dir, CASE))

    fun readExpected(dir: File): GoldenExpected? = File(dir, EXPECTED).takeIf { it.isFile }?.let { mapper.readValue(it) }

    fun readTopics(dir: File): GoldenTopics? = File(dir, EXPECTED_TOPICS).takeIf { it.isFile }?.let { mapper.readValue(it) }

    fun write(file: File, value: Any) {
        file.parentFile.mkdirs()
        file.writeText(mapper.writeValueAsString(value).replace("\r\n", "\n") + "\n")
    }

    fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * Runs a [GoldenCase] through the real [EventExtractor.extract] — prompt building, the client call,
 * post-processing — against a relaxed mock DB that answers from the case. [client] is a fake that
 * returns `response.json` in the suite, or a real model in the manual record / re-record modes.
 */
object GoldenRunner {

    /** Minimal prompts for the suite: the fake client ignores them, but they must carry both placeholders. */
    val STUB_PROMPTS = DedupPromptsInline(
        extractSystem = "Golden Step 1 for {{CATEGORY}} {{EMOJI}}",
        extractUser = "BATCH={{CURRENT_BATCH_JSON}}\nCOVERED={{PREVIOUSLY_COVERED_EVENTS_JSON}}",
        renderSystem = "unused",
        renderUser = "{{SHORTLIST_JSON}}"
    )

    fun categoryConfig(case: GoldenCase, prompts: DedupPromptsInline? = STUB_PROMPTS, promptFile: String? = null) = CategoryConfig(
        emoji = case.emoji,
        feeds = listOf(FeedConfig("https://golden.invalid/rss")),
        channelId = "@golden",
        dedup = DedupConfig(promptFile = promptFile, prompts = prompts, digest = case.digestConfig())
    )

    fun mockDb(case: GoldenCase, rejected: MutableList<RejectedEventRow>): NewsDatabase {
        val db = mockk<NewsDatabase>(relaxed = true)
        val covered = case.covered.map { it.toRow(case.category) }
        val archive = covered + case.coveredArchive.map { it.toRow(case.category) }
        every { db.fetchRecentEvents(case.category, any(), any()) } answers { covered.take(thirdArg<Int>()) }
        every { db.fetchCoveredEventsByKeys(case.category, any(), any()) } answers {
            val keys = secondArg<Collection<String>>().toSet()
            val since = thirdArg<LocalDateTime>()
            archive.filter { it.eventKey in keys && !it.coveredAt.isBefore(since) }
        }
        every { db.countDigestPostsByEventKeys(case.category, any(), any()) } answers {
            val keys = secondArg<Collection<String>>().toSet()
            case.postCounts.filterKeys { it in keys }
        }
        every { db.fetchAudienceAffinity() } returns case.affinity.map {
            AudienceAffinityRow(
                category = case.category, dimension = it.dimension, key = it.key, n = it.n,
                engagementZ = 0.0, sentiment = it.score, score = it.score, updatedAt = case.nowTime
            )
        }
        every { db.getState(any()) } returns null
        every { db.fetchArticleStamps(any()) } returns emptyMap()
        every { db.saveRejectedEvents(any()) } answers { rejected += firstArg<List<RejectedEventRow>>() }
        return db
    }

    /** A client that returns [response] for any Step 1 call. */
    fun replayClient(response: String): LlmClient {
        val client = mockk<LlmClient>()
        every { client.isBatchCapable } returns false
        every { client.endpoint } returns LlmEndpoint("https://golden.invalid", "", "golden")
        every { client.completeJson(any(), any(), any()) } returns response
        return client
    }

    fun run(
        case: GoldenCase,
        client: LlmClient,
        prompts: DedupPromptsInline? = STUB_PROMPTS,
        promptFile: String? = null,
        promptLoader: PromptLoader = PromptLoader()
    ): GoldenRun {
        val rejected = mutableListOf<RejectedEventRow>()
        val capture = CapturingClient(client)
        val extractor = EventExtractor(
            openAI = capture,
            promptLoader = promptLoader,
            db = mockDb(case, rejected),
            clock = { case.nowTime }
        )
        val cat = categoryConfig(case, prompts, promptFile)
        val articles = case.articleModels()
        val outcome = extractor.extract(case.category, cat, articles)
        val expected = when (outcome) {
            is ExtractOutcome.Ready -> {
                // extract() does not hand out its drops; evaluate() is write-free, so a second look
                // at the same raw output yields them without touching the captured rejected rows.
                val covered = case.covered.map { it.toRow(case.category) }
                val drops = extractor.evaluate(case.category, cat, articles, capture.raw.orEmpty(), covered, tag = "[Golden]")
                    ?.drops.orEmpty()
                GoldenExpected(
                    fallbackToLegacy = false,
                    shortlist = outcome.result.shortlist.map {
                        GoldenShortlistItem(it.url, it.status, it.newsworthiness, it.digestFit, it.eventDate, it.lane, it.sourceLinks)
                    },
                    drops = drops.map { GoldenDrop(it.url, it.reason) },
                    rejectedSaved = rejected.size
                )
            }
            else -> GoldenExpected(fallbackToLegacy = true, rejectedSaved = rejected.size)
        }
        return GoldenRun(expected, capture.system, capture.user, capture.raw)
    }

    /** Step 2 half: [render] through the same topic preparation delivery uses. */
    fun topics(case: GoldenCase, render: String): GoldenTopics {
        val prepared = DigestDeliverer.prepareTopics(case.category, render, case.articleModels())
        return GoldenTopics(prepared.topics, prepared.hadValidTopic, prepared.dropped)
    }

    /** Delegates everything, remembering the Step 1 prompts and raw output. */
    class CapturingClient(private val inner: LlmClient) : LlmClient by inner {
        var system: String? = null
        var user: String? = null
        var raw: String? = null

        override fun completeJson(systemPrompt: String, userPrompt: String, maxRetry: Int): String {
            system = systemPrompt
            user = userPrompt
            return inner.completeJson(systemPrompt, userPrompt, maxRetry).also { raw = it }
        }
    }
}
