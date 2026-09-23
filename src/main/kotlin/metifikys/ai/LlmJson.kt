package metifikys.ai

/**
 * JSON answers of a model, before a caller parses them. Every provider can wrap its JSON in a
 * Markdown fence despite the prompt (and OpenAI-compatible JSON mode on OpenRouter models does not
 * prevent it), so both the clients' `completeJson` and the parsers of its result go through here.
 */
object LlmJson {

    /**
     * [s] trimmed and without a Markdown code fence around it: ` ```json\n…\n``` ` or ` ```\n…\n``` `.
     * Anything after the closing fence is dropped; an unclosed fence keeps the rest. Text that does
     * not start with a fence is returned trimmed, so calling this twice is harmless.
     */
    fun stripCodeFences(s: String): String {
        val trimmed = s.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed
        val withoutOpening = trimmed.substring(firstNewline + 1)
        val closing = withoutOpening.lastIndexOf("```")
        return if (closing >= 0) withoutOpening.substring(0, closing).trim() else withoutOpening.trim()
    }
}
