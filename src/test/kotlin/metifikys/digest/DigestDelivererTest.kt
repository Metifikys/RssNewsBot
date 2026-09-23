package metifikys.digest

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.HashtagsConfig
import metifikys.config.OpenAIConfig
import metifikys.config.PostConfig
import metifikys.config.SchedulerConfig
import metifikys.config.SummaryHistoryConfig
import metifikys.config.TelegramConfig
import metifikys.config.ThreadsConfig
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.db.SummaryRecord
import metifikys.model.Article
import metifikys.model.ShortlistItem
import metifikys.telegram.SentRef
import metifikys.telegram.TelegramSender
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `a source link whose URL has parentheses is not mistaken for a bare URL`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()

        val articles = listOf(article(1).copy(link = "https://en.wikipedia.org/wiki/Doom_(2016_video_game)"))
        val summary = "• **Story.**\n\nBody. [Doom](https://en.wikipedia.org/wiki/Doom_(2016_video_game))"

        DigestDeliverer(config(), db, sender).deliver("tech", summary, articles)

        verify(exactly = 1) { sender.sendToChannel(any(), any(), any()) }
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

    // ── post: footer (hashtags) and reply threading ──────────────────────────

    private fun configWithPost(post: PostConfig?, channelId: String = "@tech", enableImages: Boolean = false) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("tech" to CategoryConfig(
            emoji = "📰",
            feeds = listOf(FeedConfig("https://example.com/rss")),
            channelId = channelId,
            enableImages = enableImages,
            post = post
        )),
        summaryHistory = SummaryHistoryConfig(maxCount = 2)
    )

    private fun updateItem(eventKey: String, url: String, related: String?) = ShortlistItem(
        eventKey = eventKey,
        franchise = "Hollow Knight",
        coreFact = "fact",
        relatedPreviousEventKey = related,
        url = url,
        status = "meaningful_update"
    )

    private fun previousPost(eventKey: String, messageId: Long, chatId: Long = -100L, hoursAgo: Long = 5) =
        DigestMessageRow(chatId, messageId, "tech", eventKey, "", LocalDateTime.now().minusHours(hoursAgo))

    @Test
    fun `hashtag footer goes to Telegram but never into the stored summary`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))

        val articles = listOf(article(1))
        val shortlist = listOf(shortlistItem("evt-1", article(1).link).copy(franchise = "Hollow Knight"))
        val summary = "• **Story.**\n\nBody.\n\n[Title 1](https://example.com/1)"
        val post = PostConfig(hashtags = HashtagsConfig(category = "#tech"))

        DigestDeliverer(configWithPost(post), db, sender).deliver("tech", summary, articles, shortlist)

        assertEquals("**Story.**\n\nBody.\n\n[Title 1](https://example.com/1)\n\n#tech #hollow_knight", sent.captured)
        verify { db.saveSummary("tech", match { "#tech" !in it && "[Title 1](https://example.com/1)" in it }, 1) }
        verify { db.insertDigestMessages(match { rows -> rows.single().articleLinks == "https://example.com/1" }) }
    }

    @Test
    fun `badge lines come before the hashtag line and only for enabled providers`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))

        val steam = object : metifikys.enrich.BadgeProvider {
            override val id = "steam"
            override fun badge(item: ShortlistItem?, articles: List<Article>) =
                metifikys.enrich.Badge("🎮 [Steam](https://store.steampowered.com/app/1): 719₴ · ${articles.size} article(s) for ${item?.eventKey}")
        }
        val github = object : metifikys.enrich.BadgeProvider {
            override val id = "github"
            override fun badge(item: ShortlistItem?, articles: List<Article>) = metifikys.enrich.Badge("⭐ should not appear")
        }
        val resolver = metifikys.enrich.BadgeResolver(listOf(steam, github))
        val post = PostConfig(
            hashtags = HashtagsConfig(category = "#gaming"),
            badges = metifikys.config.BadgesConfig(steam = true, github = false)
        )
        val articles = listOf(article(1))
        val shortlist = listOf(shortlistItem("evt-1", article(1).link).copy(franchise = "Silksong"))

        DigestDeliverer(configWithPost(post), db, sender, resolver)
            .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", articles, shortlist)

        assertEquals(
            "**Story.**\n\n[Title 1](https://example.com/1)\n\n" +
                "🎮 [Steam](https://store.steampowered.com/app/1): 719₴ · 1 article(s) for evt-1\n#gaming #silksong",
            sent.captured
        )
        verify { db.saveSummary("tech", match { "Steam" !in it && "#gaming" !in it }, 1) }
    }

    @Test
    fun `intro line sits between badges and hashtags and is capped per digest`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val sent = mutableListOf<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))

        val intro = mockk<metifikys.enrich.WikipediaIntroProvider>()
        every { intro.intro("tech", any(), any(), any()) } answers {
            val item = secondArg<ShortlistItem>()
            "📚 ${item.franchise}: студія · [Вікіпедія](https://uk.wikipedia.org/wiki/${item.franchise})"
        }
        val post = PostConfig(
            hashtags = HashtagsConfig(category = "#tech"),
            intro = metifikys.config.IntroConfig(maxPerDigest = 1)
        )
        val articles = listOf(article(1), article(2))
        val shortlist = listOf(
            shortlistItem("evt-1", article(1).link).copy(franchise = "Alpha"),
            shortlistItem("evt-2", article(2).link).copy(franchise = "Beta")
        )
        val summary = "• **One.**\n\n[Title 1](https://example.com/1)\n• **Two.**\n\n[Title 2](https://example.com/2)"

        DigestDeliverer(configWithPost(post), db, sender, null, intro).deliver("tech", summary, articles, shortlist)

        assertEquals(2, sent.size)
        assertEquals("**One.**\n\n[Title 1](https://example.com/1)\n\n📚 Alpha: студія · [Вікіпедія](https://uk.wikipedia.org/wiki/Alpha)\n#tech #alpha", sent[0])
        assertEquals("**Two.**\n\n[Title 2](https://example.com/2)\n\n#tech #beta", sent[1])   // cap of 1 per digest
        verify(exactly = 1) { intro.intro("tech", any(), any(), any()) }
        verify { db.saveSummary("tech", match { "Вікіпедія" !in it }, 2) }
    }

    @Test
    fun `trackers are told about the events that actually reached the channel`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendToChannel(any(), match { "/1)" in it }, any(), any()) } returns listOf(SentRef(-100L, 11L))
        every { sender.sendToChannel(any(), match { "/2)" in it }, any(), any()) } returns emptyList()
        val trackers = mockk<TrackerUpdater>(relaxed = true)

        val articles = listOf(article(1), article(2))
        val shortlist = listOf(shortlistItem("evt-1", article(1).link), shortlistItem("evt-2", article(2).link))
        val summary = "• **First.**\n\n[Title 1](https://example.com/1)\n• **Second.**\n\n[Title 2](https://example.com/2)"

        DigestDeliverer(configWithPost(null), db, sender, null, null, trackers).deliver("tech", summary, articles, shortlist)

        verify {
            trackers.afterDigest(
                "tech",
                match { items -> items.map { it.eventKey } == listOf("evt-1") },
                match { rows -> rows.single().messageId == 11L && rows.single().eventKey == "evt-1" }
            )
        }
    }

    @Test
    fun `moderation gets one post per topic, not per chunk, after the channel sends`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendToChannel(any(), match { "/1)" in it }, any(), any()) } returns listOf(SentRef(-100123L, 11L), SentRef(-100123L, 12L))
        every { sender.sendToChannel(any(), match { "/2)" in it }, any(), any()) } returns listOf(SentRef(-100123L, 13L))
        val notifier = mockk<metifikys.telegram.ModerationNotifier>(relaxed = true)
        every { notifier.appliesTo("tech") } returns true

        val articles = listOf(article(1), article(2))
        val shortlist = listOf(
            shortlistItem("evt-1", article(1).link).copy(franchise = "Silksong / Team Cherry"),
            shortlistItem("evt-2", article(2).link)
        )
        val summary = "• **First [story].**\n\n[Title 1](https://example.com/1)\n• **Second.**\n\n[Title 2](https://example.com/2)"

        DigestDeliverer(config(), db, sender, moderationNotifier = notifier).deliver("tech", summary, articles, shortlist)

        verify {
            notifier.afterDigest("tech", match { posts ->
                posts.map { it.messageId } == listOf(11L, 13L) &&
                    posts[0].muteKeyword == "silksong" && posts[1].muteKeyword == null &&
                    posts[0].url == "https://t.me/tech/11" && posts[0].eventKey == "evt-1"
            })
        }
    }

    @Test
    fun `legacy bullet gets only the static category tag`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))

        val post = PostConfig(hashtags = HashtagsConfig(category = "#politics", fromFranchise = true))
        DigestDeliverer(configWithPost(post), db, sender)
            .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", listOf(article(1)), shortlist = null)

        assertTrue(sent.captured.endsWith("\n#politics"), sent.captured)
    }

    @Test
    fun `caption budget drops the hashtag line rather than the bullet`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val caption = slot<String>()
        every { sender.sendPhotoToChannel(any(), any(), capture(caption), any()) } returns SentRef(-100L, 12L)

        val withImage = article(1).copy(imageUrl = "https://img.example/1.jpg")
        val body = "x".repeat(960)
        val summary = "• **Story.**\n\n$body\n\n[Title 1](https://example.com/1)"  // ~1010 chars: fits, but not with a blank line + tag line
        val post = PostConfig(hashtags = HashtagsConfig(category = "#tech #gaming #science"))

        DigestDeliverer(configWithPost(post, enableImages = true), db, sender)
            .deliver("tech", summary, listOf(withImage), listOf(shortlistItem("evt-1", withImage.link)))

        assertFalse("#tech" in caption.captured, "tag line should have been dropped: ${caption.captured.takeLast(60)}")
        assertTrue(caption.captured.endsWith("[Title 1](https://example.com/1)"))
        assertTrue(caption.captured.length <= 1024)
    }

    @Test
    fun `meaningful_update bullet is a reply to the previous post about the story`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendToChannel(any(), any(), any(), any()) } returns listOf(SentRef(-100L, 13L))
        every {
            db.fetchLatestDigestMessagesByEventKeys("tech", match { "evt-0" in it && "evt-1" in it }, any())
        } returns mapOf("evt-0" to previousPost("evt-0", 11L))

        val articles = listOf(article(1), article(2))
        val shortlist = listOf(
            updateItem("evt-1", article(1).link, related = "evt-0"),
            shortlistItem("evt-2", article(2).link)
        )
        val summary = "• **Update.**\n\n[Title 1](https://example.com/1)\n• **Fresh.**\n\n[Title 2](https://example.com/2)"

        DigestDeliverer(configWithPost(PostConfig(threads = ThreadsConfig())), db, sender)
            .deliver("tech", summary, articles, shortlist)

        verify { sender.sendToChannel("@tech", match { "/1)" in it }, true, 11L) }
        verify { sender.sendToChannel("@tech", match { "/2)" in it }, true, null) }
    }

    @Test
    fun `the newest post among the related and own keys wins`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendToChannel(any(), any(), any(), any()) } returns listOf(SentRef(-100L, 13L))
        every { db.fetchLatestDigestMessagesByEventKeys("tech", any(), any()) } returns mapOf(
            "evt-0" to previousPost("evt-0", 11L, hoursAgo = 30),
            "evt-1" to previousPost("evt-1", 12L, hoursAgo = 4)   // Step 1 reused the key last time
        )

        DigestDeliverer(configWithPost(PostConfig(threads = ThreadsConfig())), db, sender)
            .deliver("tech", "• **Update.**\n\n[Title 1](https://example.com/1)", listOf(article(1)),
                listOf(updateItem("evt-1", article(1).link, related = "evt-0")))

        verify { sender.sendToChannel("@tech", any(), true, 12L) }
    }

    @Test
    fun `a previous post from another chat is ignored and a new item never replies`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendToChannel(any(), any(), any(), any()) } returns listOf(SentRef(-1001234567890L, 13L))
        every { db.fetchLatestDigestMessagesByEventKeys("tech", any(), any()) } returns mapOf(
            "evt-0" to previousPost("evt-0", 11L, chatId = -1009999999999L)
        )

        val articles = listOf(article(1), article(2))
        val shortlist = listOf(
            updateItem("evt-1", article(1).link, related = "evt-0"),
            shortlistItem("evt-2", article(2).link).copy(relatedPreviousEventKey = "evt-0")   // status new
        )
        val summary = "• **Update.**\n\n[Title 1](https://example.com/1)\n• **Fresh.**\n\n[Title 2](https://example.com/2)"

        DigestDeliverer(configWithPost(PostConfig(threads = ThreadsConfig()), channelId = "-1001234567890"), db, sender)
            .deliver("tech", summary, articles, shortlist)

        verify(exactly = 2) { sender.sendToChannel(any(), any(), true, null) }
    }

    @Test
    fun `photo bullets reply too, and a lookup failure just sends without replies`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendPhotoToChannel(any(), any(), any(), any()) } returns SentRef(-100L, 13L)
        every { db.fetchLatestDigestMessagesByEventKeys("tech", any(), any()) } returns mapOf("evt-0" to previousPost("evt-0", 11L))

        val withImage = article(1).copy(imageUrl = "https://img.example/1.jpg")
        val config = configWithPost(PostConfig(threads = ThreadsConfig()), enableImages = true)
        DigestDeliverer(config, db, sender)
            .deliver("tech", "• **Update.**\n\n[Title 1](https://example.com/1)", listOf(withImage),
                listOf(updateItem("evt-1", withImage.link, related = "evt-0")))
        verify { sender.sendPhotoToChannel("@tech", "https://img.example/1.jpg", any(), 11L) }

        every { db.fetchLatestDigestMessagesByEventKeys(any(), any(), any()) } throws IllegalStateException("db down")
        DigestDeliverer(config, db, sender)
            .deliver("tech", "• **Update.**\n\n[Title 1](https://example.com/1)", listOf(withImage),
                listOf(updateItem("evt-1", withImage.link, related = "evt-0")))
        verify { sender.sendPhotoToChannel("@tech", "https://img.example/1.jpg", any(), null) }
    }

    @Test
    fun `a long-form post longer than a caption goes out as text with the image as preview`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any(), any()) } returns listOf(SentRef(-100L, 31L))

        val withImage = article(1).copy(imageUrl = "https://img.example/1.jpg")
        val body = "Перший абзац. ".repeat(40).trim() + "\n\n" + "Другий абзац. ".repeat(40).trim()
        val summary = "• 🎮 **Story.** $body\n[Title 1](https://example.com/1)"
        val long = shortlistItem("evt-1", withImage.link).copy(depth = ShortlistItem.DEPTH_LONG)
        DigestDeliverer(configWithPost(null, enableImages = true), db, sender)
            .deliver("tech", summary, listOf(withImage), listOf(long))

        verify(exactly = 0) { sender.sendPhotoToChannel(any(), any(), any(), any()) }
        verify { sender.sendToChannel("@tech", any(), true, null, "https://img.example/1.jpg") }
        assertTrue(sent.captured.length > 1024)
        assertTrue("\n\nДругий абзац." in sent.captured)
    }

    @Test
    fun `a long-form post that fits a caption, or a short one, stays a photo post`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendPhotoToChannel(any(), any(), any(), any()) } returns SentRef(-100L, 32L)

        val withImage = article(1).copy(imageUrl = "https://img.example/1.jpg")
        val long = shortlistItem("evt-1", withImage.link).copy(depth = ShortlistItem.DEPTH_LONG)
        DigestDeliverer(configWithPost(null, enableImages = true), db, sender)
            .deliver("tech", "• **Story.** Short body.\n[Title 1](https://example.com/1)", listOf(withImage), listOf(long))

        verify { sender.sendPhotoToChannel("@tech", "https://img.example/1.jpg", any(), null) }
        verify(exactly = 0) { sender.sendToChannel(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a photo Telegram cannot fetch falls back to a text post instead of failing the topic`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendPhotoToChannel(any(), any(), any(), any()) } returns null
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 21L))

        val withImage = article(1).copy(imageUrl = "https://img.example/broken.jpg")
        DigestDeliverer(configWithPost(null, enableImages = true), db, sender)
            .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", listOf(withImage), listOf(shortlistItem("evt-1", withImage.link)))

        verify { sender.sendPhotoToChannel("@tech", "https://img.example/broken.jpg", any(), null) }
        assertEquals("**Story.**\n\n[Title 1](https://example.com/1)", sent.captured)
        verify { db.markProcessed(listOf(withImage.link)) }
        verify(exactly = 0) { db.markUnprocessed(any()) }
        verify { db.insertDigestMessages(match { rows -> rows.single().messageId == 21L }) }
    }

    @Test
    fun `threads disabled means no lookup at all`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val sender = mockk<TelegramSender>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { sender.sendToChannel(any(), any(), any(), any()) } returns listOf(SentRef(-100L, 13L))

        DigestDeliverer(configWithPost(PostConfig(threads = ThreadsConfig(enabled = false))), db, sender)
            .deliver("tech", "• **Update.**\n\n[Title 1](https://example.com/1)", listOf(article(1)),
                listOf(updateItem("evt-1", article(1).link, related = "evt-0")))

        verify(exactly = 0) { db.fetchLatestDigestMessagesByEventKeys(any(), any(), any()) }
        verify { sender.sendToChannel("@tech", any(), true, null) }
    }
}
