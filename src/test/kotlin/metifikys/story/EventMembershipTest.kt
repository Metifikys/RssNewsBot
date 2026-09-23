package metifikys.story

import metifikys.model.Article
import metifikys.model.ExtractionItem
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventMembershipTest {

    private fun article(link: String) = Article(
        category = "games", title = "T $link", link = link, description = "d", pubDate = LocalDateTime.of(2026, 9, 1, 12, 0)
    )

    private fun ext(index: Int, key: String, url: String, status: String = "new") =
        ExtractionItem(articleIndex = index, eventKey = key, coreFact = "f", url = url, status = status)

    private fun item(key: String, url: String, indices: List<Int> = emptyList(), status: String = "new") =
        ShortlistItem(eventKey = key, coreFact = "f", url = url, status = status, articleIndices = indices)

    private val a = article("https://a.com/1")
    private val b = article("https://b.com/2")
    private val c = article("https://c.com/3")

    @Test
    fun `extractions join their article by url`() {
        val members = EventMembership.resolve(
            listOf(ext(0, "k1", b.link), ext(1, "k2", a.link, "duplicate")),
            emptyList(),
            listOf(a, b)
        )
        assertEquals(mapOf(b.link to "k1", a.link to "k2"), members.associate { it.link to it.eventKey })
        assertEquals("duplicate", members.single { it.link == a.link }.step1Status)
    }

    @Test
    fun `a url outside the batch is not a member, even with a valid index`() {
        val members = EventMembership.resolve(listOf(ext(0, "k1", "https://elsewhere.com/x")), emptyList(), listOf(a))
        assertTrue(members.isEmpty())
    }

    @Test
    fun `a shuffled article list (resume) still joins by url`() {
        val ex = listOf(ext(0, "k-a", a.link), ext(1, "k-b", b.link), ext(2, "k-c", c.link))
        val members = EventMembership.resolve(ex, emptyList(), listOf(c, a, b))
        assertEquals(mapOf(a.link to "k-a", b.link to "k-b", c.link to "k-c"), members.associate { it.link to it.eventKey })
    }

    @Test
    fun `an extraction without url falls back to its index`() {
        val members = EventMembership.resolve(listOf(ext(1, "k", "")), emptyList(), listOf(a, b))
        assertEquals(b.link, members.single().link)
    }

    @Test
    fun `1-based batches are recognised from the url anchors`() {
        val ex = listOf(ext(1, "k-a", a.link), ext(2, "k-b", b.link), ext(3, "k-c", ""))
        val members = EventMembership.resolve(ex, emptyList(), listOf(a, b, c))
        assertEquals("k-c", members.single { it.link == c.link }.eventKey)
    }

    @Test
    fun `1-based batches without anchors are recognised from the index range`() {
        val members = EventMembership.resolve(listOf(ext(2, "k", ""), ext(1, "k0", "")), emptyList(), listOf(a, b))
        assertEquals(mapOf(b.link to "k", a.link to "k0"), members.associate { it.link to it.eventKey })
    }

    @Test
    fun `shortlist indices add articles that have no extraction, the extraction wins otherwise`() {
        val members = EventMembership.resolve(
            listOf(ext(0, "own", a.link, "rejected")),
            listOf(item("story", b.link, indices = listOf(0, 1, 2))),
            listOf(a, b, c)
        )
        assertEquals(mapOf(a.link to "own", b.link to "story", c.link to "story"), members.associate { it.link to it.eventKey })
    }

    @Test
    fun `1-based shortlist indices resolve as the render prompt resolves them`() {
        val members = EventMembership.resolve(emptyList(), listOf(item("story", a.link, indices = listOf(1, 2))), listOf(a, b))
        assertEquals(setOf(a.link, b.link), members.map { it.link }.toSet())
    }

    @Test
    fun `sourceLinks are the item's own articles plus in-batch copies, never rejects`() {
        val story = item("story", a.link, indices = listOf(0))
        val members = EventMembership.resolve(
            listOf(
                ext(0, "story", a.link),
                ext(1, "story", b.link, "duplicate"),
                ext(2, "story", c.link, "rejected")
            ),
            listOf(story),
            listOf(a, b, c)
        )
        assertEquals(listOf(a.link, b.link), EventMembership.sourceLinks(story, members, listOf(a, b, c)))
    }

    @Test
    fun `sourceLinks without indices fall back to the item url`() {
        val story = item("story", b.link)
        assertEquals(listOf(b.link), EventMembership.sourceLinks(story, emptyList(), listOf(a, b)))
    }
}
