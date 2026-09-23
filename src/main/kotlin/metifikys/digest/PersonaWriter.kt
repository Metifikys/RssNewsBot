package metifikys.digest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.LlmClient
import metifikys.config.CategoryConfig
import metifikys.config.PersonaConfig
import metifikys.db.DigestMessageRow
import metifikys.format.PersonaRemark
import metifikys.model.ShortlistItem
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * `post.persona`: the editor's one-line remark under the digest's top event, as an A/B test (see
 * [PersonaConfig]). One LLM call at most per digest — only for the persona arm; the control arm
 * costs nothing. Never throws: any failure is a post without the line and without a variant.
 */
class PersonaWriter(
    /** The persona client of a category (`LlmClientsFactory.forPersona`, metered as PERSONA). */
    private val clientFor: (CategoryConfig) -> LlmClient,
    /** Where a relative `promptFile` is resolved. */
    private val baseDir: File = File("."),
    /** Runs the call so a slow provider can be abandoned after `timeoutSeconds`. */
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "persona").also { it.isDaemon = true }
    }
) {

    /** The arm the post goes to ([DigestMessageRow.variant]) and its footer line; both null = not in the test. */
    data class Outcome(val variant: String?, val line: String?) {
        companion object {
            val NONE = Outcome(null, null)
        }
    }

    data class Prompts(val system: String, val user: String)

    fun write(category: String, categoryConfig: CategoryConfig, cfg: PersonaConfig, item: ShortlistItem, topic: String): Outcome {
        val label = "[Category:$category][Persona] ${item.eventKey}"
        // Without the owner's prompt neither arm is tagged — a control arm alone compares nothing.
        val prompts = loadPrompts(cfg.promptFile) ?: return Outcome.NONE
        if (!PersonaRemark.inPersonaArm(item.eventKey, cfg.share)) {
            logger.info { "$label: control arm" }
            return Outcome(DigestMessageRow.VARIANT_CONTROL, null)
        }
        val vars = mapOf(
            "CATEGORY" to category,
            "EMOJI" to categoryConfig.emoji,
            "TOPIC" to topic,
            "CORE_FACT" to item.coreFact,
            "SUBJECT" to item.subject,
            "FRANCHISE" to item.franchise,
            "EVENT_TYPE" to item.eventType,
            "MAX_CHARS" to cfg.maxChars.toString()
        )
        val raw = try {
            val client = clientFor(categoryConfig)
            val future = executor.submit<String> { client.complete(substitute(prompts.system, vars), substitute(prompts.user, vars)) }
            try {
                future.get(cfg.timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                logger.warn { "$label: no remark within ${cfg.timeoutSeconds}s — post without it" }
                return Outcome.NONE
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.NONE
        } catch (e: Exception) {
            logger.warn(e) { "$label: remark call failed — post without it" }
            return Outcome.NONE
        }
        val checked = PersonaRemark.check(raw.orEmpty(), topic, cfg)
        if (checked.text == null) {
            logger.info { "$label: remark rejected (${checked.reason}): ${raw.orEmpty().take(200)}" }
            return Outcome.NONE
        }
        logger.info { "$label: persona arm — ${checked.text}" }
        return Outcome(DigestMessageRow.VARIANT_PERSONA, PersonaRemark.line(checked.text))
    }

    /** `persona.system` / `persona.user` from [rawPath]; null (logged) when missing or incomplete. */
    internal fun loadPrompts(rawPath: String): Prompts? {
        if (rawPath.isBlank() || rawPath.contains("..")) {
            logger.warn { "[Persona] promptFile '$rawPath' is not usable — no remark, no variant" }
            return null
        }
        val file = if (File(rawPath).isAbsolute) File(rawPath) else File(baseDir, rawPath)
        return try {
            val root = yamlMapper.readTree(file)
            val system = root?.at("/persona/system")?.asText(null)?.takeIf { it.isNotBlank() }
            val user = root?.at("/persona/user")?.asText(null)?.takeIf { it.isNotBlank() }
            if (system == null || user == null) {
                logger.warn { "[Persona] ${file.absolutePath} lacks persona.system / persona.user — no remark, no variant" }
                null
            } else Prompts(system, user)
        } catch (e: Exception) {
            logger.warn { "[Persona] prompt file ${file.absolutePath} unreadable (${e.message?.lineSequence()?.firstOrNull()}) — no remark, no variant" }
            null
        }
    }

    private fun substitute(template: String, vars: Map<String, String>): String =
        vars.entries.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }

    companion object {
        private val yamlMapper = ObjectMapper(YAMLFactory())
    }
}
