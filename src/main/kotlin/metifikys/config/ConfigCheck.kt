package metifikys.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.dedup.PromptFinding
import metifikys.ai.dedup.PromptLoader
import metifikys.ai.dedup.PromptValidator
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Prod-vs-repo config comparison, for `java -jar RssNewsBot.jar --check-config config.yaml
 * [--against config.example.yaml]` (run by `deploy/restart.sh` before a restart) and for the
 * startup log. Prints key PATHS only, never values — the prod file holds secrets.
 */
object ConfigCheck {

    private val yaml = ObjectMapper(YAMLFactory())

    data class Result(val ok: Boolean, val lines: List<String>)

    /** Schema-form key paths present in the YAML file, plus keys the schema does not know. */
    fun scan(file: File): ConfigSchema.YamlScan {
        val tree: JsonNode = yaml.readTree(file) ?: yaml.createObjectNode()
        return ConfigSchema.APP.scan(tree)
    }

    /**
     * Full check of [configPath]: load + validation exactly as at startup, unknown keys, prompt
     * validation (prompt paths resolve against [baseDir], the service's working directory),
     * registered new options the file does not set, and with [againstPath] the key diff in both
     * directions. Not ok = the bot would refuse to start with this config.
     */
    fun run(configPath: String, againstPath: String? = null, baseDir: File = File(".").canonicalFile): Result {
        val out = mutableListOf<String>()
        var ok = true
        val file = File(configPath)

        val config = try {
            ConfigLoader.load(configPath).also { out += "ok    $configPath loads and validates" }
        } catch (e: Exception) {
            ok = false
            out += "ERROR $configPath does not load: ${rootMessage(e)}"
            null
        }

        val scan = try {
            scan(file)
        } catch (e: Exception) {
            null // the load error above already says why
        }
        scan?.unknown?.forEach {
            ok = false
            out += "ERROR unknown key: $it"
        }

        if (config != null) {
            val findings = PromptValidator(PromptLoader(baseDir), baseDir).validate(config)
            if (findings.isEmpty()) out += "ok    prompts validate"
            for (f in findings.sortedBy { it.level.ordinal }) {
                if (f.level == PromptFinding.Level.ERROR) ok = false
                val mark = when (f.level) {
                    PromptFinding.Level.ERROR -> "ERROR"
                    PromptFinding.Level.WARN -> "WARN "
                    PromptFinding.Level.INFO -> "INFO "
                }
                out += "$mark prompts: $f"
            }
        }

        scan?.let { s ->
            for (e in ConfigChangelog.missing(s.known)) out += "INFO  new option since ${e.since} not set: ${e.path} — ${e.hint}"
        }

        if (againstPath != null && scan != null) {
            try {
                val other = scan(File(againstPath))
                val onlyThere = (other.known - scan.known).sorted()
                val onlyHere = (scan.known - other.known).sorted()
                out += "--- keys in $againstPath but not in $configPath (${onlyThere.size}):"
                onlyThere.forEach { out += "  + $it" }
                out += "--- keys in $configPath but not in $againstPath (${onlyHere.size}):"
                onlyHere.forEach { out += "  - $it" }
                other.unknown.forEach { out += "WARN  $againstPath has an unknown key: $it" }
            } catch (e: Exception) {
                ok = false
                out += "ERROR $againstPath does not parse: ${rootMessage(e)}"
            }
        }
        out += if (ok) "OK" else "FAILED"
        return Result(ok, out)
    }

    /** Startup: one INFO line per registered option the config file does not set; WARN per unknown key. */
    fun logStartupReport(configPath: String) {
        val scan = try {
            scan(File(configPath))
        } catch (e: Exception) {
            logger.warn(e) { "[Config] could not scan $configPath for new options" }
            return
        }
        for (e in ConfigChangelog.missing(scan.known)) {
            logger.info { "[Config] new option since ${e.since} not set: ${e.path} — ${e.hint}" }
        }
        for (k in scan.unknown) logger.warn { "[Config] unknown key ignored: $k" }
    }

    /** CLI entry: `--check-config [config.yaml] [--against other.yaml]`; returns the exit code. */
    fun main(args: List<String>): Int {
        val rest = args.dropWhile { it == "--check-config" }
        val againstIdx = rest.indexOf("--against")
        val against = if (againstIdx >= 0) rest.getOrNull(againstIdx + 1) else null
        if (againstIdx >= 0 && against == null) {
            println("usage: --check-config [config.yaml] [--against other.yaml]")
            return 2
        }
        val positional = rest.filterIndexed { i, _ -> againstIdx < 0 || (i != againstIdx && i != againstIdx + 1) }
        val configPath = positional.firstOrNull() ?: "config.yaml"
        val result = run(configPath, against)
        result.lines.forEach(::println)
        return if (result.ok) 0 else 1
    }

    private fun rootMessage(e: Throwable): String =
        (e as? IllegalArgumentException)?.message
            ?: generateSequence(e) { it.cause }.last().message?.lineSequence()?.firstOrNull()
            ?: e.javaClass.simpleName
}
