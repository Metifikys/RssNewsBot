package metifikys.format

import kotlin.test.Test
import kotlin.test.assertEquals

class TopicDecoratorTest {

    private val topic = "🎮 **Headline.**\n\nBody.\n\n[Source](https://example.com/1)"

    @Test
    fun `no footer leaves the topic untouched`() {
        assertEquals(topic, TopicDecorator.decorate(topic, emptyList(), TopicDecorator.TEXT_BUDGET))
        assertEquals(topic, TopicDecorator.decorate(topic, listOf("", "   "), TopicDecorator.TEXT_BUDGET))
    }

    @Test
    fun `footer lines follow the bullet in the given order, one per line`() {
        val out = TopicDecorator.decorate(topic, listOf("🎮 Steam: ₴899", "#gaming #silksong"), TopicDecorator.TEXT_BUDGET)
        assertEquals("$topic\n🎮 Steam: ₴899\n#gaming #silksong", out)
    }

    @Test
    fun `over budget the last footer line goes first`() {
        val body = "x".repeat(1000)
        val badge = "b".repeat(20)
        val tags = "#" + "t".repeat(29)
        // 1000 + 1 + 20 = 1021 fits in 1030; adding the tag line (1021 + 1 + 30) does not.
        assertEquals("$body\n$badge", TopicDecorator.decorate(body, listOf(badge, tags), 1030))
        // Nothing fits → the bare bullet.
        assertEquals(body, TopicDecorator.decorate(body, listOf(badge, tags), 1010))
    }

    @Test
    fun `trailing whitespace of the bullet does not produce a blank line`() {
        assertEquals("bullet\n#tag", TopicDecorator.decorate("bullet  \n\n", listOf("#tag"), 100))
    }
}
