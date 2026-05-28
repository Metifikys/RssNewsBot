package metifikys.ai.dedup

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.CategoryConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Fully-resolved dedup prompt set for a category. All four prompt strings are guaranteed
 * non-blank when an instance exists; otherwise [PromptLoader.resolve] returns null.
 */
data class ResolvedDedupPrompts(
    val extractSystem: String,
    val extractUser: String,
    val renderSystem: String,
    val renderUser: String,
    val contextDays: Long,
    val maxContextEvents: Int
)

/**
 * Loads per-category dedup prompts from YAML files and/or inline config overrides.
 *
 * Resolution order for each of the four required prompt strings
 * (`extractSystem`, `extractUser`, `renderSystem`, `renderUser`):
 *   1. The matching field of [CategoryConfig.dedup].prompts (if non-null & non-blank).
 *   2. The corresponding `extract.system` / `extract.user` / `render.system` / `render.user`
 *      key from the YAML file at [CategoryConfig.dedup].promptFile.
 *   3. Otherwise unresolved → [resolve] returns null and the caller falls back to the
 *      legacy single-step flow for that category.
 *
 * Files are cached by (absolutePath, lastModified). Paths containing `..` are rejected.
 */
class PromptLoader(
    private val baseDir: File = File(".").canonicalFile
) {

    private data class YamlDoc(
        val extractSystem: String?,
        val extractUser: String?,
        val renderSystem: String?,
        val renderUser: String?
    )

    private data class CacheEntry(val lastModified: Long, val doc: YamlDoc)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun resolve(cat: CategoryConfig): ResolvedDedupPrompts? {
        val dedup = cat.dedup ?: return null

        val fileDoc: YamlDoc? = dedup.promptFile?.let { loadFile(it) }
        val inline = dedup.prompts

        val extractSystem = inline?.extractSystem?.nonBlank() ?: fileDoc?.extractSystem?.nonBlank()
        val extractUser = inline?.extractUser?.nonBlank() ?: fileDoc?.extractUser?.nonBlank()
        val renderSystem = inline?.renderSystem?.nonBlank() ?: fileDoc?.renderSystem?.nonBlank()
        val renderUser = inline?.renderUser?.nonBlank() ?: fileDoc?.renderUser?.nonBlank()

        if (extractSystem == null || extractUser == null || renderSystem == null || renderUser == null) {
            logger.warn {
                "[Dedup] Prompt resolution failed for category — missing field(s): " +
                    "extractSystem=${extractSystem != null}, extractUser=${extractUser != null}, " +
                    "renderSystem=${renderSystem != null}, renderUser=${renderUser != null}. " +
                    "Falling back to legacy flow."
            }
            return null
        }

        return ResolvedDedupPrompts(
            extractSystem = extractSystem,
            extractUser = extractUser,
            renderSystem = renderSystem,
            renderUser = renderUser,
            contextDays = dedup.contextDays,
            maxContextEvents = dedup.maxContextEvents
        )
    }

    /** Replaces `{{KEY}}` placeholders in [template] with the values from [vars]. */
    fun substitute(template: String, vars: Map<String, String>): String {
        var result = template
        for ((k, v) in vars) {
            result = result.replace("{{$k}}", v)
        }
        return result
    }

    private fun loadFile(rawPath: String): YamlDoc? {
        if (rawPath.contains("..")) {
            logger.warn { "[Dedup] Rejecting prompt file path with '..': $rawPath" }
            return null
        }
        val file = if (File(rawPath).isAbsolute) File(rawPath) else File(baseDir, rawPath)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            logger.warn { "[Dedup] Prompt file missing or unreadable: ${file.absolutePath}" }
            return null
        }
        val key = file.canonicalPath
        val lastModified = file.lastModified()
        cache[key]?.let { if (it.lastModified == lastModified) return it.doc }

        return try {
            val root = mapper.readTree(file) ?: return null
            val doc = YamlDoc(
                extractSystem = root.at("/extract/system").asText(null)?.takeIf { !it.isNullOrEmpty() },
                extractUser = root.at("/extract/user").asText(null)?.takeIf { !it.isNullOrEmpty() },
                renderSystem = root.at("/render/system").asText(null)?.takeIf { !it.isNullOrEmpty() },
                renderUser = root.at("/render/user").asText(null)?.takeIf { !it.isNullOrEmpty() }
            )
            cache[key] = CacheEntry(lastModified, doc)
            doc
        } catch (e: Exception) {
            logger.warn(e) { "[Dedup] Failed to parse prompt file ${file.absolutePath}" }
            null
        }
    }

    private fun String.nonBlank(): String? = takeIf { it.isNotBlank() }
}
