package metifikys.story

import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventClusterTest {

    private val t0 = LocalDateTime.of(2026, 9, 1, 12, 0)

    private fun row(id: Int, domain: String, pubMin: Long, seenMin: Long? = pubMin, key: String = "k") = EventArticleRow(
        category = "games", articleId = id, eventKey = key, link = "https://$domain/$id", domain = domain,
        pubDate = t0.plusMinutes(pubMin), seenAt = seenMin?.let { t0.plusMinutes(it) },
        role = EventArticleRole.STEP1, step1Status = "new", recordedAt = t0
    )

    @Test
    fun `outlets count distinct domains, aliases folded in`() {
        val c = EventCluster(
            "k",
            listOf(row(1, "ign.com", 0), row(2, "ign.com", 5), row(3, "eurogamer.de", 10), row(4, "eurogamer.net", 20)),
            mapOf("eurogamer.de" to "eurogamer.net")
        )
        assertEquals(2, c.outlets)
        assertEquals(listOf("ign.com", "eurogamer.net"), c.byDomain.keys.toList())
    }

    @Test
    fun `a feed date from the future is replaced by our first sighting`() {
        val future = row(1, "a.com", pubMin = 180, seenMin = 30)
        assertEquals(t0.plusMinutes(30), EventCluster.timeOf(future))
        val slightlyAhead = row(2, "b.com", pubMin = 35, seenMin = 30)
        assertEquals(t0.plusMinutes(35), EventCluster.timeOf(slightlyAhead))
        val noSeen = row(3, "c.com", pubMin = 999, seenMin = null)
        assertEquals(t0.plusMinutes(999), EventCluster.timeOf(noSeen))
    }

    @Test
    fun `earliest is by effective time`() {
        val c = EventCluster("k", listOf(row(1, "a.com", pubMin = 500, seenMin = 5), row(2, "b.com", 10)))
        assertEquals(1, c.earliest?.articleId)
        assertNull(EventCluster("k", emptyList()).earliest)
    }

    @Test
    fun `of merges the related key's rows and counts an article once`() {
        val rows = mapOf(
            "upd" to listOf(row(1, "a.com", 60, key = "upd")),
            "orig" to listOf(row(2, "b.com", 0, key = "orig"), row(1, "a.com", 60, key = "orig"))
        )
        val c = EventCluster.of("upd", rows, listOf("orig", null))
        assertEquals(2, c.members.size)
        assertEquals(2, c.earliest?.articleId)
    }
}
