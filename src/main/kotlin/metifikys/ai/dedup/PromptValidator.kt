package metifikys.ai.dedup

import metifikys.ai.PromptBuilder
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.digest.WeeklyDigest
import java.io.File

/** One prompt problem. [slot] is `promptFile`, `extract`, `extract.user`, `render.system`, … */
data class PromptFinding(val level: Level, val category: String, val slot: String, val message: String) {
    enum class Level { ERROR, WARN, INFO }

    override fun toString(): String = "$level $category/$slot: $message"
}

/**
 * Checks the prompts the enabled config will actually use, before they reach an LLM.
 *
 * Prompt files are copied to prod by hand, and until now a file that did not resolve quietly
 * switched its category to the legacy single-step format (the " --- " incident, 146ea63), while
 * a prompt missing a feature's block (`depth`, `eventDate`) left that feature silently inert.
 * ERROR = the pipeline cannot work as configured (startup refuses to run); WARN = something
 * configured will not take effect or text goes to the LLM unsubstituted; INFO = a hint.
 *
 * Markers (`depth`, `eventDate`) are plain substring checks, so a comment inside the prompt
 * text mentioning them counts as present.
 */
class PromptValidator(
    private val loader: PromptLoader,
    private val baseDir: File = File(".").canonicalFile
) {

    fun validate(config: AppConfig): List<PromptFinding> =
        config.categories.flatMap { (name, cat) -> validateCategory(name, cat, config) } + validateWeekly(config)

    /** Categories whose `dedup.promptFile` is [file] (canonical) — the ones a hot reload affects. */
    fun categoriesUsing(file: File, config: AppConfig): List<String> =
        config.categories.filter { (_, c) -> c.dedup?.promptFile?.let { loader.fileFor(it) } == file }.keys.toList()

    fun validateCategory(name: String, cat: CategoryConfig, config: AppConfig): List<PromptFinding> {
        val p = loader.inspect(cat) ?: return emptyList()
        val out = mutableListOf<PromptFinding>()
        fun add(level: PromptFinding.Level, slot: String, msg: String) = out.add(PromptFinding(level, name, slot, msg))

        p.fileProblem?.let { add(PromptFinding.Level.ERROR, "promptFile", "${p.promptFile}: $it") }
        val unresolved = listOf(
            "extract.system" to p.extractSystem, "extract.user" to p.extractUser,
            "render.system" to p.renderSystem, "render.user" to p.renderUser
        ).filter { it.second == null }.map { it.first }
        if (unresolved.isNotEmpty()) {
            add(
                PromptFinding.Level.ERROR, "prompts",
                "not resolved: ${unresolved.joinToString()} — the category would fall back to the legacy single-step flow"
            )
        }

        val extract = listOfNotNull(p.extractSystem, p.extractUser).joinToString("\n")
        if (p.extractSystem != null && p.extractUser != null) {
            if ("{{CURRENT_BATCH_JSON}}" !in extract) {
                add(PromptFinding.Level.ERROR, "extract", "no {{CURRENT_BATCH_JSON}} — Step 1 would never see the articles")
            }
            if ("{{PREVIOUSLY_COVERED_EVENTS_JSON}}" !in extract) {
                add(
                    PromptFinding.Level.WARN, "extract",
                    "no {{PREVIOUSLY_COVERED_EVENTS_JSON}} — Step 1 cannot see what was already covered"
                )
            }
        }
        p.renderUser?.let { ru ->
            if ("{{SHORTLIST_JSON}}" !in ru) {
                add(PromptFinding.Level.ERROR, "render.user", "no {{SHORTLIST_JSON}} — Step 2 would never see the shortlist")
            }
        }

        p.extractSystem?.let { unknownIn(it, EventExtractor.PLACEHOLDERS) }?.let { add(PromptFinding.Level.WARN, "extract.system", it) }
        p.extractUser?.let { unknownIn(it, EventExtractor.PLACEHOLDERS) }?.let { add(PromptFinding.Level.WARN, "extract.user", it) }
        p.renderUser?.let { unknownIn(it, PromptBuilder.RENDER_PLACEHOLDERS) }?.let { add(PromptFinding.Level.WARN, "render.user", it) }
        p.renderSystem?.let { placeholders(it) }?.takeIf { it.isNotEmpty() }?.let {
            add(PromptFinding.Level.WARN, "render.system", "${it.joinToString()} — render.system is sent verbatim, never substituted")
        }

        val hasSignals = "{{AUDIENCE_SIGNALS}}" in extract
        if (hasSignals && !config.feedback.enabled) {
            add(PromptFinding.Level.WARN, "extract", "{{AUDIENCE_SIGNALS}} is always empty while feedback.enabled=false")
        }
        val reactionWeight = cat.dedup?.digest?.ranker?.reactionWeight ?: 0.0
        if (!hasSignals && p.extractSystem != null && p.extractUser != null && config.feedback.enabled && reactionWeight > 0.0) {
            add(
                PromptFinding.Level.INFO, "extract",
                "no {{AUDIENCE_SIGNALS}} — reactions reach this category through the ranker only, not the Step 1 prompt"
            )
        }

        val render = listOfNotNull(p.renderSystem, p.renderUser).joinToString("\n")
        if (cat.post?.longForm?.enabled == true && render.isNotEmpty() && "depth" !in render && "LONG-FORM" !in render) {
            add(
                PromptFinding.Level.WARN, "render",
                "post.longForm is on but the render prompt never mentions \"depth\" — long items would come out short"
            )
        }
        if (cat.trackers.isNotEmpty() && extract.isNotEmpty() && "eventDate" !in extract) {
            add(
                PromptFinding.Level.WARN, "extract",
                "trackers are configured but the extract prompt never asks for eventDate — every tracker row stays undated"
            )
        }
        out += validateShadow(name, cat, config)
        return out
    }

    /**
     * `llm.shadow.promptFile` gets the same checks as `dedup.promptFile`, reported as `shadow:<slot>`
     * and at most WARN: a broken shadow prompt only means no shadow run, the live pipeline is fine.
     */
    private fun validateShadow(name: String, cat: CategoryConfig, config: AppConfig): List<PromptFinding> {
        val file = cat.llm?.shadow?.promptFile ?: return emptyList()
        val dedup = cat.dedup ?: return emptyList()
        val shadowCat = cat.copy(dedup = dedup.copy(promptFile = file, prompts = null), llm = cat.llm.copy(shadow = null))
        return validateCategory(name, shadowCat, config).map {
            val level = if (it.level == PromptFinding.Level.ERROR) PromptFinding.Level.WARN else it.level
            val message = if (it.level == PromptFinding.Level.ERROR) "${it.message} — no shadow run" else it.message
            it.copy(level = level, slot = "shadow:${it.slot}", message = message)
        }
    }

    fun validateWeekly(config: AppConfig): List<PromptFinding> {
        val weekly = config.weekly?.takeIf { it.enabled } ?: return emptyList()
        val out = mutableListOf<PromptFinding>()
        val w = WeeklyDigest.resolvePrompts(weekly, baseDir)
        w.fileProblem?.let {
            out += PromptFinding(PromptFinding.Level.WARN, "weekly", "promptFile", "${weekly.promptFile}: $it — built-in prompts used")
        }
        if ("{{CANDIDATES_JSON}}" !in w.system + "\n" + w.user) {
            out += PromptFinding(PromptFinding.Level.ERROR, "weekly", "prompts", "no {{CANDIDATES_JSON}} — the roundup LLM would never see the stories")
        }
        unknownIn(w.system, WeeklyDigest.PLACEHOLDERS)?.let { out += PromptFinding(PromptFinding.Level.WARN, "weekly", "system", it) }
        unknownIn(w.user, WeeklyDigest.PLACEHOLDERS)?.let { out += PromptFinding(PromptFinding.Level.WARN, "weekly", "user", it) }
        return out
    }

    private fun unknownIn(text: String, known: Set<String>): String? {
        val unknown = PLACEHOLDER.findAll(text)
            .filter { it.groupValues[1] !in known || it.value != "{{${it.groupValues[1]}}}" }.map { it.value }.distinct().toList()
        return if (unknown.isEmpty()) null else "unknown placeholder(s) ${unknown.joinToString()} — sent to the LLM literally"
    }

    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.distinct().toList()

    companion object {
        /** `{{NAME}}`; the whitespace variant is matched too, since it is never substituted. */
        private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*}}""")

        /** Multi-line report of [findings], most severe first. */
        fun format(findings: List<PromptFinding>): String =
            findings.sortedBy { it.level.ordinal }.joinToString("\n") { "  - $it" }
    }
}
