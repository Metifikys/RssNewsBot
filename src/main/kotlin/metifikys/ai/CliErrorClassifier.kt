package metifikys.ai

/** What a failed `claude` / `codex` run means for the operator. */
enum class CliFailure {
    /** Expired or missing login: recurs until someone re-authenticates on the host. */
    AUTH,

    /** Subscription usage / credit limit: clears by itself when the window resets. */
    LIMIT,

    /** The configured model is unknown or not accessible. */
    MODEL,

    /** Anything else (transient API errors, crashes): retryable. */
    OTHER
}

/**
 * Classifies a CLI's combined stdout/stderr after a non-zero exit. Shared by [ClaudeCli],
 * [CodexCli] (which map it to [BillingException] / [NonRetryableCliException] / `IOException`)
 * and the login probe of [CliHealthMonitor]. The two CLIs word their errors differently, so each
 * keeps its own phrase set; order matters — a usage-limit message often mentions the login too.
 *
 * For codex the caller must strip the prompt echo first (see `CodexCli.stripPromptEcho`): article
 * text containing "login … run" otherwise reads as an auth failure (regression 146ea63).
 */
object CliErrorClassifier {

    fun classifyClaude(output: String): CliFailure {
        val s = output.lowercase()
        return when {
            claudeLimit(s) -> CliFailure.LIMIT
            claudeAuth(s) -> CliFailure.AUTH
            claudeModel(s) -> CliFailure.MODEL
            else -> CliFailure.OTHER
        }
    }

    fun classifyCodex(output: String): CliFailure {
        val s = output.lowercase()
        return when {
            codexLimit(s) -> CliFailure.LIMIT
            codexAuth(s) -> CliFailure.AUTH
            codexModel(s) -> CliFailure.MODEL
            else -> CliFailure.OTHER
        }
    }

    /** The "resets …" part of a usage-limit message (`resets 3am`, `resets at 14:00 (Europe/Kyiv)`), if any. */
    fun resetHint(output: String): String? =
        Regex("""(?i)(try again|resets?)\b[^\n.;|]{0,60}""").find(output)?.value?.trim()

    private fun claudeLimit(s: String) =
        "usage limit" in s || "quota" in s || "credit" in s ||
            "out of credits" in s || ("rate limit" in s && "exceeded" in s) ||
            // subscription-limit phrasing `claude -p` prints to stdout
            ("hit your" in s && "limit" in s) || "weekly limit" in s || "session limit" in s ||
            "5-hour" in s || "5 hour" in s || ("resets" in s && "limit" in s)

    private fun claudeAuth(s: String) =
        "/login" in s || "not logged in" in s ||
            "oauth token has expired" in s || "token has expired" in s || "login expired" in s ||
            "invalid api key" in s || "unauthorized" in s || "authentication_failed" in s ||
            ("authentication" in s && "error" in s)

    private fun claudeModel(s: String) =
        "issue with the selected model" in s ||
            "may not exist or you may not have access" in s ||
            "model_not_found" in s || "unknown model" in s

    private fun codexLimit(s: String) =
        "usage limit" in s || "quota" in s || "credit" in s ||
            "insufficient_quota" in s || "out of credits" in s ||
            ("rate limit" in s && "exceeded" in s) ||
            ("hit your" in s && "limit" in s) || "weekly limit" in s || "session limit" in s

    private fun codexAuth(s: String) =
        "not logged in" in s || "unauthorized" in s || "invalid api key" in s ||
            "token has expired" in s || "login expired" in s ||
            ("login" in s && ("run" in s || "please" in s || "expired" in s)) ||
            ("authentication" in s && ("fail" in s || "error" in s))

    private fun codexModel(s: String) =
        "model_not_found" in s || "unknown model" in s ||
            ("model" in s && ("not exist" in s || "not found" in s || "no access" in s || "may not have access" in s))
}
