package metifikys.format

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Invariants of [TopicDecorator.decorate] over generated bullets and footers. */
class TopicDecoratorPropertyTest {

    private val topics = Arb.choice(FormatterArbs.topic(), FormatterArbs.text(5000))
    private val budgets = Arb.int(0..5000)

    @Test
    fun `the bullet is never touched and the budget holds whenever the bullet itself fits`() = runBlocking<Unit> {
        checkAll(1000, topics, FormatterArbs.footer(), budgets) { topic, footer, budget ->
            val out = TopicDecorator.decorate(topic, footer, budget)
            if (out == topic) return@checkAll
            val head = topic.trimEnd()
            assertTrue(out.startsWith("$head\n\n"), "decorated text must start with the untouched bullet")
            assertTrue(out.length <= budget, "decorated ${out.length} > budget $budget")
        }
    }

    @Test
    fun `kept footer lines are a prefix of the non-blank footer, dropped from the end`() = runBlocking<Unit> {
        checkAll(1000, topics, FormatterArbs.footer(), budgets) { topic, footer, budget ->
            val lines = footer.map { it.trim() }.filter { it.isNotEmpty() }
            val out = TopicDecorator.decorate(topic, footer, budget)
            val kept = if (out == topic) emptyList() else out.removePrefix(topic.trimEnd() + "\n\n").split("\n")
            assertEquals(lines.take(kept.size), kept)
            if (lines.isEmpty()) assertEquals(topic, out, "an empty footer returns the bullet as-is")
        }
    }

    @Test
    fun `a larger budget never keeps fewer footer lines`() = runBlocking<Unit> {
        checkAll(1000, topics, FormatterArbs.footer(), budgets, Arb.int(0..2000)) { topic, footer, budget, extra ->
            fun keptLines(b: Int): Int {
                val out = TopicDecorator.decorate(topic, footer, b)
                return if (out == topic) 0 else out.removePrefix(topic.trimEnd() + "\n\n").split("\n").size
            }
            assertTrue(keptLines(budget + extra) >= keptLines(budget))
        }
    }
}
