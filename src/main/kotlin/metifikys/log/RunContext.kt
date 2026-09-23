package metifikys.log

import org.slf4j.MDC
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom

/**
 * Logging context of one run (MDC `runId`, `category`, `phase`), so every line of a category
 * run — Step 1 → ranker → delivery → the batch callback — can be pulled out of the JSON log with
 * one `jq 'select(.mdc.runId=="tech-digest-…")'` instead of grepping by time.
 *
 * MDC is thread-local: work handed to another thread (fetch pools, the legacy fan-out pool, a
 * `CompletableFuture` callback) must carry it over — [wrap] for tasks, [capture] + [within] for
 * callbacks. Both restore the worker's own context afterwards, so a pooled thread never leaks
 * one run's id into its next task.
 */
object RunContext {

    const val RUN_ID = "runId"
    const val CATEGORY = "category"
    const val PHASE = "phase"

    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** `<category>-<phase>-<yyyyMMdd-HHmmss>-<4 hex>`, e.g. `tech-digest-20260923-071856-3fa2`. */
    fun newRunId(category: String, phase: String, now: LocalDateTime = LocalDateTime.now()): String =
        "$category-$phase-${now.format(stamp)}-" + "%04x".format(ThreadLocalRandom.current().nextInt(0x10000))

    /** The run id in effect on this thread, if any. */
    fun currentRunId(): String? = MDC.get(RUN_ID)

    /**
     * Runs [block] with [runId] / [category] / [phase] in the MDC, restoring whatever was there
     * before (so nested runs and pooled threads stay clean).
     */
    inline fun <T> withRun(runId: String, category: String?, phase: String, block: () -> T): T {
        val previous = MDC.getCopyOfContextMap()
        try {
            MDC.put(RUN_ID, runId)
            if (category != null) MDC.put(CATEGORY, category) else MDC.remove(CATEGORY)
            MDC.put(PHASE, phase)
            return block()
        } finally {
            restore(previous)
        }
    }

    /**
     * [block] inside a run of [category]: the run already in effect when it belongs to the same
     * category (a scheduled run, a `/run` from the admin chat), else a fresh `<category>-<phase>-…` id.
     */
    inline fun <T> ensureRun(category: String, phase: String, block: () -> T): T {
        val current = MDC.get(RUN_ID)
        return if (current != null && MDC.get(CATEGORY) == category) block()
        else withRun(newRunId(category, phase), category, phase, block)
    }

    /** This thread's MDC, to hand to a callback that runs elsewhere (see [within]). */
    fun capture(): Map<String, String>? = MDC.getCopyOfContextMap()?.takeIf { it.isNotEmpty() }

    /** Runs [block] with a [capture]d MDC, restoring this thread's own afterwards. */
    inline fun <T> within(captured: Map<String, String>?, block: () -> T): T {
        if (captured == null) return block()
        val previous = MDC.getCopyOfContextMap()
        try {
            MDC.setContextMap(captured)
            return block()
        } finally {
            restore(previous)
        }
    }

    /** [task] run with the MDC of the thread that calls [wrap] (captured now). */
    fun wrap(task: Runnable): Runnable {
        val captured = capture() ?: return task
        return Runnable { within(captured) { task.run() } }
    }

    /** [task] run with the MDC of the thread that calls [wrap] (captured now). */
    fun <T> wrap(task: Callable<T>): Callable<T> {
        val captured = capture() ?: return task
        return Callable { within(captured) { task.call() } }
    }

    /**
     * A future whose dependent stages (`thenAccept`, `exceptionally`, …) run with the MDC of the
     * thread that calls this — a batch callback fires on the poller's thread, far from its run.
     * Completion values and exceptions pass through unchanged.
     */
    fun <T> carry(future: CompletableFuture<T>): CompletableFuture<T> {
        val captured = capture() ?: return future
        val out = CompletableFuture<T>()
        future.whenComplete { v, e ->
            within(captured) { if (e != null) out.completeExceptionally(e) else out.complete(v) }
        }
        return out
    }

    @PublishedApi
    internal fun restore(previous: Map<String, String>?) {
        if (previous.isNullOrEmpty()) MDC.clear() else MDC.setContextMap(previous)
    }
}
