package metifikys.fetch

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.net.URI
import java.util.regex.PatternSyntaxException

private val logger = KotlinLogging.logger {}

/**
 * One site-chrome scrub rule: [regex] is removed from extracted article text, optionally only
 * for articles whose URL host is [host] or a subdomain of it.
 */
data class ScrubRule(val name: String, val regex: Regex, val host: String? = null) {
    fun appliesTo(articleHost: String?): Boolean {
        val h = host ?: return true
        val a = articleHost ?: return true
        return a == h || a.endsWith(".$h")
    }
}

/**
 * The set of site-chrome patterns scrubbed from extracted article text before the length cut.
 * Data, not code: the list is an operator-maintained YAML file (`fetcher.scrubRulesFile`, see
 * `scrub-rules.example.yaml`), so a host's login nudge or share bar is a YAML edit and a
 * restart, not a release. Without a file nothing is scrubbed.
 */
class TextScrubRules(val rules: List<ScrubRule>) {

    /** Removes every applicable rule's matches from [text] and collapses the whitespace left behind. */
    fun clean(text: String, url: String? = null): String {
        val host = url?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
        var out = text
        for (rule in rules) {
            if (rule.appliesTo(host)) out = rule.regex.replace(out, " ")
        }
        return out.replace(WHITESPACE_RUNS, " ").trim()
    }

    companion object {
        private val WHITESPACE_RUNS = Regex("""\s{2,}""")
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        val EMPTY = TextScrubRules(emptyList())

        /** [path] null → [EMPTY] (nothing scrubbed); otherwise the file's rules. */
        fun load(path: String?): TextScrubRules {
            if (path == null) {
                logger.info { "[Scrub] fetcher.scrubRulesFile not set — extracted article text is stored as is" }
                return EMPTY
            }
            val rules = parse(File(path).readText(Charsets.UTF_8), path)
            logger.info { "[Scrub] loaded ${rules.rules.size} site-chrome rule(s) from $path" }
            return rules
        }

        fun parse(yaml: String, source: String): TextScrubRules {
            val file = try {
                mapper.readValue<RulesFile>(yaml)
            } catch (e: Exception) {
                throw IllegalArgumentException("Cannot parse scrub rules from $source: ${e.message}", e)
            }
            val seen = HashSet<String>()
            val rules = file.rules.map { r ->
                require(r.name.isNotBlank()) { "Scrub rule without a name in $source" }
                require(seen.add(r.name)) { "Duplicate scrub rule name '${r.name}' in $source" }
                require(r.regex.isNotBlank()) { "Scrub rule '${r.name}' in $source has an empty regex" }
                val options = if (r.dotAll) setOf(RegexOption.DOT_MATCHES_ALL) else emptySet()
                val regex = try {
                    Regex(r.regex, options)
                } catch (e: PatternSyntaxException) {
                    throw IllegalArgumentException("Scrub rule '${r.name}' in $source has an invalid regex: ${e.description}", e)
                }
                ScrubRule(name = r.name, regex = regex, host = r.host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() })
            }
            return TextScrubRules(rules)
        }
    }

    /** YAML shape: `rules: [{ name, regex, host?, dotAll? }]`. */
    internal data class RuleYaml(
        val name: String = "",
        val regex: String = "",
        val host: String? = null,
        val dotAll: Boolean = false
    )

    internal data class RulesFile(val rules: List<RuleYaml> = emptyList())
}
