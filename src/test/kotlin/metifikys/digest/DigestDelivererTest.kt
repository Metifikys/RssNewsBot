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
    fun `event history gets exactly the sent events, and only with storyIntel on`() {
        for (on in listOf(true, false)) {
            val db = mockk<NewsDatabase>(relaxed = true)
            val sender = mockk<TelegramSender>(relaxed = true)
            every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
            every { sender.sendToChannel(any(), match { it.contains("/1") }, any()) } returns listOf(SentRef(-100L, 11L))
            every { sender.sendToChannel(any(), match { it.contains("/2") }, any()) } returns emptyList()
            val shortlist = listOf(shortlistItem("evt-1", article(1).link), shortlistItem("evt-2", article(2).link))
            val summary = "• **First.**\n\n[Title 1](https://example.com/1)\n• **Second.**\n\n[Title 2](https://example.com/2)"

            DigestDeliverer(config().copy(storyIntel = metifikys.config.StoryIntelConfig(enabled = on)), db, sender)
                .deliver("tech", summary, listOf(article(1), article(2)), shortlist)

            if (on) verify { db.insertEventHistory(match { rows -> rows.map { it.eventKey } == listOf("evt-1") }) }
            else verify(exactly = 0) { db.insertEventHistory(any()) }
        }
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

    // ── post.storyLines (📢 hype) ────────────────────────────────────────────

    private fun member(id: Int, domain: String, minutes: Long, key: String = "evt-1") = metifikys.db.EventArticleRow(
        category = "tech", articleId = id, eventKey = key, link = "https://$domain/story", domain = domain,
        pubDate = LocalDateTime.of(2026, 9, 1, 10, 0).plusMinutes(minutes), seenAt = null,
        role = metifikys.db.EventArticleRole.STEP1, step1Status = "new", recordedAt = LocalDateTime.now()
    )

    private fun storyDb(): NewsDatabase {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        every { db.fetchEventClusters("tech", any()) } returns mapOf(
            "evt-1" to listOf(member(1, "alpha.com", 0), member(2, "beta.com", 10), member(3, "gamma.com", 20), member(4, "delta.com", 30))
        )
        every { db.fetchArticlesByLinks(any()) } answers {
            firstArg<List<String>>().map { link ->
                val credits = if (link.contains("alpha.com")) "" else " via alpha.com"
                Article(category = "tech", title = "Story$credits", link = link, description = "d")
            }
        }
        return db
    }

    private fun storyConfig(storyIntel: Boolean, hype: metifikys.config.HypeLineConfig?) =
        configWithPost(PostConfig(hashtags = HashtagsConfig(category = "#tech"), storyLines = metifikys.config.StoryLinesConfig(hype = hype)))
            .copy(storyIntel = metifikys.config.StoryIntelConfig(enabled = storyIntel))

    @Test
    fun `hype line sits before the hashtags and never reaches the stored summary`() {
        val db = storyDb()
        val sender = mockk<TelegramSender>(relaxed = true)
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))

        DigestDeliverer(storyConfig(true, metifikys.config.HypeLineConfig(minOutlets = 4)), db, sender)
            .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", listOf(article(1)), listOf(shortlistItem("evt-1", article(1).link)))

        assertEquals("**Story.**\n\n[Title 1](https://example.com/1)\n\n📢 4 видання · 1 першоджерело\n#tech", sent.captured)
        verify { db.saveSummary("tech", match { "📢" !in it }, 1) }
    }

    @Test
    fun `below minOutlets, or with storyIntel off, the post is exactly as before`() {
        val outputs = listOf(
            storyConfig(true, metifikys.config.HypeLineConfig(minOutlets = 5)),
            storyConfig(false, metifikys.config.HypeLineConfig(minOutlets = 2)),
            storyConfig(true, null)
        ).map { cfg ->
            val db = storyDb()
            val sender = mockk<TelegramSender>(relaxed = true)
            val sent = slot<String>()
            every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))
            DigestDeliverer(cfg, db, sender)
                .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", listOf(article(1)), listOf(shortlistItem("evt-1", article(1).link)))
            if (!cfg.storyIntel.enabled) verify(exactly = 0) { db.fetchEventClusters(any(), any()) }
            sent.captured
        }
        outputs.forEach { assertEquals("**Story.**\n\n[Title 1](https://example.com/1)\n\n#tech", it) }
    }

    @Test
    fun `a failing cluster lookup sends the post without the line`() {
        val db = storyDb()
        every { db.fetchEventClusters(any(), any()) } throws RuntimeException("locked")
        val sender = mockk<TelegramSender>(relaxed = true)
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))

        DigestDeliverer(storyConfig(true, metifikys.config.HypeLineConfig(minOutlets = 2)), db, sender)
            .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", listOf(article(1)), listOf(shortlistItem("evt-1", article(1).link)))

        assertEquals("**Story.**\n\n[Title 1](https://example.com/1)\n\n#tech", sent.captured)
    }

    @Test
    fun `hype and first-source facts share one footer line`() {
        val db = storyDb()
        val sender = mockk<TelegramSender>(relaxed = true)
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))
        val cfg = configWithPost(
            PostConfig(
                hashtags = HashtagsConfig(category = "#tech"),
                storyLines = metifikys.config.StoryLinesConfig(
                    hype = metifikys.config.HypeLineConfig(minOutlets = 4),
                    first = metifikys.config.FirstLineConfig(minOutlets = 3, minLeadMinutes = 10)
                )
            )
        ).copy(storyIntel = metifikys.config.StoryIntelConfig(enabled = true))

        DigestDeliverer(cfg, db, sender)
            .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", listOf(article(1)), listOf(shortlistItem("evt-1", article(1).link)))

        assertEquals(
            "**Story.**\n\n[Title 1](https://example.com/1)\n\n📢 4 видання · 1 першоджерело · 🥇 alpha.com (+10 хв)\n#tech",
            sent.captured
        )
    }

    // ── post.storyLines.conflicts (⚖️) ───────────────────────────────────────

    private fun conflictDb(): NewsDatabase {
        val db = storyDb()
        every { db.fetchArticlesByLinks(any()) } answers {
            firstArg<List<String>>().map { link ->
                val date = if (link.contains("delta.com")) "October 21" else "October 14"
                Article(category = "tech", title = "Game launches $date", link = link, description = "d")
            }
        }
        return db
    }

    private fun conflictConfig(mode: String) = configWithPost(
        PostConfig(
            hashtags = HashtagsConfig(category = "#tech"),
            storyLines = metifikys.config.StoryLinesConfig(conflicts = metifikys.config.ConflictLineConfig(mode = mode, minSupport = 1))
        )
    ).copy(storyIntel = metifikys.config.StoryIntelConfig(enabled = true))

    @Test
    fun `sources-disagree line is posted in post mode and only logged in log mode`() {
        val outputs = listOf("post", "log").associateWith { mode ->
            val sender = mockk<TelegramSender>(relaxed = true)
            val sent = slot<String>()
            every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))
            DigestDeliverer(conflictConfig(mode), conflictDb(), sender)
                .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", listOf(article(1)), listOf(shortlistItem("evt-1", article(1).link)))
            sent.captured
        }
        assertEquals(
            "**Story.**\n\n[Title 1](https://example.com/1)\n\n" +
                "⚖️ Джерела розходяться: дата 14.10.2026 (alpha.com, beta.com, gamma.com) чи 21.10.2026 (delta.com)\n#tech",
            outputs["post"]
        )
        assertEquals("**Story.**\n\n[Title 1](https://example.com/1)\n\n#tech", outputs["log"])
    }

    // ── post.storyLines.vaporware (🌀) ───────────────────────────────────────

    @Test
    fun `an undated re-announcement gets the vaporware line from the event history`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val past = listOf(400L, 200L, 90L).map { days ->
            metifikys.db.EventHistoryRow(
                category = "tech", eventKey = "old-$days", entityKey = "hollow_knight_silksong", subject = "Hollow Knight: Silksong",
                franchise = "Hollow Knight", eventType = "rumor", coreFact = "f", eventDate = null, newsworthiness = 5,
                coveredAt = LocalDateTime.now().minusDays(days)
            )
        }
        every { db.fetchEventHistoryByEntities("tech", listOf("hollow_knight_silksong")) } returns mapOf("hollow_knight_silksong" to past)
        val sender = mockk<TelegramSender>(relaxed = true)
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))
        val cfg = configWithPost(
            PostConfig(
                hashtags = HashtagsConfig(category = "#tech"),
                storyLines = metifikys.config.StoryLinesConfig(vaporware = metifikys.config.VaporwareLineConfig())
            )
        ).copy(storyIntel = metifikys.config.StoryIntelConfig(enabled = true))
        val item = shortlistItem("evt-1", article(1).link).copy(subject = "Hollow Knight: Silksong", eventType = "major_announcement")

        DigestDeliverer(cfg, db, sender).deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", listOf(article(1)), listOf(item))

        val first = LocalDateTime.now().minusDays(400).format(java.time.format.DateTimeFormatter.ofPattern("MM.yyyy"))
        assertEquals(
            "**Story.**\n\n[Title 1](https://example.com/1)\n\n🌀 анонсовано вчетверте, дати досі немає (вперше: $first)\n#tech",
            sent.captured
        )
        verify(exactly = 0) { db.fetchEventClusters(any(), any()) }
    }

    // ── post.also (📰) ───────────────────────────────────────────────────────

    private fun alsoDelivery(also: metifikys.config.AlsoConfig, sourceLinks: List<String>): Pair<NewsDatabase, String> {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val sender = mockk<TelegramSender>(relaxed = true)
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))
        val articles = listOf(
            article(1).copy(link = "https://www.ign.com/a"),
            article(2).copy(link = "https://www.pcgamer.com/b", feedUrl = "https://www.pcgamer.com/rss"),
            article(3).copy(link = "https://www.eurogamer.net/c")
        )
        val cfg = configWithPost(PostConfig(hashtags = HashtagsConfig(category = "#tech"), also = also))
        val withLabel = cfg.copy(categories = cfg.categories.mapValues { (_, c) ->
            c.copy(feeds = c.feeds + FeedConfig("https://www.pcgamer.com/rss", label = "PC Gamer"))
        })
        val item = shortlistItem("evt-1", "https://www.ign.com/a").copy(sourceLinks = sourceLinks)
        DigestDeliverer(withLabel, db, sender)
            .deliver("tech", "• **Story.**\n\n[IGN](https://www.ign.com/a)", articles, listOf(item))
        return db to sent.captured
    }

    @Test
    fun `also line names the other outlets before the hashtags and never reaches the stored summary`() {
        val (db, sent) = alsoDelivery(
            metifikys.config.AlsoConfig(max = 3),
            listOf("https://www.ign.com/a", "https://www.pcgamer.com/b", "https://www.eurogamer.net/c")
        )
        assertEquals(
            "**Story.**\n\n[IGN](https://www.ign.com/a)\n\n" +
                "📰 Також: [PC Gamer](https://www.pcgamer.com/b) · [Eurogamer](https://www.eurogamer.net/c)\n#tech",
            sent
        )
        verify { db.saveSummary("tech", match { "Також" !in it && "pcgamer" !in it }, 1) }
        verify { db.insertDigestMessages(match { rows -> rows.single().articleLinks == "https://www.ign.com/a" }) }
    }

    @Test
    fun `also line is absent for a single-article event and in logOnly mode`() {
        val (_, single) = alsoDelivery(metifikys.config.AlsoConfig(), listOf("https://www.ign.com/a"))
        assertEquals("**Story.**\n\n[IGN](https://www.ign.com/a)\n\n#tech", single)
        val (_, logOnly) = alsoDelivery(
            metifikys.config.AlsoConfig(logOnly = true),
            listOf("https://www.ign.com/a", "https://www.pcgamer.com/b")
        )
        assertEquals("**Story.**\n\n[IGN](https://www.ign.com/a)\n\n#tech", logOnly)
    }

    // ── post.images ──────────────────────────────────────────────────────────

    @Test
    fun `images block takes the og image first and falls back to the cluster's pictures`() {
        val cluster = listOf("https://example.com/1", "https://example.com/2")
        val articles = listOf(
            article(1).copy(imageUrl = "https://example.com/img/site-logo.png"),
            article(2).copy(imageUrl = "https://cdn.example.com/hero.jpg")
        )
        val item = shortlistItem("evt-1", "https://example.com/1").copy(sourceLinks = cluster)
        fun deliver(og: ImageCandidateOrNull, images: metifikys.config.PostImagesConfig): String? {
            val db = mockk<NewsDatabase>(relaxed = true)
            every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
            val sender = mockk<TelegramSender>(relaxed = true)
            val photo = slot<String>()
            every { sender.sendPhotoToChannel(any(), capture(photo), any(), any()) } returns SentRef(-100L, 12L)
            val cfg = configWithPost(PostConfig(images = images), enableImages = true)
            DigestDeliverer(cfg, db, sender, postImages = PostImages({ og }, { null }))
                .deliver("tech", "• **Story.**\n\n[Title 1](https://example.com/1)", articles, listOf(item))
            return if (photo.isCaptured) photo.captured else null
        }
        val cfg = metifikys.config.PostImagesConfig()
        assertEquals("https://example.com/og.jpg", deliver(metifikys.fetch.ImageCandidate("https://example.com/og.jpg", 1200, 630), cfg))
        assertEquals("https://cdn.example.com/hero.jpg", deliver(null, cfg))
        assertEquals(null, deliver(null, cfg.copy(fromCluster = false)))
    }

    // ── post.crossLinks (🔗) ─────────────────────────────────────────────────

    @Test
    fun `cross link sits after the intro slot and before the hashtags`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val vector = metifikys.digest.VectorMath.encode(floatArrayOf(1f, 0f))
        every { db.fetchEventEmbeddings("tech", any()) } returns mapOf(
            "evt-1" to metifikys.db.EventEmbeddingRow("tech", "evt-1", "S", "", "m", vector, LocalDateTime.now())
        )
        every { db.fetchRecentEventEmbeddings("science", any(), any()) } returns listOf(
            metifikys.db.EventEmbeddingRow("science", "sci-1", "Big study", "", "m", vector, LocalDateTime.now())
        )
        every { db.fetchLatestDigestMessagesByEventKeys("science", setOf("sci-1"), any()) } returns
            mapOf("sci-1" to DigestMessageRow(-100L, 5L, "science", "sci-1", "", LocalDateTime.now()))
        val sender = mockk<TelegramSender>(relaxed = true)
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any()) } returns listOf(SentRef(-100L, 11L))
        val base = configWithPost(
            PostConfig(
                hashtags = HashtagsConfig(category = "#tech"),
                crossLinks = metifikys.config.CrossLinksConfig(categories = listOf("science"))
            )
        )
        val cfg = base.copy(categories = base.categories + ("science" to CategoryConfig(
            emoji = "🔬", feeds = listOf(FeedConfig("https://example.com/sci")), channelId = "@science"
        )))
        DigestDeliverer(cfg, db, sender)
            .deliver("tech", "• **Story.**\n\n[Src](https://example.com/1)", listOf(article(1)), listOf(shortlistItem("evt-1", "https://example.com/1")))
        assertEquals(
            "**Story.**\n\n[Src](https://example.com/1)\n\n🔗 У 🔬 Science: [Big study](https://t.me/science/5)\n#tech",
            sent.captured
        )
        verify { db.saveSummary("tech", match { "t.me" !in it }, 1) }
    }

    // ── post.longForm.telegraph (⚡) ─────────────────────────────────────────

    private fun telegraphDelivery(
        mode: String,
        pageUrl: String?
    ): Triple<NewsDatabase, TelegramSender, String> {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchRecentSummaries(any(), any()) } returns emptyList()
        val sender = mockk<TelegramSender>(relaxed = true)
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any(), any()) } returns listOf(SentRef(-100L, 41L))
        val publisher = mockk<metifikys.telegraph.TelegraphPublisher>()
        every { publisher.publish(any(), any(), any(), any(), any()) } returns pageUrl
        val withImage = article(1).copy(imageUrl = "https://img.example/1.jpg")
        val body = "Перший абзац. ".repeat(40).trim() + "\n\n" + "Другий абзац. ".repeat(40).trim()
        val summary = "• 🎮 **Story.** $body\n[Title 1](https://example.com/1)"
        val long = shortlistItem("evt-1", withImage.link).copy(depth = ShortlistItem.DEPTH_LONG)
        val post = PostConfig(
            hashtags = HashtagsConfig(category = "#tech"),
            longForm = metifikys.config.LongFormConfig(telegraph = metifikys.config.LongFormTelegraphConfig(mode = mode))
        )
        DigestDeliverer(configWithPost(post, enableImages = true), db, sender, telegraph = publisher)
            .deliver("tech", summary, listOf(withImage), listOf(long))
        return Triple(db, sender, sent.captured)
    }

    @Test
    fun `telegraph teaser drops the second paragraph and previews the page`() {
        val (db, sender, sent) = telegraphDelivery("teaser", "https://telegra.ph/Story-09-23")
        verify { sender.sendToChannel("@tech", any(), true, null, "https://telegra.ph/Story-09-23") }
        assertFalse("Другий абзац" in sent)
        assertTrue(sent.startsWith("🎮 **Story.**\n\nПерший абзац."))
        assertTrue(sent.endsWith("⚡ [Читати повністю](https://telegra.ph/Story-09-23)\n\n[Title 1](https://example.com/1)\n\n#tech"))
        // The stored summary is the full clean topic — no teaser cut, no page link.
        verify { db.saveSummary("tech", match { "Другий абзац" in it && "telegra.ph" !in it }, 1) }
    }

    @Test
    fun `telegraph full mode keeps the whole body and adds the read-more line`() {
        val (_, _, sent) = telegraphDelivery("full", "https://telegra.ph/Story-09-23")
        assertTrue("\n\nДругий абзац." in sent)
        assertTrue("⚡ [Читати повністю](https://telegra.ph/Story-09-23)\n\n[Title 1](https://example.com/1)" in sent)
    }

    @Test
    fun `a failed telegraph page sends the long post as before`() {
        val (_, sender, sent) = telegraphDelivery("teaser", null)
        verify { sender.sendToChannel("@tech", any(), true, null, "https://img.example/1.jpg") }
        assertTrue("\n\nДругий абзац." in sent)
        assertFalse("⚡" in sent)
    }
}

private typealias ImageCandidateOrNull = metifikys.fetch.ImageCandidate?
