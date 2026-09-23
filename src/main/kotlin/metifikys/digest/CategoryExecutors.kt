package metifikys.digest

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The per-category scheduler threads (`digest-<name>`, `ingest-<name>`), one single-thread
 * executor each, looked up by thread name. A manual run ([enqueue]) goes onto the SAME executor
 * as the scheduled one, so it can never run in parallel with it — it just queues behind a run
 * in progress.
 */
class CategoryExecutors {

    private val executors = LinkedHashMap<String, ScheduledExecutorService>()

    /** Creates the [threadName] executor and runs [task] now and then every [intervalMinutes]. */
    fun schedule(threadName: String, intervalMinutes: Long, task: Runnable) {
        val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, threadName) }
        synchronized(executors) { executors[threadName] = executor }
        executor.scheduleAtFixedRate(task, 0, intervalMinutes, TimeUnit.MINUTES)
    }

    /** Queues a one-off [task] on [threadName]'s executor; false when there is none (yet) or it is shut down. */
    fun enqueue(threadName: String, task: Runnable): Boolean {
        val executor = synchronized(executors) { executors[threadName] } ?: return false
        return try {
            executor.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            false
        }
    }

    fun has(threadName: String): Boolean = synchronized(executors) { threadName in executors }

    fun all(): List<ScheduledExecutorService> = synchronized(executors) { executors.values.toList() }
}
