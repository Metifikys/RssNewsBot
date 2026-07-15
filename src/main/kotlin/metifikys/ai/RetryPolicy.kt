package metifikys.ai

/**
 * Shared retry loop for the synchronous LLM/embedding clients, replacing the six near-identical
 * hand-rolled `while (attempt <= maxRetries) { try … catch (e: Exception) { sleep } }` blocks.
 *
 * The key correctness fix over those copies: **[InterruptedException] always propagates** (with the
 * thread's interrupt flag restored) instead of being swallowed by a broad `catch (e: Exception)` and
 * treated as a retryable failure. The old copies swallowed it, so a `shutdownNow()` on the executor
 * could not actually stop a client mid-retry — the loop just kept going. See BUG-008 / phase-2 notes.
 *
 * The same applies to interrupts that libraries translate into other exceptions (okhttp/okio throw
 * [java.io.InterruptedIOException] with the interrupt flag still set): any failure on an already-
 * interrupted thread propagates without a retry. A plain [java.net.SocketTimeoutException] with a
 * clear flag is a genuine timeout and remains retryable.
 *
 * Per-site policy is passed in so each caller keeps its own semantics:
 *  - [isFatal] marks exceptions that must surface immediately without a retry (e.g. billing/quota,
 *    provider-specific non-retryable errors, an OpenAI error flagged non-retryable).
 *  - [delayMillis] computes the wait before the next attempt (1-based attempt number + the thrown
 *    exception, so a caller can honor a Retry-After carried on the exception).
 *  - [onRetry] is the log hook fired just before sleeping.
 */
object RetryPolicy {

    fun <T> retry(
        maxRetries: Int,
        isFatal: (Throwable) -> Boolean = { false },
        onRetry: (attempt: Int, e: Throwable) -> Unit = { _, _ -> },
        delayMillis: (attempt: Int, e: Throwable) -> Long,
        block: () -> T
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                // An interrupt often surfaces as a translated exception with the flag still set
                // (okhttp/okio throw InterruptedIOException, NIO throws ClosedByInterruptException).
                // A cancelled worker must never retry. A plain SocketTimeoutException with a clear
                // flag is a genuine timeout and still retries.
                if (Thread.currentThread().isInterrupted()) throw e
                attempt++
                if (attempt > maxRetries) throw e
                onRetry(attempt, e)
                val ms = delayMillis(attempt, e)
                if (ms > 0) {
                    try {
                        Thread.sleep(ms)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw ie
                    }
                }
            }
        }
    }
}
