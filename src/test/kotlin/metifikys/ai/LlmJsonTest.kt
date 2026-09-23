package metifikys.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmJsonTest {

    private val obj = """{"a": 1}"""

    @Test
    fun `a bare answer is only trimmed`() {
        assertEquals(obj, LlmJson.stripCodeFences(obj))
        assertEquals(obj, LlmJson.stripCodeFences("\n  $obj  \n"))
    }

    @Test
    fun `a json-tagged or untagged fence is removed`() {
        assertEquals(obj, LlmJson.stripCodeFences("```json\n$obj\n```"))
        assertEquals(obj, LlmJson.stripCodeFences("```\n$obj\n```"))
        assertEquals(obj, LlmJson.stripCodeFences("  ```JSON\n\n$obj\n\n```\n"))
    }

    @Test
    fun `a multi-line object keeps its inner lines`() {
        val multi = "{\n  \"a\": 1,\n  \"b\": [2, 3]\n}"
        assertEquals(multi, LlmJson.stripCodeFences("```json\n$multi\n```"))
    }

    @Test
    fun `prose after the closing fence is dropped, an unclosed fence keeps the rest`() {
        assertEquals(obj, LlmJson.stripCodeFences("```json\n$obj\n```\nHope this helps!"))
        assertEquals(obj, LlmJson.stripCodeFences("```json\n$obj"))
    }

    @Test
    fun `a one-line fence without a newline is left alone`() {
        assertEquals("```$obj```", LlmJson.stripCodeFences("```$obj```"))
    }

    @Test
    fun `stripping twice changes nothing`() {
        val once = LlmJson.stripCodeFences("```json\n$obj\n```")
        assertEquals(once, LlmJson.stripCodeFences(once))
    }
}
