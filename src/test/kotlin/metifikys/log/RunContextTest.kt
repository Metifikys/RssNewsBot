package metifikys.log

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunContextTest {

    private val logger = LoggerFactory.getLogger("RunContextTest") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        MDC.clear()
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        MDC.clear()
    }

    private fun runIdOf(message: String): String? =
        appender.list.single { it.formattedMessage == message }.mdcPropertyMap[RunContext.RUN_ID]

    @Test
    fun `run id format`() {
        val id = RunContext.newRunId("tech", "digest", LocalDateTime.of(2026, 9, 23, 7, 18, 56))
        assertTrue(Regex("""tech-digest-20260923-071856-[0-9a-f]{4}""").matches(id), id)
    }

    @Test
    fun `withRun sets and then restores the MDC`() {
        MDC.put("outer", "1")
        RunContext.withRun("r1", "tech", "digest") {
            logger.info("inside")
            assertEquals("tech", MDC.get(RunContext.CATEGORY))
        }
        logger.info("after")
        assertEquals("r1", runIdOf("inside"))
        assertNull(runIdOf("after"))
        assertEquals("1", MDC.get("outer"))
    }

    @Test
    fun `ensureRun keeps a run of the same category, starts one for another`() {
        RunContext.withRun("cmd-1", "tech", "cmd") {
            RunContext.ensureRun("tech", "digest") { logger.info("same") }
            RunContext.ensureRun("games", "run") { logger.info("other") }
        }
        assertEquals("cmd-1", runIdOf("same"))
        assertTrue(runIdOf("other")!!.startsWith("games-run-"))
    }

    @Test
    fun `the run id follows a task into a pool thread and does not leak into the next task`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            RunContext.withRun("r-pool", "tech", "ingest") {
                pool.submit(RunContext.wrap(Runnable { logger.info("pooled") })).get(5, TimeUnit.SECONDS)
                pool.submit(RunContext.wrap(Callable { logger.info("callable"); 1 })).get(5, TimeUnit.SECONDS)
            }
            pool.submit(Runnable { logger.info("next task") }).get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        assertEquals("r-pool", runIdOf("pooled"))
        assertEquals("r-pool", runIdOf("callable"))
        assertNull(runIdOf("next task"))
    }

    @Test
    fun `a carried future runs its callbacks with the run id, values and failures unchanged`() {
        val ok = CompletableFuture<String>()
        val bad = CompletableFuture<String>()
        val done = CompletableFuture<String?>()
        RunContext.withRun("r-batch", "tech", "digest") {
            RunContext.carry(ok).thenAccept { v -> logger.info("callback $v") }
            RunContext.carry(bad).exceptionally { e -> logger.info("failed ${e.javaClass.simpleName}"); null }
                .thenAccept { done.complete(null) }
        }
        val poller = Thread {
            ok.complete("x")
            bad.completeExceptionally(IllegalStateException("boom"))
            logger.info("poller after")
        }
        poller.start()
        poller.join(5000)
        done.get(5, TimeUnit.SECONDS)
        assertEquals("r-batch", runIdOf("callback x"))
        assertEquals("r-batch", runIdOf("failed IllegalStateException"))
        assertNull(runIdOf("poller after"))
    }

    // ── the shipped logback.xml ──

    private val context get() = LoggerFactory.getILoggerFactory() as LoggerContext
    private val root get() = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

    private fun event(mdc: Map<String, String>): LoggingEvent = LoggingEvent().apply {
        loggerName = "metifikys.Test"
        level = ch.qos.logback.classic.Level.INFO
        message = "hello"
        threadName = "digest-tech"
        timeStamp = System.currentTimeMillis()
        setLoggerContext(context)
        setMDCPropertyMap(mdc)
    }

    @Test
    fun `text log shows the run id inside a run and nothing outside one`() {
        val layout = ((root.getAppender("FILE") as RollingFileAppender<*>).encoder as PatternLayoutEncoder).layout
        val inRun = layout.doLayout(event(mapOf(RunContext.RUN_ID to "tech-digest-1")))
        val outside = layout.doLayout(event(emptyMap()))
        assertTrue("[digest-tech] [tech-digest-1] INFO" in inRun, inRun)
        assertTrue("[digest-tech] INFO" in outside, outside)
    }

    @Test
    fun `json log keeps two days and carries the MDC`() {
        val json = root.getAppender("JSON") as RollingFileAppender<*>
        val policy = json.rollingPolicy as SizeAndTimeBasedRollingPolicy<*>
        assertEquals(2, policy.maxHistory, "owner: JSON logs for two days only")
        val encoder = json.encoder as JsonEncoder
        val line = String(encoder.encode(event(mapOf(RunContext.RUN_ID to "tech-digest-1"))))
        assertTrue("\"mdc\"" in line && "\"runId\": \"tech-digest-1\"".replace(" ", "") in line.replace(" ", ""), line)
        assertTrue(line.trimEnd().lines().size == 1, "one JSON object per line")
    }
}
