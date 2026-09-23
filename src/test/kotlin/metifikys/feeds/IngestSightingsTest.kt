package metifikys.feeds

import metifikys.model.Article
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestSightingsTest {

    private fun a(link: String, feed: String?) = Article("tech", "t", link, "d", feedUrl = feed)

    @Test
    fun `a feed re-reading its own links is no sighting`() {
        val split = IngestSightings.split(listOf(a("l1", "A"), a("l2", "A")), mapOf("l1" to "A"))
        assertEquals(listOf("l2"), split.fresh.map { it.link })
        assertTrue(split.sightings.isEmpty())
    }

    @Test
    fun `a link another feed stored earlier is a sighting of the second feed`() {
        val split = IngestSightings.split(listOf(a("l1", "B")), mapOf("l1" to "A"))
        assertTrue(split.fresh.isEmpty())
        assertEquals(listOf("l1" to "B"), split.sightings.map { it.link to it.feedUrl })
    }

    @Test
    fun `within one pass the first feed wins and the second copy is dropped as a sighting`() {
        val split = IngestSightings.split(listOf(a("l1", "A"), a("l1", "B"), a("l1", "A")), emptyMap())
        assertEquals(listOf("A"), split.fresh.map { it.feedUrl })
        assertEquals(listOf("B"), split.sightings.map { it.feedUrl })
    }

    @Test
    fun `rows without a feed never produce sightings`() {
        val split = IngestSightings.split(listOf(a("l1", null), a("l2", "B")), mapOf("l1" to "A", "l2" to null))
        assertTrue(split.sightings.isEmpty())
        assertTrue(split.fresh.isEmpty())
    }
}
