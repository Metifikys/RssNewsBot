package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.ai.LlmClient
import metifikys.ai.MeteredLlmClient
import metifikys.ai.dedup.Step1Evaluation
import metifikys.config.ShadowConfig
import metifikys.db.ShadowRunRow
import metifikys.db.ShadowRunStore
import metifikys.format.TopicFormatter
import metifikys.model.Article
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * `llm.shadow`: runs the B side of a Step 1 or render call next to the live A side and stores both
 * in `shadow_runs` (see [metifikys.config.ShadowConfig]).
 *
 * [start] is called when A starts: B goes to the category's own single-thread executor (bounded
 * queue; full → the run is dropped, never waited for) and a [Handle] comes back; A's code hands
 * its raw output to [Handle.finish] whatever happens. When both sides are done, a writer thread
 * judges each output with the same post-processing ([Outcome]) and inserts the row. Nothing here
 * can fail or slow A: every step is caught and logged.
 */
class ShadowRunner(
    private val db: ShadowRunStore,
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    /** One side's model and prompt, for the row. */
    data class Side(val provider: String, val model: String, val promptHash: String) {
        companion object {
            fun of(client: LlmClient, promptHash: String) =
                Side(MeteredLlmClient.providerKeyOf(client.endpoint), client.endpoint.model, promptHash)
        }
    }

    /** What the post-processing made of one side's answer: [output] JSON and its links; [error] = unusable. */
    data class Outcome(val output: String?, val urls: List<String>, val error: String? = null)

    /** A started shadow run; [finish] takes A's raw output (null when A failed). Idempotent. */
    class Handle internal constructor(private val a: CompletableFuture<String?>) {
        fun finish(aRaw: String?) {
            a.complete(aRaw)
        }
    }

    private data class BResult(val raw: String?, val error: String?, val durationMs: Long)

    private val workers = ConcurrentHashMap<String, ThreadPoolExecutor>()

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shadow-writer").also { it.isDaemon = true }
    }

    /**
     * Starts B for [category]; null when the run is sampled out ([ShadowConfig.sampleRate]) or the
     * category's queue is full. [callB] runs on the shadow thread; [judgeA] / [judgeB] on the writer.
     */
    fun start(
        category: String,
        stage: String,
        cfg: ShadowConfig,
        inputHash: String,
        a: Side,
        b: Side,
        callB: () -> String,
        judgeA: (String) -> Outcome,
        judgeB: (String) -> Outcome
    ): Handle? {
        val label = "[Category:$category][Shadow] $stage"
        if (cfg.sampleRate <= 0.0 || (cfg.sampleRate < 1.0 && random() >= cfg.sampleRate)) {
            logger.debug { "$label: sampled out (sampleRate=${cfg.sampleRate})" }
            return null
        }
        val bFuture = CompletableFuture<BResult>()
        try {
            workerFor(category, cfg.queueSize).execute {
                val startNs = System.nanoTime()
                val result = try {
                    BResult(callB(), null, elapsedMs(startNs))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    BResult(null, "interrupted", elapsedMs(startNs))
                } catch (e: Exception) {
                    logger.warn { "$label: B (${b.provider} ${b.model}) failed — ${e.message?.lineSequence()?.firstOrNull()}" }
                    BResult(null, (e.message ?: e.javaClass.simpleName).take(500), elapsedMs(startNs))
                }
                bFuture.complete(result)
            }
        } catch (e: RejectedExecutionException) {
            logger.info { "$label dropped (busy): the shadow queue of $category is full" }
            return null
        }
        val aFuture = CompletableFuture<String?>()
        bFuture.thenCombineAsync(aFuture, { bResult, aRaw ->
            write(category, stage, inputHash, a, b, aRaw, bResult, judgeA, judgeB)
        }, writer)
        return Handle(aFuture)
    }

    /** Stops the shadow threads; queued B runs are discarded, a running one is interrupted. */
    fun shutdown() {
        workers.values.forEach { it.shutdownNow() }
        writer.shutdownNow()
    }

    private fun write(
        category: String,
        stage: String,
        inputHash: String,
        a: Side,
        b: Side,
        aRaw: String?,
        bResult: BResult,
        judgeA: (String) -> Outcome,
        judgeB: (String) -> Outcome
    ) {
        val label = "[Category:$category][Shadow] $stage"
        try {
            val aOut = aRaw?.let { judgeSafely(label, "A", it, judgeA) }
            val bOut = bResult.raw?.let { judgeSafely(label, "B", it, judgeB) }
            val row = ShadowRunRow(
                category = category,
                stage = stage,
                createdAt = clock(),
                inputHash = inputHash,
                aProvider = a.provider,
                aModel = a.model,
                aPromptHash = a.promptHash,
                bProvider = b.provider,
                bModel = b.model,
                bPromptHash = b.promptHash,
                aOutput = aOut?.output,
                bOutput = bOut?.output,
                bError = bResult.error ?: bOut?.error,
                bDurationMs = bResult.durationMs,
                aUrls = aOut?.urls.orEmpty(),
                bUrls = bOut?.urls.orEmpty()
            )
            db.insertShadowRun(row)
            logger.info {
                "$label: A ${a.provider} ${a.model} ${row.aUrls.size} link(s) | B ${b.provider} ${b.model} " +
                    (row.bError?.let { "failed ($it)" } ?: "${row.bUrls.size} link(s)") +
                    ", Jaccard ${String.format(Locale.ROOT, "%.2f", jaccard(row.aUrls, row.bUrls))}, B ${bResult.durationMs} ms"
            }
        } catch (e: Exception) {
            logger.warn(e) { "$label: storing the shadow run failed" }
        }
    }

    private fun judgeSafely(label: String, side: String, raw: String, judge: (String) -> Outcome): Outcome =
        try {
            judge(raw)
        } catch (e: Exception) {
            logger.warn(e) { "$label: judging $side's output failed" }
            Outcome(raw.take(RAW_KEEP), emptyList(), "post-processing failed: ${e.message}")
        }

    private fun workerFor(category: String, queueSize: Int): ThreadPoolExecutor =
        workers.computeIfAbsent(category) {
            ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueSize.coerceAtLeast(1)),
                { r -> Thread(r, "shadow-$category").also { it.isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy()
            )
        }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    @Serializable
    data class Step1Item(val url: String, val eventKey: String, val status: String, val newsworthiness: Int, val digestFit: Int)

    @Serializable
    data class Step1Drop(val url: String, val reason: String)

    @Serializable
    data class Step1Output(val shortlist: List<Step1Item>, val drops: List<Step1Drop>, val extracted: Int)

    @Serializable
    data class RenderOutput(val topics: List<String>, val dropped: Map<String, Int> = emptyMap())

    companion object {
        /** How much of an unparseable answer is kept in the row, for a look at what went wrong. */
        const val RAW_KEEP = 20_000

        private val json = Json { encodeDefaults = true }

        /** Short SHA-256 of [text], for the input and prompt fingerprints. */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        /** Step 1: the evaluated shortlist and drops, or the raw answer with an error when it did not parse. */
        fun step1Outcome(evaluation: Step1Evaluation?, raw: String): Outcome {
            if (evaluation == null) return Outcome(raw.take(RAW_KEEP), emptyList(), "unparseable output")
            val shortlist = evaluation.result.shortlist
            val out = Step1Output(
                shortlist = shortlist.map { Step1Item(it.url, it.eventKey, it.status, it.newsworthiness, it.digestFit) },
                drops = evaluation.drops.map { Step1Drop(it.url, it.reason) },
                extracted = evaluation.result.extractions.size
            )
            return Outcome(json.encodeToString(out), shortlist.map { it.url })
        }

        /** Render: the topics delivery would post (URL whitelist applied, no repeat check), and their links. */
        fun renderOutcome(category: String, summary: String, articles: List<Article>): Outcome {
            if (summary.isBlank()) return Outcome(null, emptyList(), "empty output")
            val prepared = DigestDeliverer.prepareTopics(category, summary, articles)
            val urls = prepared.topics.flatMap { TopicFormatter.extractUrls(it) }.distinct()
            val out = json.encodeToString(RenderOutput(prepared.topics, prepared.dropped))
            return if (prepared.topics.isEmpty()) {
                Outcome(out, emptyList(), "no valid topic")
            } else Outcome(out, urls)
        }

        /**
         * The `/status` line over [runs]: `🌓 shadow 24h: 12 runs · B fail 1 · Jaccard 0.74`, Jaccard of
         * the links averaged over the runs where both sides produced something; null without runs.
         */
        fun statusLine(runs: List<ShadowRunRow>, window: String = "24h"): String? {
            if (runs.isEmpty()) return null
            val failed = runs.count { it.bError != null }
            val compared = runs.filter { it.bError == null && it.aOutput != null }
            val jaccard = if (compared.isEmpty()) "—" else
                String.format(Locale.ROOT, "%.2f", compared.map { jaccard(it.aUrls, it.bUrls) }.average())
            return "🌓 shadow $window: ${runs.size} runs · B fail $failed · Jaccard $jaccard"
        }

        fun jaccard(a: Collection<String>, b: Collection<String>): Double {
            val sa = a.toSet()
            val sb = b.toSet()
            val union = (sa union sb).size
            return if (union == 0) 1.0 else (sa intersect sb).size.toDouble() / union
        }
    }
}
