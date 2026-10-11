package metifikys.format

import metifikys.format.FooterLines.Slot
import kotlin.test.Test
import kotlin.test.assertEquals

class FooterLinesTest {

    @Test
    fun `lines come out in slot order whatever order they were added in`() {
        val out = FooterLines()
            .add(Slot.HASHTAGS, "#gaming")
            .add(Slot.ALSO, "📰 Також: [IGN](https://ign.com/a)")
            .add(Slot.STORY, "📢 5 видань")
            .add(Slot.INTRO, "📚 Team Cherry")
            .add(Slot.BADGES, "🎮 Steam: ₴899")
            .add(Slot.PERSONA, "💬 Нарешті.")
            .build()
        assertEquals(
            listOf("💬 Нарешті.", "🎮 Steam: ₴899", "📚 Team Cherry", "📢 5 видань", "📰 Також: [IGN](https://ign.com/a)", "#gaming"),
            out
        )
    }

    @Test
    fun `lines of one slot keep their order and blanks are skipped`() {
        val out = FooterLines()
            .addAll(Slot.BADGES, listOf("🎮 Steam", "", "▶️ [Трейлер](https://youtu.be/x)"))
            .add(Slot.STORY, null)
            .add(Slot.STORY, "  ")
            .build()
        assertEquals(listOf("🎮 Steam", "▶️ [Трейлер](https://youtu.be/x)"), out)
    }

    @Test
    fun `the priority order is persona, badges, date, intro, explore, cross-link, story, also, hashtags`() {
        assertEquals(
            listOf("PERSONA", "BADGES", "DATE", "INTRO", "EXPLORE", "CROSS_LINK", "STORY", "ALSO", "HASHTAGS"),
            Slot.entries.map { it.name }
        )
    }
}
