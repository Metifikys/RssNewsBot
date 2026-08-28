package metifikys.digest

import metifikys.model.Article
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicMuteFilterTest {

    private fun article(title: String = "", description: String = "") = Article(
        category = "games", title = title, link = "https://example.com/x", description = description
    )

    @Test
    fun `empty filter never matches`() {
        val f = TopicMuteFilter.of(emptyList())
        assertTrue(f.isEmpty())
        assertFalse(f.matches(article(title = "Call of Duty reveal")))
    }

    @Test
    fun `matches keyword case-insensitively as a whole word`() {
        val f = TopicMuteFilter.of(listOf("cod"))
        assertTrue(f.matches(article(title = "New CoD trailer drops")))
        assertTrue(f.matches(article(title = "COD: Warzone update")))
    }

    @Test
    fun `does not match keyword embedded in a larger word`() {
        val f = TopicMuteFilter.of(listOf("cod"))
        assertFalse(f.matches(article(title = "How to write clean code")))
        assertFalse(f.matches(article(description = "The codex was updated")))
    }

    @Test
    fun `matches multi-word phrase`() {
        val f = TopicMuteFilter.of(listOf("call of duty"))
        assertTrue(f.matches(article(title = "Call of Duty: Black Ops announced")))
        assertFalse(f.matches(article(title = "The call of the wild")))
    }

    @Test
    fun `checks description as well as title`() {
        val f = TopicMuteFilter.of(listOf("warzone"))
        assertTrue(f.matches(article(title = "FPS roundup", description = "Warzone gets a new map")))
    }

    @Test
    fun `any of multiple keywords triggers a match`() {
        val f = TopicMuteFilter.of(listOf("warzone", "modern warfare"))
        assertTrue(f.matches(article(title = "Modern Warfare III patch notes")))
        assertFalse(f.matches(article(title = "Elden Ring DLC")))
    }

    @Test
    fun `blank keywords are ignored`() {
        val f = TopicMuteFilter.of(listOf("  ", ""))
        assertTrue(f.isEmpty())
    }
}
