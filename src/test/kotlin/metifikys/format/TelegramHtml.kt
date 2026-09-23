package metifikys.format

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Test-side validator for what [TopicFormatter.toHtml] may emit under `parse_mode=HTML`: only
 * `<b>`, `<code>`, `<a href="…">` tags, balanced; no raw `<` / `>` outside tags; `&` only as
 * `&lt;` `&gt;` `&amp;` `&quot;`; an `href` free of raw quotes and angle brackets.
 */
object TelegramHtml {

    private val TAG = Regex("""<(/?)(b|code|a)(?: href="([^"<>]*)")?>""")
    private val ENTITY = Regex("""&(lt|gt|amp|quot);""")
    private val ENTITY_VALUES = mapOf("lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"")

    /** Asserts [html] is valid Telegram HTML and returns its visible text (tags removed, entities decoded). */
    fun visibleText(html: String): String {
        val visible = StringBuilder()
        val open = ArrayDeque<String>()
        var i = 0
        while (i < html.length) {
            when (html[i]) {
                '<' -> {
                    val m = TAG.matchAt(html, i) ?: fail("raw '<' or unsupported tag at $i: ${html.substring(i).take(40)}")
                    val closing = m.groupValues[1] == "/"
                    val name = m.groupValues[2]
                    val href = m.groups[3]
                    if (closing) {
                        assertTrue(href == null, "closing tag with attributes at $i")
                        assertEquals(name, open.removeLastOrNull(), "unbalanced </$name> at $i")
                    } else {
                        assertTrue((name == "a") == (href != null), "<$name> attribute mismatch at $i")
                        href?.value?.let { checkEntities(it, "href") }
                        open.addLast(name)
                    }
                    i = m.range.last + 1
                }
                '>' -> fail("raw '>' at $i")
                '&' -> {
                    val m = ENTITY.matchAt(html, i) ?: fail("bare '&' at $i: ${html.substring(i).take(20)}")
                    visible.append(ENTITY_VALUES.getValue(m.groupValues[1]))
                    i = m.range.last + 1
                }
                else -> visible.append(html[i++])
            }
        }
        assertTrue(open.isEmpty(), "unclosed tags: $open")
        return visible.toString()
    }

    private fun checkEntities(value: String, where: String) {
        var i = value.indexOf('&')
        while (i >= 0) {
            assertTrue(ENTITY.matchAt(value, i) != null, "bare '&' in $where at $i: $value")
            i = value.indexOf('&', i + 1)
        }
    }

    /** Reverses [TopicFormatter.escapeHtml] (and the attribute `&quot;`). */
    fun unescape(s: String): String =
        ENTITY.replace(s) { ENTITY_VALUES.getValue(it.groupValues[1]) }

    /** True when [s] has no unpaired UTF-16 surrogate. */
    fun surrogatesPaired(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                i += 2
            } else {
                if (c.isLowSurrogate()) return false
                i++
            }
        }
        return true
    }
}
