package metifikys.ai

import metifikys.config.AppConfig

/**
 * Provider-agnostic descriptor of an LLM chat-completions endpoint.
 *
 * OpenAI and OpenRouter speak the same `/chat/completions` wire format (JSON body,
 * `Bearer` auth); Anthropic uses its own `/messages` shape and `x-api-key` auth.
 * The [provider] tag lets clients dispatch to the right implementation while keeping
 * the cache shape `(baseUrl, model, batchCapable)` unchanged. [extraHeaders] carries
 * provider-specific bits like OpenRouter's `HTTP-Referer` / `X-Title` attribution
 * or Anthropic's `anthropic-version`.
 */
data class LlmEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val provider: Provider = Provider.OPENAI_COMPATIBLE,
    val extraHeaders: Map<String, String> = emptyMap()
) {
    enum class Provider { OPENAI_COMPATIBLE, ANTHROPIC, CLAUDE_CLI }

    companion object {
        /**
         * Picks the sync provider:
         *  - if `openrouter:` block is present in config → OpenRouter
         *  - otherwise → OpenAI
         */
        fun forSync(config: AppConfig): LlmEndpoint {
            return forOpenRouter(config) ?: forOpenAI(config)
        }

        fun forOpenAI(config: AppConfig): LlmEndpoint {
            return LlmEndpoint(
                baseUrl = "https://api.openai.com/v1",
                apiKey = config.openai.apiKey,
                model = config.openai.model
            )
        }

        fun forOpenRouter(config: AppConfig): LlmEndpoint? {
            val or = config.openrouter ?: return null
            val headers = buildMap {
                or.httpReferer?.takeIf { it.isNotBlank() }?.let { put("HTTP-Referer", it) }
                or.xTitle?.takeIf { it.isNotBlank() }?.let { put("X-Title", it) }
            }
            return LlmEndpoint(
                baseUrl = or.baseUrl.trimEnd('/'),
                apiKey = or.apiKey,
                model = or.model,
                extraHeaders = headers
            )
        }

        fun forAnthropic(config: AppConfig): LlmEndpoint? {
            val a = config.anthropic ?: return null
            return LlmEndpoint(
                baseUrl = a.baseUrl.trimEnd('/'),
                apiKey = a.apiKey,
                model = a.model,
                provider = Provider.ANTHROPIC,
                extraHeaders = mapOf("anthropic-version" to a.anthropicVersion)
            )
        }

        /**
         * Local `claude -p` CLI provider. [baseUrl] is a constant sentinel (not a URL) so
         * the factory's `(baseUrl, model, batchCapable)` cache key stays stable; auth is
         * the CLI's own login so [apiKey] is empty. Returns null when no claudeCli: block.
         */
        fun forClaudeCli(config: AppConfig): LlmEndpoint? {
            config.claudeCli ?: return null
            return LlmEndpoint(
                baseUrl = "claude-cli",
                apiKey = "",
                model = config.claudeCli.model,
                provider = Provider.CLAUDE_CLI
            )
        }
    }
}
