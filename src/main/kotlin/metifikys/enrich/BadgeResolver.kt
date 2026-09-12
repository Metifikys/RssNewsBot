package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * Runs the badge providers for one bullet under a hard time budget, so a slow public API can
 * never delay a digest: each provider gets [timeoutSeconds], and the whole delivery shares one
 * deadline of [maxTotalSeconds] (from [newDeadline]). A provider that times out, throws, or
 * finds nothing contributes no line. Results keep provider order.
 */
class BadgeResolver(
    private val providers: List<BadgeProvider>,
    private val timeoutSeconds: Long = 5,
    private val maxTotalSeconds: Long = 15
) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "badge").also { it.isDaemon = true } }

    val providerIds: Set<String> get() = providers.map { it.id }.toSet()

    /** One per delivery: the moment after which no further badge lookups are started or awaited. */
    fun newDeadline(): Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxTotalSeconds)

    fun resolve(
        enabled: Set<String>,
        item: ShortlistItem?,
        articles: List<Article>,
        deadlineNanos: Long,
        label: String = ""
    ): List<String> {
        val active = providers.filter { it.id in enabled }
        if (active.isEmpty()) return emptyList()
        if (deadlineNanos - System.nanoTime() <= 0) {
            logger.debug { "$label[Badge] budget exhausted — skipping ${active.map { it.id }}" }
            return emptyList()
        }
        val futures = active.map { p -> p to pool.submit(Callable { p.badge(item, articles) }) }
        val out = mutableListOf<String>()
        for ((provider, future) in futures) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0)
            val waitMs = minOf(TimeUnit.SECONDS.toMillis(timeoutSeconds), remainingMs)
            try {
                future.get(waitMs, TimeUnit.MILLISECONDS)?.let { out += it.text }
            } catch (e: TimeoutException) {
                future.cancel(true)
                logger.warn { "$label[Badge] ${provider.id} timed out after ${waitMs}ms — sending without it" }
            } catch (e: ExecutionException) {
                logger.warn { "$label[Badge] ${provider.id} failed: ${e.cause?.message ?: e.message}" }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                futures.forEach { it.second.cancel(true) }
                return out
            }
        }
        return out
    }
}
