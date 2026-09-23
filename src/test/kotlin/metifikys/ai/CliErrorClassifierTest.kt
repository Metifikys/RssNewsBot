package metifikys.ai

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CliErrorClassifierTest {

    @Test
    fun `claude - expired OAuth is auth`() {
        assertEquals(
            CliFailure.AUTH,
            CliErrorClassifier.classifyClaude("API Error: 401 {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"OAuth token has expired. Please obtain a new token or refresh your existing token.\"}} · Please run /login")
        )
        assertEquals(CliFailure.AUTH, CliErrorClassifier.classifyClaude("Invalid API key · Please run /login"))
    }

    @Test
    fun `claude - subscription limit is limit, even though it mentions login-ish words`() {
        assertEquals(CliFailure.LIMIT, CliErrorClassifier.classifyClaude("Claude AI usage limit reached|1757000000"))
        assertEquals(CliFailure.LIMIT, CliErrorClassifier.classifyClaude("You've hit your limit · resets 3am (Europe/Kyiv)"))
    }

    @Test
    fun `claude - unknown model is model`() {
        assertEquals(
            CliFailure.MODEL,
            CliErrorClassifier.classifyClaude("There's an issue with the selected model (claude-foo). It may not exist or you may not have access to it.")
        )
    }

    @Test
    fun `claude - anything else is other`() {
        assertEquals(CliFailure.OTHER, CliErrorClassifier.classifyClaude("API Error: 529 Overloaded"))
    }

    @Test
    fun `codex phrases`() {
        assertEquals(CliFailure.AUTH, CliErrorClassifier.classifyCodex("Error: Not logged in. Please run `codex login`."))
        assertEquals(CliFailure.LIMIT, CliErrorClassifier.classifyCodex("stream error: You've hit your usage limit. Try again in 3 hours."))
        assertEquals(CliFailure.MODEL, CliErrorClassifier.classifyCodex("model gpt-9 not found"))
        assertEquals(CliFailure.OTHER, CliErrorClassifier.classifyCodex("stream disconnected before completion"))
    }

    @Test
    fun `reset hint is lifted from a limit message`() {
        assertEquals("resets 3am (Europe/Kyiv)", CliErrorClassifier.resetHint("You've hit your limit · resets 3am (Europe/Kyiv)"))
        assertEquals("Try again in 3 hours", CliErrorClassifier.resetHint("You've hit your usage limit. Try again in 3 hours."))
        assertNull(CliErrorClassifier.resetHint("usage limit reached"))
    }
}
