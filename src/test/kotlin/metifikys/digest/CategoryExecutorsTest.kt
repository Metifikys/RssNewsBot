package metifikys.digest

import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CategoryExecutorsTest {

    @Test
    fun `a manual run queues behind the scheduled run instead of running beside it`() {
        val executors = CategoryExecutors()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val scheduledStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manualDone = CountDownLatch(1)
        var firstRun = true
        executors.schedule("digest-a", 60) {
            if (firstRun) {
                firstRun = false
                events += "scheduled:start"
                scheduledStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
                events += "scheduled:end"
            }
        }
        try {
            assertTrue(scheduledStarted.await(10, TimeUnit.SECONDS))
            assertTrue(executors.enqueue("digest-a") { events += "manual"; manualDone.countDown() })
            Thread.sleep(200)
            assertEquals(listOf("scheduled:start"), events.toList(), "manual run must wait for the scheduled one")
            release.countDown()
            assertTrue(manualDone.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("scheduled:start", "scheduled:end", "manual"), events.toList())
        } finally {
            executors.all().forEach { it.shutdownNow() }
        }
    }

    @Test
    fun `enqueue on an unknown or stopped thread reports false`() {
        val executors = CategoryExecutors()
        assertFalse(executors.enqueue("digest-x") {})
        executors.schedule("digest-x", 60) {}
        assertTrue(executors.has("digest-x"))
        executors.all().forEach { it.shutdownNow() }
        assertFalse(executors.enqueue("digest-x") {})
    }
}
