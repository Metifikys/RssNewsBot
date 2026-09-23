package metifikys.digest

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.ai.LlmClient
import metifikys.ai.LlmEndpoint
import metifikys.ai.PromptBuilder
import metifikys.config.CategoryConfig
import metifikys.config.FactCheckConfig
import metifikys.config.FeedConfig
import metifikys.config.LlmOverride
import metifikys.db.FactCheckRow
import metifikys.db.FactCheckStore
import metifikys.model.Article
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactCheckerTest {

    private class Store : FactCheckStore {
        val rows = CopyOnWriteArrayList<FactCheckRow>()
        override fun insertFactCheck(row: FactCheckRow) { rows += row }
        override fun fetchFactChecks(since: LocalDateTime) = rows.toList()
        override fun deleteOldFactChecks(days: Long) {}
    }

    /** Runs every task on the calling thread, so a test sees the row right after [FactChecker.afterDelivery]. */
    private class DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val cat = CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://e.com/rss")), channelId = "@tech")
    private val cfg = FactCheckConfig(enabled = true, sampleRate = 1.0, judge = LlmOverride("openrouter", "judge"))
    private val source = PromptBuilder.RenderSource(0, "Valve ships Steam Frame", "Valve said the headset ships on May 5.", "https://a.example/1")
    private val post = FactChecker.Post(-100L, 11L, "valve|frame", "**Steam Frame виходить 5 травня.**\n\n[Джерело](https://a.example/1)", listOf(source))

    private fun judge(answer: String): LlmClient = mockk<LlmClient>().also {
        every { it.endpoint } returns LlmEndpoint("https://openrouter.ai/api/v1", "", "judge")
        every { it.completeJson(any(), any(), any()) } returns answer
    }

    private fun checker(client: LlmClient?, config: FactCheckConfig = cfg, store: Store = Store(), random: () -> Double = { 0.0 }) =
        FactChecker(config, store, { client }, random = random, executor = DirectExecutor())

    @Test
    fun `a verdict is stored with the clean post, the source size and the judge`() {
        val store = Store()
        val client = judge("""{"verdict":"Unsupported","claims":[{"claim":"5 травня","status":"unsupported","note":"sources: May 6"}]}""")
        val user = slot<String>()
        every { client.completeJson(any(), capture(user), 1) } returns
            """{"verdict":"Unsupported","claims":[{"claim":"5 травня","status":"unsupported","note":"sources: May 6"}]}"""

        checker(client, store = store).afterDelivery("tech", cat, listOf(post))

        val row = store.rows.single()
        assertEquals(FactCheckRow.UNSUPPORTED, row.verdict)
        assertEquals(post.topic, row.postText)
        assertEquals(11L, row.messageId)
        assertEquals("openrouter", row.judgeProvider)
        assertTrue(row.claimsJson.contains("sources: May 6"))
        assertTrue(post.topic in user.captured && "Valve said the headset ships on May 5." in user.captured, user.captured)
        assertEquals(FactChecker.formatSources(post.sources).length, row.sourceChars)
    }

    @Test
    fun `sampleRate 0 checks nothing, sampleRate 1 checks everything`() {
        val client = judge("""{"verdict":"supported","claims":[]}""")
        val none = Store()
        checker(client, cfg.copy(sampleRate = 0.0), none).afterDelivery("tech", cat, listOf(post, post))
        assertTrue(none.rows.isEmpty())
        verify(exactly = 0) { client.completeJson(any(), any(), any()) }

        val all = Store()
        checker(client, cfg.copy(sampleRate = 1.0), all, random = { 0.99 }).afterDelivery("tech", cat, listOf(post, post))
        assertEquals(2, all.rows.size)
    }

    @Test
    fun `categories limit which posts are checked`() {
        val store = Store()
        checker(judge("""{"verdict":"supported"}"""), cfg.copy(categories = listOf("gaming")), store).afterDelivery("tech", cat, listOf(post))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a broken or unknown verdict is a warning without a row`() {
        for (answer in listOf("not json", """{"verdict":"maybe","claims":[]}""", """{"claims":[]}""")) {
            val store = Store()
            assertNull(checker(judge(answer), store = store).check("tech", cat, post), answer)
            assertTrue(store.rows.isEmpty(), answer)
        }
    }

    @Test
    fun `a judge exception never leaves the checker`() {
        val client = mockk<LlmClient>()
        every { client.endpoint } returns LlmEndpoint("https://x", "", "judge")
        every { client.completeJson(any(), any(), any()) } throws IllegalStateException("down")
        val store = Store()
        checker(client, store = store).afterDelivery("tech", cat, listOf(post))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `afterDelivery returns while the judge is still working`() {
        val release = CountDownLatch(1)
        val client = mockk<LlmClient>()
        every { client.endpoint } returns LlmEndpoint("https://x", "", "judge")
        every { client.completeJson(any(), any(), any()) } answers { release.await(5, TimeUnit.SECONDS); """{"verdict":"supported"}""" }
        val store = Store()
        val async = FactChecker(cfg, store, { client })
        try {
            val start = System.nanoTime()
            async.afterDelivery("tech", cat, listOf(post))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
            assertTrue(store.rows.isEmpty())
            release.countDown()
            val deadline = System.currentTimeMillis() + 5_000
            while (store.rows.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(1, store.rows.size)
        } finally {
            async.shutdown()
        }
    }

    @Test
    fun `a long-form item is judged against the full text the render saw`() {
        val article = Article("tech", "Title", "https://a.example/1", "short rss text", fullText = "FULL ARTICLE TEXT with the numbers")
        val short = ShortlistItem(eventKey = "k", coreFact = "f", url = article.link, status = "new", articleIndices = listOf(0))
        val long = short.copy(depth = ShortlistItem.DEPTH_LONG)

        assertEquals("FULL ARTICLE TEXT with the numbers", PromptBuilder.renderSourcesFor(long, listOf(article)).single().text)
        assertEquals("short rss text", PromptBuilder.renderSourcesFor(short, listOf(article)).single().text)
        // The render prompt carries exactly the same text.
        val renderPrompt = PromptBuilder.buildRenderUserPrompt("tech", "💻", "{{SHORTLIST_JSON}}", listOf(long), listOf(article))
        assertTrue("FULL ARTICLE TEXT with the numbers" in renderPrompt)
    }

    @Test
    fun `a missing or incomplete prompt file falls back to the built-in prompt`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            assertNull(FactChecker.loadPrompts("missing.yaml", dir))
            java.io.File(dir, "fc.yaml").writeText("factCheck:\n  system: judge\n  user: \"{{POST}} only\"\n")
            assertNotNull(FactChecker.promptFileProblem("fc.yaml", dir))
            java.io.File(dir, "ok.yaml").writeText("factCheck:\n  system: judge\n  user: \"{{POST}} {{SOURCES}}\"\n")
            assertEquals(FactChecker.Prompts("judge", "{{POST}} {{SOURCES}}"), FactChecker.loadPrompts("ok.yaml", dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
