package metifikys.feeds

import metifikys.feeds.FeedQuality.Hint
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedHealthSectionTest {

    private val now = LocalDateTime.of(2026, 9, 23, 12, 0)

    private fun m(url: String, cat: String, vararg hints: Hint, total: Int = 10) = FeedQuality.FeedMetrics(
        feedUrl = url, category = cat, total = total, pending = 0, published = 0, muted = 0, dupL2 = 0,
        step1Duplicate = 0, step1Rejected = 0, unselected = total, firstSource = 0, echo = 0, medianLagMinutes = null,
        lastSuccess = now.minusDays(5), lastNew = null, failStreak = 12, lastError = "HTTP 403", avgJunk = null,
        hints = hints.toList()
    )

    @Test
    fun `summary counts per category and anomalies ordered by severity`() {
        val lines = FeedHealthSection.render(
            listOf(
                m("https://a.com/feed", "tech"),
                m("https://b.com/feed", "tech", Hint.NOISY, total = 200),
                m("https://c.com/feed", "tech", Hint.DEAD),
                m("https://d.com/feed", "gaming", Hint.PRIMARY)
            ),
            14, mapOf("https://c.com/feed" to "C_feed"), listOf("tech", "gaming"), now, escape = { it.replace("_", "") }
        )
        assertEquals("📡 *Feeds* (14d): tech 1 ok · 1 💀 · 1 🗑 | gaming 1 ok", lines[0])
        assertTrue(lines[1].startsWith("  💀 tech Cfeed — no success 5d, 12 failed in a row (HTTP 403)"), lines[1])
        assertTrue(lines[2].startsWith("  🗑 tech b.com/feed — 200 art."), lines[2])
        assertEquals(3, lines.size, "⭐ is not an anomaly line")
    }

    @Test
    fun `never more than eight lines`() {
        val many = (1..20).map { m("https://x$it.com/feed", "tech", Hint.DEAD) }
        val lines = FeedHealthSection.render(many, 14, emptyMap(), listOf("tech"), now)
        assertEquals(FeedHealthSection.MAX_LINES, lines.size)
        assertTrue(lines.last().endsWith("(+13 more in the report)"), lines.last())
    }

    @Test
    fun `no feeds, no section`() {
        assertTrue(FeedHealthSection.render(emptyList(), 14, emptyMap(), listOf("tech"), now).isEmpty())
    }
}
