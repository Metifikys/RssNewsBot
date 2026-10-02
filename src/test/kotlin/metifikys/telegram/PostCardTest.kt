package metifikys.telegram

import metifikys.config.DigestConfig
import metifikys.db.ArticleRecord
import metifikys.db.AdminMuteKind
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import metifikys.db.ExplicitSignal
import metifikys.db.ReactionCount
import metifikys.digest.MuteRegistry
import metifikys.story.EventCluster
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostCardTest {

    private val sent = LocalDateTime.of(2026, 10, 2, 7, 22)
    private val post = DigestMessageRow(
        -1009876543210, 15205, "gaming", "signal_veil_announcement", "https://www.gematsu.com/a", sent
    )
    private val event = CoveredEventRow(
        category = "gaming", eventKey = "signal_veil_announcement", subject = "Signal Veil",
        franchise = "Signal Veil / Serafini Productions", eventType = "major_announcement",
        coreFact = "Serafini Productions анонсувала survival horror Signal Veil.", importance = 3,
        newsworthiness = 3, digestFit = 5, url = "https://www.gematsu.com/a", coveredAt = sent
    )
    private val article = ArticleRecord(
        id = 1, category = "gaming", title = "Survival horror game Signal Veil announced for PS5, Xbox Series, Switch 2, and PC",
        link = "https://www.gematsu.com/a", feedUrl = "https://www.gematsu.com/feed",
        pubDate = LocalDateTime.of(2026, 10, 1, 22, 35), fetchedAt = LocalDateTime.of(2026, 10, 2, 5, 46),
        status = "PROCESSED", dropReason = null
    )

    private fun plain() = PostInspection(
        category = "gaming", emoji = "🎮", post = post, chunks = 1,
        postUrl = "https://t.me/c/9876543210/15205",
        event = event, step1Status = "new", digest = DigestConfig(),
        sources = listOf(PostInspection.Source(article.link, article, "gematsu.com/feed", null)),
        affinity = listOf(PostInspection.AffinityCell("тип", "major_announcement", -0.0743, 93.6, 413.9))
    )

    @Test
    fun `a plain post shows header, event, scores, source timing, audience and filters`() {
        val text = PostCard.render(plain())
        assertTrue(text.startsWith("🔎 🎮 gaming · 02.10 07:22 · <a href=\"https://t.me/c/9876543210/15205\">пост ↗</a>"), text)
        assertTrue("<b>Подія</b> <code>signal_veil_announcement</code>" in text)
        assertTrue("Signal Veil · франшиза: Signal Veil / Serafini Productions" in text)
        assertTrue("тип: анонси (major_announcement) · статус: нова" in text)
        assertTrue("Оцінки: newsworthiness 3 · digestFit 5 · composite 3.8" in text, text)
        assertTrue("опубл. 01.10 22:35 → взято 02.10 05:46 (+7 год)" in text)
        assertTrue("gematsu.com · стрічка gematsu.com/feed" in text)
        assertTrue("Реакції: немає" in text)
        assertTrue("Affinity: тип major_announcement −0.07 (n 93.6/413.9)" in text, text)
        assertTrue("✅ жоден mute зараз не зачіпає цей пост" in text)
        assertTrue(text.endsWith("Своє слово: <code>/mute &lt;слово&gt; gaming</code>"))
        assertFalse("Чому пройшла" in text, "nothing provable for an ordinary post")
        assertFalse("Кластер" in text)
    }

    @Test
    fun `a story-chain update links back and forward and says why it passed`() {
        val update = plain().copy(
            post = post.copy(relatedEventKey = "signal_veil_reveal", variant = DigestMessageRow.VARIANT_EXPLORE),
            step1Status = null,
            previous = PostInspection.ChainPost(sent.minusDays(1), "https://t.me/c/9876543210/15100"),
            laterCount = 2,
            latest = PostInspection.ChainPost(sent.plusDays(1), "https://t.me/c/9876543210/15300"),
            event = event.copy(newsworthiness = 9, digestFit = 2),
            digest = DigestConfig(breakingNewsworthiness = 9),
            chunks = 2,
            reactions = listOf(ReactionCount("👍", 12), ReactionCount("custom:55", 2)),
            adminSignal = ExplicitSignal.LESS
        )
        val text = PostCard.render(update)
        assertTrue("· 2 повідомл. · 🧭 explore" in text, text)
        assertTrue("↩ оновлення до <a href=\"https://t.me/c/9876543210/15100\">попереднього посту ↗</a> (01.10 07:22)" in text)
        assertTrue("↪ далі: 2 оновлення <a href=\"https://t.me/c/9876543210/15300\">↗</a>" in text)
        assertTrue("статус: оновлення до <code>signal_veil_reveal</code>" in text)
        assertTrue("🧭 anti-bubble" in text)
        assertTrue("digestFit 2 < minDigestFit 3, але newsworthiness 9 ≥ 8" in text)
        assertTrue("newsworthiness 9 ≥ поріг breaking 9" in text)
        assertTrue("Реакції: 👍 12 · custom 2" in text)
        assertTrue("Твій сигнал: 👎 менше такого" in text)
    }

    @Test
    fun `a long-form post links its Telegraph page and a cluster line appears with storyIntel rows`() {
        fun member(id: Int, domain: String, minutes: Long, role: String = EventArticleRole.STEP1, status: String? = "new") = EventArticleRow(
            "gaming", id, "signal_veil_announcement", "https://$domain/$id", domain, sent.plusMinutes(minutes), sent.plusMinutes(minutes),
            role, status, null, sent
        )
        val cluster = EventCluster(
            "signal_veil_announcement",
            listOf(member(1, "gematsu.com", 10), member(2, "insider-gaming.com", 0), member(3, "dualshockers.com", 30, EventArticleRole.LAYER2, null))
        )
        val text = PostCard.render(plain().copy(telegraphUrl = "https://telegra.ph/Signal-Veil-10-02", cluster = cluster))
        assertTrue("⚡ лонгрід: <a href=\"https://telegra.ph/Signal-Veil-10-02\">Telegraph ↗</a>" in text)
        assertTrue("<b>Кластер</b>: 3 видання · першим insider-gaming.com (02.10 07:22) · передруків 1" in text, text)
    }

    @Test
    fun `missing data degrades to short notes instead of failing`() {
        val legacy = plain().copy(post = post.copy(eventKey = null), event = null, step1Status = null, affinity = emptyList(),
            sources = listOf(PostInspection.Source("https://x.com/gone", null, null, null)))
        val text = PostCard.render(legacy)
        assertTrue("<b>Подія</b>: немає (legacy-пост без Step 1)" in text)
        assertTrue("статті вже немає в БД (ретеншн)" in text)
        assertFalse("Affinity" in text)

        val pruned = PostCard.render(plain().copy(event = null, eventRetentionDays = 14))
        assertTrue("дані події вже видалені (covered_events живе 14 дн.) · статус: нова" in pruned, pruned)
    }

    @Test
    fun `filters list the mutes that already hit the post`() {
        val text = PostCard.render(
            plain().copy(
                keywordMutes = listOf(MuteRegistry.Entry("signal veil", null, MuteRegistry.Origin.DB)),
                typeMute = MuteRegistry.Entry("major_announcement", "gaming", MuteRegistry.Origin.DB, kind = AdminMuteKind.EVENT_TYPE)
            )
        )
        assertTrue("🔇 уже глушить «signal veil» (глобально · бот)" in text)
        assertTrue("🔇 тип «анонси» зам'ючено в gaming" in text)
        assertFalse("✅" in text)
    }

    @Test
    fun `a card with many long sources stays under the Telegram limit`() {
        val many = (1..40).map { n ->
            PostInspection.Source("https://site$n.com/${"x".repeat(150)}", article.copy(title = "T".repeat(200)), "feed $n", null)
        }
        val text = PostCard.render(plain().copy(sources = many))
        assertTrue(text.length <= PostCard.MAX_CHARS, "${text.length}")
        assertTrue("… ще" in text)
        assertTrue(text.contains("<b>Фільтри</b>"), "sections after the sources survive")
    }

    @Test
    fun `keyword candidates - franchise segment, subject, then distinctive title words`() {
        assertEquals(
            listOf("signal veil", "survival", "horror"),
            PostCard.keywordCandidates("Signal Veil / Serafini Productions", "Signal Veil", listOf(article.title))
        )
        assertEquals(
            listOf("gta", "grand theft auto vi", "rockstar", "delay"),
            PostCard.keywordCandidates("GTA / Rockstar", "Grand Theft Auto VI", listOf("Rockstar confirms GTA 6 delay to 2027", "Take-Two earnings"))
        )
        assertEquals(
            listOf("silksong", "cherry"),
            PostCard.keywordCandidates("game", null, listOf("The Silksong patch from Team Cherry is here")),
            "a generic franchise gives no candidate; generic title words are skipped"
        )
        assertEquals(emptyList(), PostCard.keywordCandidates(null, null, emptyList()))
    }

    @Test
    fun `keyboard - candidates two per row, type row, muted ones checked, callback data within 64 bytes`() {
        val kb = PostCard.keyboard(
            PostCard.KeyboardState(
                -1009876543210, 15205, "gaming",
                listOf("signal veil" to false, "survival" to true, "a very long keyword phrase indeed" to false),
                "major_announcement" to false, note = "🔇 «survival» зам'ючено: gaming"
            )
        )
        assertEquals(
            listOf(listOf("🔇 signal veil ▸", "🔇 survival ✓"), listOf("🔇 a very long keyword phr… ▸"), listOf("🔇 тип: анонси ▸"),
                listOf("🔇 «survival» зам'ючено: gaming")),
            kb.map { row -> row.map { it.text } }
        )
        assertEquals("in:k:-1009876543210:15205:0:${PostCard.tag("signal veil")}", kb[0][0].callbackData)
        assertEquals("in:noop:-1009876543210:15205", kb[0][1].callbackData)
        assertEquals("in:t:-1009876543210:15205", kb[2][0].callbackData)
        val confirm = PostCard.keywordConfirm(-1009876543210, 15205, "gaming", 3, "survival")
        assertEquals(listOf("in:kc:-1009876543210:15205:3:${PostCard.tag("survival")}", "in:kg:-1009876543210:15205:3:${PostCard.tag("survival")}"),
            confirm[0].map { it.callbackData })
        assertTrue(confirm.flatten().all { it.callbackData.toByteArray().size <= 64 })
        assertEquals("🔇 Усі «анонси» у gaming", PostCard.typeConfirm(-1009876543210, 15205, "gaming", "major_announcement")[0][0].text)
    }

    @Test
    fun `delta reads minutes, hours and days, negative for a feed date after our sighting`() {
        val t = LocalDateTime.of(2026, 10, 1, 12, 0)
        assertEquals("+25 хв", PostCard.delta(t, t.plusMinutes(25)))
        assertEquals("+7 год", PostCard.delta(t, t.plusHours(7).plusMinutes(11)))
        assertEquals("+3 дн", PostCard.delta(t, t.plusDays(3)))
        assertEquals("−2 год", PostCard.delta(t, t.minusHours(2)))
    }
}
