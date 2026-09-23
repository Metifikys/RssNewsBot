package metifikys.ai

/** `SHADOW_*`: the B side of an `llm.shadow` run, metered apart from the live calls. */
enum class LlmUseCase { EXTRACT, RENDER, BATCH, SUMMARIZE, EMBED, PERSONA, SHADOW_EXTRACT, SHADOW_RENDER }
