package metifikys.ai

/**
 * `SHADOW_*`: the B side of an `llm.shadow` run, metered apart from the live calls; `JUDGE`: the
 * `factCheck` judge; `UPDATE_DIFF`: the `dedup.digest.updateDiff` check of a `meaningful_update`.
 */
enum class LlmUseCase { EXTRACT, RENDER, BATCH, SUMMARIZE, EMBED, PERSONA, SHADOW_EXTRACT, SHADOW_RENDER, JUDGE, UPDATE_DIFF }
