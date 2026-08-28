package metifikys.digest

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.SummaryHistoryConfig
import metifikys.config.TelegramConfig
import metifikys.db.NewsDatabase
import metifikys.db.SummaryRecord
import metifikys.model.Article
import metifikys.model.ShortlistItem
import metifikys.telegram.SentRef
import metifikys.telegram.TelegramSender
import java.time.LocalDateTime
import kotlin.test.Test

/** Covers BUG-003: linkless / all-invalid topics must not be silently marked PROCESSED. */
class DigestDelivererTest {

    private fun config(historyMaxCount: Int = 2) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("tech" to CategoryConfig(
            emoji = "📰",
            feeds = listOf(FeedConfig("https://example.com/rss")),
            channelId = "@tech"
        )),
        summaryHistory = SummaryHistoryConfig(maxCount = historyMaxCount)
    )

    private fun article(n: Int) = Article(
        category = "tech",
        title = "Title $n",
        link = "https://example.com/$n",
        description = "desc $n",
        pubDate = LocalDateTime.now()
    )

    private fun shortlistItem(eventKey: String, url: String) = ShortlistItem(
        eventKey = eventKey,
        coreFact = "fact for $eventKey",
        url = url,
        status = "new"
    )

    @Test
    fun `all topics missing a URL keeps articles UNPROCESSED and sends nothing`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()

        val articles = listOf(article(1), article(2))
        // A single topic with a bold headline and body but NO [label](url) source link.
        val summary = "• **Big news happened.**\n\nA lot of context here but no source link at all."

        DigestDeliverer(config(), db, sender).deliver("tech", summary, articles)

        verify { db.markUnprocessed(articles.map { it.link }) }
        verify(exactly = 0) { db.markProcessed(any()) }
        verify(exactly = 0) { sender.sendToChannel(any(), any(), any()) }
    }

    @Test
    fun `topic carrying a bare non-markdown URL is dropped as injection (BUG-011)`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()

        val articles = listOf(article(1))
        // A valid whitelisted markdown link plus an injected bare URL in the body.
        val summary = "• **Story.**\n\nBody visit https://evil.example now [Title 1](https://example.com/1)"

        DigestDeliverer(config(), db, sender).deliver("tech", summary, articles)

        verify(exactly = 0) { sender.sendToChannel(any(), any(), any()) }
        verify { db.markUnprocessed(articles.map { it.link }) }
    }

    @Test
    fun `partial send does not record the failed topic's event as covered`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()

        val articles = listOf(article(1), article(2))
        val shortlist = listOf(shortlistItem("evt-1", article(1).link), shortlistItem("evt-2", article(2).link))
        val summary = "• **First.**\n\n[Title 1](https://example.com/1)\n" +
            "• **Second.**\n\n[Title 2](https://example.com/2)"

        // Topic 1 delivers, topic 2 fails to send.
        every { sender.sendToChannel(any(), match { it.contains("/1") }, any()) } returns listOf(SentRef(-100L, 11L))
        every { sender.sendToChannel(any(), match { it.contains("/2") }, any()) } returns emptyList()

        DigestDeliverer(config(), db, sender).deliver("tech", summary, articles, shortlist)

        // The failed topic's article stays UNPROCESSED for retry...
        verify { db.markUnprocessed(listOf(article(2).link)) }
        // ...so its event must NOT land in covered_events: a covered row would make Step 1 call
        // the retry a duplicate and the story would never reach the channel.
        verify {
            db.insertCoveredEvents(match { rows -> rows.map { it.eventKey } == listOf("evt-1") })
        }
    }

    @Test
    fun `fully successful send records every shortlist event as covered`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendToChannel(any(), any(), any()) } returns listOf(SentRef(-100L, 11L))

        val articles = listOf(article(1), article(2))
        val shortlist = listOf(shortlistItem("evt-1", article(1).link), shortlistItem("evt-2", article(2).link))
        val summary = "• **First.**\n\n[Title 1](https://example.com/1)\n" +
            "• **Second.**\n\n[Title 2](https://example.com/2)"

        DigestDeliverer(config(), db, sender).deliver("tech", summary, articles, shortlist)

        verify {
            db.insertCoveredEvents(match { rows -> rows.map { it.eventKey }.toSet() == setOf("evt-1", "evt-2") })
        }
        verify(exactly = 0) { db.markUnprocessed(any()) }
    }

    @Test
    fun `all topics duplicates of a prior digest marks articles PROCESSED`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        // Previous digest already covered example.com/1 → the only topic is a duplicate.
        every { db.fetchRecentSummaries("tech", any()) } returns listOf(
            SummaryRecord("tech", "• Old headline [src](https://example.com/1)", LocalDateTime.now())
        )

        val articles = listOf(article(1))
        val summary = "• **Same story again.**\n\n[Title 1](https://example.com/1)"

        DigestDeliverer(config(), db, sender).deliver("tech", summary, articles)

        verify { db.markProcessed(articles.map { it.link }) }
        verify(exactly = 0) { db.markUnprocessed(any()) }
        verify(exactly = 0) { sender.sendToChannel(any(), any(), any()) }
    }
}
