package metifikys.discussion

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.ai.LlmClient
import metifikys.ai.LlmEndpoint
import metifikys.config.CategoryConfig
import metifikys.config.DiscussionConfig
import metifikys.config.FeedConfig
import metifikys.config.PostConfig
import metifikys.db.DiscussionRow
import metifikys.db.DiscussionStore
import metifikys.model.Article
import metifikys.telegram.SentRef
import metifikys.telegram.TelegramSender
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscussionReplierTest {

    private class Store : DiscussionStore {
        val rows = ConcurrentHashMap<Triple<String, String, String>, DiscussionRow>()
        override fun insertDiscussion(row: DiscussionRow) = rows.putIfAbsent(Triple(row.category, row.source, row.threadId), row) == null
        override fun findDiscussion(category: String, source: String, threadId: String) = rows[Triple(category, source, threadId)]
        override fun deleteOldDiscussions(days: Long) {}
    }

    private class DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    /** Threads by article link; [byUrl] answers [lookupByUrl]. */
    private class FakeSource(
        val threads: Map<String, DiscussionThread>,
        val byUrl: Map<String, DiscussionRef> = emptyMap()
    ) : DiscussionSource {
        override val id = "hn"
        val fetched = mutableListOf<String>()
        val lookedUp = mutableListOf<String>()
        override fun locate(articles: List<Article>) = articles.firstNotNullOfOrNull { threads[it.link]?.ref }
        override fun lookupByUrl(articleUrl: String, maxAgeDays: Long): DiscussionRef? {
            lookedUp += articleUrl
            return byUrl[articleUrl]
        }
        override fun fetch(ref: DiscussionRef): DiscussionThread? {
            fetched += ref.threadId
            return threads.values.firstOrNull { it.ref.threadId == ref.threadId }
        }
    }

    private val comment = "This misses that the same bug was fixed upstream two releases ago."

    private fun thread(id: String, comments: Int = 120) = DiscussionThread(
        DiscussionRef("hn", id, "https://news.ycombinator.com/item?id=$id", comments), "HN", comments,
        listOf(DiscussionComment("c$id", comment))
    )

    private fun article(link: String) = Article(category = "tech", title = "t", link = link, description = "d")

    private fun post(n: Long, link: String) = DiscussionReplier.Post(-100L, n, "key$n", "**Post $n**\n\n[Джерело]($link)", listOf(article(link)))

    private fun cat(cfg: DiscussionConfig) =
        CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://e.com/rss")), channelId = "@tech", post = PostConfig(discussion = cfg))

    private fun llm(answer: String): LlmClient = mockk<LlmClient>().also {
        every { it.endpoint } returns LlmEndpoint("https://openrouter.ai/api/v1", "", "m")
        every { it.completeJson(any(), any(), any()) } returns answer
    }

    private fun sender(): TelegramSender = mockk<TelegramSender>().also {
        every { it.sendToChannel(any(), any(), any(), any(), any(), any(), any()) } returns listOf(SentRef(-100L, 900L))
    }

    private fun replier(source: FakeSource, store: Store, client: LlmClient, sender: TelegramSender) =
        DiscussionReplier(mapOf("hn" to source), store, sender, { client }, executor = DirectExecutor())

    private val good = """{"skip": false, "points": ["Баг уже виправили в апстрімі два релізи тому.", "Частина коментаторів радить [форк](https://x.example)."]}"""

    @Test
    fun `a sent post gets a silent reply with the cleaned points, and the thread is remembered`() {
        val store = Store()
        val source = FakeSource(mapOf("https://a.example/1" to thread("11")))
        val client = llm(good)
        val user = slot<String>()
        every { client.completeJson(any(), capture(user), 1) } returns good
        val sender = sender()
        val text = slot<String>()
        every { sender.sendToChannel("@tech", capture(text), true, 5L, null, true, true) } returns listOf(SentRef(-100L, 900L))

        replier(source, store, client, sender).afterDelivery("tech", cat(DiscussionConfig()), listOf(post(5, "https://a.example/1")))

        assertEquals(
            "🗣 Обговорення на [HN](https://news.ycombinator.com/item?id=11) · 120 коментарів\n\n" +
                "• Баг уже виправили в апстрімі два релізи тому.\n• Частина коментаторів радить форк.",
            text.captured
        )
        assertTrue(comment in user.captured && "**Post 5**" in user.captured, user.captured)
        val row = store.rows.values.single()
        assertEquals(DiscussionRow.SENT, row.status)
        assertEquals(900L, row.replyMessageId)
        assertEquals(5L, row.postMessageId)
        assertEquals("openrouter", row.provider)

        // The same thread under a later post (an update, a re-delivery): no second reply.
        replier(source, store, client, sender).afterDelivery("tech", cat(DiscussionConfig()), listOf(post(6, "https://a.example/1")))
        verify(exactly = 1) { sender.sendToChannel(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `few comments, no thread or a skip send nothing`() {
        val store = Store()
        val source = FakeSource(mapOf("https://a.example/1" to thread("11", comments = 10), "https://a.example/2" to thread("12")))
        val sender = sender()
        val r = replier(source, store, llm("""{"skip": true, "points": []}"""), sender)

        val rows = r.run("tech", cat(DiscussionConfig(minComments = 25)), listOf(
            post(1, "https://a.example/1"), post(2, "https://a.example/2"), post(3, "https://a.example/none")
        ))

        assertEquals(listOf(DiscussionRow.SKIPPED), rows.map { it.status })
        assertEquals("12", store.rows.values.single().threadId)
        assertEquals(listOf("11", "12"), source.fetched)
        verify(exactly = 0) { sender.sendToChannel(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `at most maxPerDigest replies, in digest order, skips not counted`() {
        val store = Store()
        val source = FakeSource((1..4).associate { "https://a.example/$it" to thread("1$it") })
        val client = mockk<LlmClient>()
        every { client.endpoint } returns LlmEndpoint("https://openrouter.ai/api/v1", "", "m")
        every { client.completeJson(any(), any(), any()) } returnsMany listOf("""{"skip": true}""", good, good, good)
        val sender = sender()

        val rows = replier(source, store, client, sender).run(
            "tech", cat(DiscussionConfig(maxPerDigest = 2)), (1..4L).map { post(it, "https://a.example/$it") }
        )

        assertEquals(listOf(DiscussionRow.SKIPPED, DiscussionRow.SENT, DiscussionRow.SENT), rows.map { it.status })
        assertEquals(listOf("11", "12", "13"), source.fetched)
        verify(exactly = 2) { sender.sendToChannel(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `logOnly stores the summary without posting`() {
        val store = Store()
        val sender = sender()
        val rows = replier(FakeSource(mapOf("https://a.example/1" to thread("11"))), store, llm(good), sender)
            .run("tech", cat(DiscussionConfig(logOnly = true)), listOf(post(1, "https://a.example/1")))

        assertEquals(DiscussionRow.LOG_ONLY, rows.single().status)
        assertTrue("апстрімі" in rows.single().pointsJson)
        verify(exactly = 0) { sender.sendToChannel(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `lookupByUrl finds a thread for a post from another feed, only when enabled`() {
        val t = thread("77")
        val source = FakeSource(emptyMap(), byUrl = mapOf("https://verge.example/a" to t.ref))
        val threads = FakeSource(mapOf("unused" to t), byUrl = source.byUrl)
        val sender = sender()

        val off = replier(threads, Store(), llm(good), sender).run("tech", cat(DiscussionConfig()), listOf(post(1, "https://verge.example/a")))
        assertTrue(off.isEmpty())
        assertTrue(threads.lookedUp.isEmpty())

        val on = replier(threads, Store(), llm(good), sender).run("tech", cat(DiscussionConfig(lookupByUrl = true)), listOf(post(1, "https://verge.example/a")))
        assertEquals("77", on.single().threadId)
        assertEquals(listOf("https://verge.example/a"), threads.lookedUp)
    }

    @Test
    fun `a failed send leaves no row, so a later post may try again`() {
        val store = Store()
        val sender = mockk<TelegramSender>()
        every { sender.sendToChannel(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        val rows = replier(FakeSource(mapOf("https://a.example/1" to thread("11"))), store, llm(good), sender)
            .run("tech", cat(DiscussionConfig()), listOf(post(1, "https://a.example/1")))
        assertTrue(rows.isEmpty())
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a broken answer or a failing source is a warning, not an exception`() {
        val store = Store()
        val broken = replier(FakeSource(mapOf("https://a.example/1" to thread("11"))), store, llm("not json"), sender())
        assertTrue(broken.run("tech", cat(DiscussionConfig()), listOf(post(1, "https://a.example/1"))).isEmpty())

        val failing = object : DiscussionSource {
            override val id = "hn"
            override fun locate(articles: List<Article>) = thread("11").ref
            override fun fetch(ref: DiscussionRef): DiscussionThread? = throw java.io.IOException("HTTP 503")
        }
        val r = DiscussionReplier(mapOf("hn" to failing), store, sender(), { llm(good) }, executor = DirectExecutor())
        assertTrue(r.run("tech", cat(DiscussionConfig()), listOf(post(1, "https://a.example/1"))).isEmpty())
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `the prompt file is read when given`() {
        val dir = kotlin.io.path.createTempDirectory("discussion-prompts").toFile()
        java.io.File(dir, "d.yaml").writeText("discussion:\n  system: \"SYS {{MAX_POINTS}}\"\n  user: \"USR {{COMMENTS}}\"\n")
        val client = llm(good)
        val system = slot<String>()
        val user = slot<String>()
        every { client.completeJson(capture(system), capture(user), 1) } returns good
        DiscussionReplier(
            mapOf("hn" to FakeSource(mapOf("https://a.example/1" to thread("11")))), Store(), sender(), { client },
            baseDir = dir, executor = DirectExecutor()
        ).run("tech", cat(DiscussionConfig(promptFile = "d.yaml", maxPoints = 3)), listOf(post(1, "https://a.example/1")))
        assertEquals("SYS 3", system.captured)
        assertTrue(user.captured.startsWith("USR [1] $comment"), user.captured)
        dir.deleteRecursively()
    }
}
