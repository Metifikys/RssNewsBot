package metifikys.format

import metifikys.config.PersonaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonaRemarkTest {

    private val cfg = PersonaConfig(maxChars = 80, promptFile = "p.yaml")
    private val topic = "**Silksong виходить 4 вересня.**\n\nTeam Cherry назвала дату за 15 доларів. " +
        "[IGN](https://www.ign.com/articles/2025/silksong-99)"

    private fun check(raw: String) = PersonaRemark.check(raw, topic, cfg)

    @Test
    fun `a clean remark passes, wrapping quotes and a leading marker are stripped`() {
        assertEquals("Сім років чекання — і всього 15 доларів.", check("  «Сім років чекання — і всього 15 доларів.» ").text)
        assertEquals("Нарешті.", check("💬 \"Нарешті.\"").text)
        assertEquals("💬 Нарешті.", PersonaRemark.line("Нарешті."))
    }

    @Test
    fun `numbers must come from the topic text, not from its link`() {
        assertTrue(check("Четверте вересня, запишіть.").text != null)
        assertEquals("number '5' not in the topic", check("Через 5 років буде сиквел.").reason)
        // 2025 and 99 appear only in the link URL.
        assertNull(check("У 2025 нарешті.").text)
        assertNull(check("Оцінка 99 гарантована.").text)
        assertTrue(check("Лише 4 вересня!").text != null)
    }

    @Test
    fun `urls, markup, several lines, banned phrases and length are rejected`() {
        assertEquals("URL", check("Деталі на https://x.com").reason)
        assertEquals("URL", check("Дивіться ign.com").reason)
        assertEquals("markup '#'", check("Нарешті #silksong").reason)
        assertEquals("markup '['", check("Див. [тут]").reason)
        assertEquals("markup '**'", check("**Нарешті**").reason)
        assertEquals("several lines", check("Раз.\nДва.").reason)
        assertTrue(check("Це важливо, бо чекали довго.").reason!!.startsWith("banned phrase"))
        assertTrue(check("а".repeat(81)).reason!!.contains("maxChars=80"))
        assertEquals("empty", check(" «» ").reason)
    }

    @Test
    fun `the arm is fixed by the event key and follows the share`() {
        val keys = (1..2000).map { "event-$it" }
        assertTrue(keys.none { PersonaRemark.inPersonaArm(it, 0.0) })
        assertTrue(keys.all { PersonaRemark.inPersonaArm(it, 1.0) })
        val half = keys.count { PersonaRemark.inPersonaArm(it, 0.5) }
        assertTrue(half in 850..1150, "persona share of 2000 keys at 0.5: $half")
        assertEquals(PersonaRemark.inPersonaArm("hades-ii|release", 0.5), PersonaRemark.inPersonaArm("hades-ii|release", 0.5))
        assertFalse(PersonaRemark.inPersonaArm("x", 0.0))
    }
}
