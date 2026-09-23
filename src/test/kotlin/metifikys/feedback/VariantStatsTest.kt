package metifikys.feedback

import metifikys.db.ReactionCount
import metifikys.db.VariantPost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VariantStatsTest {

    private fun post(variant: String, vararg r: Pair<String, Int>, category: String = "gaming") =
        VariantPost(category, variant, r.map { ReactionCount(it.first, it.second) })

    @Test
    fun `arms per category with reactions per post, reacted share and positive share`() {
        val stats = VariantStats.aggregate(
            listOf(
                post("control", "👍" to 1),
                post("persona", "👍" to 2, "👎" to 1),
                post("persona"),
                post("explore", "🔥" to 1),
                post("control"),
                post("persona", "👍" to 1, category = "tech")
            ),
            ReactionValence()
        )
        val gaming = stats.getValue("gaming")
        assertEquals(listOf("persona", "control", "explore"), gaming.map { it.variant })
        val persona = gaming[0]
        assertEquals(2, persona.posts)
        assertEquals(1.5, persona.perPost)
        assertEquals(0.5, persona.reactedShare)
        assertEquals(2.0 / 3, persona.positiveShare)
        assertEquals("persona 2 · 1.5/post · 50% reacted · 67% 👍+", VariantStats.cell(persona))
        assertEquals("control 2 · 0.5/post · 50% reacted · 100% 👍+", VariantStats.cell(gaming[1]))
        assertEquals(1, stats.getValue("tech").single().posts)
    }

    @Test
    fun `an arm without reactions has no positive share`() {
        val arm = VariantStats.aggregate(listOf(post("control")), ReactionValence()).getValue("gaming").single()
        assertNull(arm.positiveShare)
        assertEquals("control 1 · 0.0/post · 0% reacted", VariantStats.cell(arm))
    }
}
