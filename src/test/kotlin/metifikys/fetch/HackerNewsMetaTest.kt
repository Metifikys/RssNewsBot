package metifikys.fetch

import metifikys.model.ArticleMeta
import metifikys.model.HnMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HackerNewsMetaTest {

    private val hnrss = """
        <p>Article URL: <a href="https://example.com/post">https://example.com/post</a></p>
        <p>Comments URL: <a href="https://news.ycombinator.com/item?id=41234567">https://news.ycombinator.com/item?id=41234567</a></p>
        <p>Points: 512</p>
        <p># Comments: 340</p>
    """.trimIndent()

    @Test
    fun `parses id, points and comments from an hnrss description`() {
        assertEquals(HnMeta(id = 41234567, points = 512, comments = 340), HackerNewsMeta.fromDescription(hnrss))
    }

    @Test
    fun `counts are optional, the item id is not`() {
        assertEquals(
            HnMeta(id = 7, points = null, comments = null),
            HackerNewsMeta.fromDescription("""<a href="https://news.ycombinator.com/item?id=7">x</a>""")
        )
        assertNull(HackerNewsMeta.fromDescription("<p>Points: 12</p><p># Comments: 3</p>"))
        assertNull(HackerNewsMeta.fromDescription(""))
        assertNull(HackerNewsMeta.fromDescription(null))
    }

    @Test
    fun `meta round-trips through the persisted JSON`() {
        val meta = ArticleMeta(hn = HackerNewsMeta.fromDescription(hnrss))
        val json = meta.toJson()
        assertEquals(meta, ArticleMeta.parse(json))
        assertEquals("https://news.ycombinator.com/item?id=41234567", ArticleMeta.parse(json)!!.hn!!.discussionUrl)
        assertNull(ArticleMeta.parse(null))
        assertNull(ArticleMeta.parse("not json"))
    }
}
