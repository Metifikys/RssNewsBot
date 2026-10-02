package metifikys.digest

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.ai.LlmClient
import metifikys.config.CategoryConfig
import metifikys.config.DedupConfig
import metifikys.config.DigestConfig
import metifikys.config.FeedConfig
import metifikys.config.UpdateDiffConfig
import metifikys.db.CoveredEventRow
import metifikys.db.NewsDatabase
import metifikys.db.StoryPost
import metifikys.model.Article
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UpdateDiffTest {

    private val now = LocalDateTime.of(2026, 9, 27, 12, 0)

    private fun cat(cfg: UpdateDiffConfig? = UpdateDiffConfig(logOnly = false)) = CategoryConfig(
        emoji = "💻",
        feeds = listOf(FeedConfig("https://example.com/rss")),
        channelId = "@tech",
        dedup = DedupConfig(promptFile = "x.yaml", digest = DigestConfig(updateDiff = cfg))
    )

    private val article = Article(
        category = "tech",
        title = "Up to 12 British companies get Ukrainian combat data via the Avengers Labs contest",
        link = "https://itc.ua/drones",
        description = "Up to 12 companies, selected through the Avengers Labs contest, will train AI drones."
    )

    private val update = ShortlistItem(
        eventKey = "uk-drones-labs",
        subject = "Avengers AI Labs",
        coreFact = "up to 12 British companies get combat data",
        url = "https://itc.ua/drones",
        status = "meaningful_update",
        relatedPreviousEventKey = "uk-drones",
        depth = ShortlistItem.DEPTH_LONG
    )

    private val fresh = ShortlistItem(eventKey = "other", coreFact = "x", url = "https://example.com/x", status = "new")

    private val posts = listOf(
        StoryPost("uk-drones", now.minusDays(2), "• **Дані для британських дронів.** Британські компанії отримають доступ до українських бойових даних."),
        StoryPost("uk-drones-12", now.minusDays(1), "• **Avengers AI Labs.** До 12 компаній, теплові відео, 70% розпізнавання цілей.")
    )

    private fun db(story: List<StoryPost> = posts, covered: List<CoveredEventRow> = emptyList()): NewsDatabase {
        val db = mockk<NewsDatabase>()
        every { db.fetchStoryPosts(any(), any(), any(), any()) } returns story
        every { db.fetchCoveredEventsByKeys(any(), any(), any()) } returns covered
        return db
    }

    private fun client(answer: String): Pair<LlmClient, io.mockk.CapturingSlot<String>> {
        val c = mockk<LlmClient>()
        val user = slot<String>()
        every { c.completeJson(any(), capture(user), any()) } returns answer
        return c to user
    }

    private fun diff(db: NewsDatabase, client: LlmClient) = UpdateDiff(db, { client }, clock = { now })

    // ── parse ─────────────────────────────────────────────────────────────

    @Test
    fun `parse strips a code fence and keeps the facts`() {
        val d = UpdateDiff.parse("```json\n{\"useful\": true, \"newFacts\": [\" Перші контракти — у грудні. \", \"\"], \"reason\": \"date\"}\n```")
        assertNotNull(d)
        assertEquals(true, d.useful)
        assertEquals(listOf("Перші контракти — у грудні."), d.newFacts)
        assertEquals("date", d.reason)
    }

    @Test
    fun `parse forces useful false without facts and tolerates prose around the object`() {
        val d = UpdateDiff.parse("Here you go: {\"useful\": true, \"newFacts\": [], \"reason\": \"same facts\"} done")
        assertNotNull(d)
        assertEquals(false, d.useful)
        assertTrue(d.newFacts.isEmpty())
    }

    @Test
    fun `parse returns null for malformed answers or a missing useful`() {
        assertNull(UpdateDiff.parse("not json at all"))
        assertNull(UpdateDiff.parse("{\"useful\": tru"))
        assertNull(UpdateDiff.parse("{\"newFacts\": [\"x\"]}"))
    }

    // ── apply ─────────────────────────────────────────────────────────────

    @Test
    fun `an update without new facts is dropped, other items untouched`() {
        val (c, user) = client("""{"useful": false, "newFacts": [], "reason": "itc.ua repeats the 26.09 post"}""")
        val out = diff(db(), c).apply("tech", cat(), listOf(fresh, update), listOf(article))
        assertEquals(listOf(fresh), out)
        // The prompt shows both earlier posts, oldest first, and the new source.
        val prompt = user.captured
        assertTrue(prompt.indexOf("25.09.2026 12:00") in 0 until prompt.indexOf("26.09.2026 12:00"), prompt)
        assertTrue("Avengers AI Labs.** До 12 компаній" in prompt)
        assertTrue("Avengers Labs contest" in prompt)
    }

    @Test
    fun `a useful update carries the new facts and is never long-form`() {
        val (c, _) = client("""{"useful": true, "newFacts": ["Перші контракти підпишуть у грудні."], "reason": "date"}""")
        val out = diff(db(), c).apply("tech", cat(), listOf(update), listOf(article))
        val item = out.single()
        assertEquals(listOf("Перші контракти підпишуть у грудні."), item.newFacts)
        assertNull(item.depth)
        assertEquals(update.eventKey, item.eventKey)
    }

    @Test
    fun `logOnly makes the call but changes nothing`() {
        val (c, _) = client("""{"useful": false, "newFacts": [], "reason": "same"}""")
        val input = listOf(update)
        val out = diff(db(), c).apply("tech", cat(UpdateDiffConfig(logOnly = true)), input, listOf(article))
        assertEquals(input, out)
        verify(exactly = 1) { c.completeJson(any(), any(), any()) }
    }

    @Test
    fun `malformed answer, failing call and timeout keep the item`() {
        val (bad, _) = client("I think it is useful")
        assertEquals(listOf(update), diff(db(), bad).apply("tech", cat(), listOf(update), listOf(article)))

        val failing = mockk<LlmClient>()
        every { failing.completeJson(any(), any(), any()) } throws RuntimeException("HTTP 502")
        assertEquals(listOf(update), diff(db(), failing).apply("tech", cat(), listOf(update), listOf(article)))

        val slow = mockk<LlmClient>()
        every { slow.completeJson(any(), any(), any()) } answers { Thread.sleep(5_000); """{"useful": false}""" }
        val out = diff(db(), slow).apply("tech", cat(UpdateDiffConfig(logOnly = false, timeoutSeconds = 1)), listOf(update), listOf(article))
        assertEquals(listOf(update), out)
    }

    @Test
    fun `disabled or absent config and a shortlist without updates make no call`() {
        val c = mockk<LlmClient>()
        val d = diff(db(), c)
        val input = listOf(update, fresh)
        assertSame(input, d.apply("tech", cat(null), input, listOf(article)))
        assertSame(input, d.apply("tech", cat(UpdateDiffConfig(enabled = false, logOnly = false)), input, listOf(article)))
        val onlyNew = listOf(fresh)
        assertSame(onlyNew, d.apply("tech", cat(), onlyNew, listOf(article)))
        verify(exactly = 0) { c.completeJson(any(), any(), any()) }
    }

    @Test
    fun `no earlier post of the story keeps the item without a call`() {
        val c = mockk<LlmClient>()
        val out = diff(db(story = emptyList()), c).apply("tech", cat(), listOf(update), listOf(article))
        assertEquals(listOf(update), out)
        verify(exactly = 0) { c.completeJson(any(), any(), any()) }
    }

    @Test
    fun `posts without text fall back to the covered event's subject and core fact`() {
        val story = listOf(StoryPost("uk-drones", now.minusDays(2), null))
        val covered = listOf(
            CoveredEventRow("tech", "uk-drones", "UK drones", "", "", "British firms get Ukrainian combat data", 7, url = "u", coveredAt = now.minusDays(2))
        )
        val (c, user) = client("""{"useful": false, "newFacts": [], "reason": "same"}""")
        diff(db(story, covered), c).apply("tech", cat(), listOf(update), listOf(article))
        assertTrue("UK drones — British firms get Ukrainian combat data" in user.captured, user.captured)
    }

    @Test
    fun `no digest post at all falls back to the covered events of the chain`() {
        val covered = listOf(
            CoveredEventRow("tech", "uk-drones", "UK drones", "", "", "British firms get Ukrainian combat data", 7, url = "u", coveredAt = now.minusDays(2))
        )
        val (c, user) = client("""{"useful": false, "newFacts": [], "reason": "same"}""")
        val out = diff(db(emptyList(), covered), c).apply("tech", cat(), listOf(update), listOf(article))
        assertTrue(out.isEmpty())
        assertTrue("British firms get Ukrainian combat data" in user.captured)
    }

    @Test
    fun `sources text is capped`() {
        val long = article.copy(description = "x".repeat(10_000))
        assertEquals(600, UpdateDiff.sourcesText(update, listOf(long), 600).length)
        assertEquals("", UpdateDiff.sourcesText(update.copy(url = "https://nowhere"), listOf(article), 600))
    }
}
