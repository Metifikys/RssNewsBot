package metifikys.digest

import metifikys.ai.BillingException
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-memory ring buffer of recent cycle errors, drained by the end-of-cycle status post.
 *
 * Each entry carries a monotonic [Entry.seq] so the poster can clear exactly the entries
 * it just rendered ([clearUpTo]) without race-killing entries that arrived afterward
 * (those keep their higher seq and survive into the next post).
 */
class CycleErrorLog(private val maxEntries: Int = 50) {

    enum class Kind { BILLING, OTHER }

    data class Entry(
        val seq: Long,
        val at: Instant,
        val category: String?,
        val context: String,
        val kind: Kind,
        val message: String
    )

    private val lock = ReentrantLock()
    private val deque = ArrayDeque<Entry>()
    private val seqGen = AtomicLong(0)

    fun recordBilling(category: String?, context: String) {
        append(category, context, Kind.BILLING, "billing limit reached")
    }

    fun recordError(category: String?, context: String, t: Throwable) {
        val cause = unwrap(t)
        if (cause is BillingException) {
            append(category, context, Kind.BILLING, "billing limit reached")
        } else {
            val msg = (cause.message ?: cause::class.simpleName ?: "error")
                .lineSequence().firstOrNull().orEmpty().take(140)
            append(category, context, Kind.OTHER, msg.ifBlank { cause::class.simpleName ?: "error" })
        }
    }

    fun list(): List<Entry> = lock.withLock { deque.toList() }

    fun clearUpTo(seq: Long) {
        if (seq < 0) return
        lock.withLock {
            while (deque.isNotEmpty() && deque.first().seq <= seq) {
                deque.removeFirst()
            }
        }
    }

    private fun append(category: String?, context: String, kind: Kind, message: String) {
        val entry = Entry(
            seq = seqGen.incrementAndGet(),
            at = Instant.now(),
            category = category,
            context = context,
            kind = kind,
            message = message
        )
        lock.withLock {
            deque.addLast(entry)
            while (deque.size > maxEntries) {
                deque.removeFirst()
            }
        }
    }

    private fun unwrap(t: Throwable): Throwable {
        var cur: Throwable = t
        while ((cur is ExecutionException || cur is java.util.concurrent.CompletionException) && cur.cause != null) {
            cur = cur.cause!!
        }
        return cur
    }
}
