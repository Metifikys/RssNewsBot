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
 * Slot-by-slot view of how a category's dedup prompts resolve, for [PromptValidator]: each
 * field is the value [PromptLoader.resolve] would use (null = unresolved); [fileProblem] says
 * why `dedup.promptFile` did not load (null when there is no file or it loaded).
 */
data class PromptInspection(
    val promptFile: String?,
    val fileProblem: String?,
    val extractSystem: String?,
    val extractUser: String?,
    val renderSystem: String?,
    val renderUser: String?
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
 * A file edited in place is picked up on its next use (hot reload); [onReload] then gets the
 * reloaded file so the caller can re-validate it.
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

    /** A file load: the parsed doc, or why there is none. */
    private data class FileLoad(val doc: YamlDoc?, val problem: String?)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /**
     * Called (on the thread that triggered the load) after a cached prompt file was re-read
     * because it changed on disk — not on its first load. Exceptions are logged and swallowed.
     */
    @Volatile
    var onReload: ((File) -> Unit)? = null

    fun resolve(cat: CategoryConfig): ResolvedDedupPrompts? {
        val dedup = cat.dedup ?: return null
        val p = inspect(cat)!!
        val extractSystem = p.extractSystem
        val extractUser = p.extractUser
        val renderSystem = p.renderSystem
        val renderUser = p.renderUser

        if (extractSystem == null || extractUser == null || renderSystem == null || renderUser == null) {
            // ERROR, not WARN: this once turned a category into the legacy single-step format
            // for days without anyone noticing (146ea63). Startup validation (PromptValidator)
            // refuses such a config, so getting here means the file broke while running.
            logger.error {
                "[Dedup] Prompt resolution failed (promptFile=${dedup.promptFile}) — missing field(s): " +
                    "extractSystem=${extractSystem != null}, extractUser=${extractUser != null}, " +
                    "renderSystem=${renderSystem != null}, renderUser=${renderUser != null}" +
                    (p.fileProblem?.let { "; file: $it" } ?: "") + ". Falling back to legacy flow."
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

    /** How each prompt slot of [cat] resolves; null when the category has no `dedup:` block. */
    fun inspect(cat: CategoryConfig): PromptInspection? {
        val dedup = cat.dedup ?: return null
        val load = dedup.promptFile?.let { loadFile(it) }
        val fileDoc = load?.doc
        val inline = dedup.prompts
        return PromptInspection(
            promptFile = dedup.promptFile,
            fileProblem = load?.problem,
            extractSystem = inline?.extractSystem?.nonBlank() ?: fileDoc?.extractSystem?.nonBlank(),
            extractUser = inline?.extractUser?.nonBlank() ?: fileDoc?.extractUser?.nonBlank(),
            renderSystem = inline?.renderSystem?.nonBlank() ?: fileDoc?.renderSystem?.nonBlank(),
            renderUser = inline?.renderUser?.nonBlank() ?: fileDoc?.renderUser?.nonBlank()
        )
    }

    /** The canonical file [rawPath] points at (as [resolve] reads it), or null for a `..` path. */
    fun fileFor(rawPath: String): File? {
        if (rawPath.contains("..")) return null
        val file = if (File(rawPath).isAbsolute) File(rawPath) else File(baseDir, rawPath)
        return runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
    }

    /** Replaces `{{KEY}}` placeholders in [template] with the values from [vars]. */
    fun substitute(template: String, vars: Map<String, String>): String {
        var result = template
        for ((k, v) in vars) {
            result = result.replace("{{$k}}", v)
        }
        return result
    }

    private fun loadFile(rawPath: String): FileLoad {
        if (rawPath.contains("..")) {
            logger.warn { "[Dedup] Rejecting prompt file path with '..': $rawPath" }
            return FileLoad(null, "path contains '..'")
        }
        val file = if (File(rawPath).isAbsolute) File(rawPath) else File(baseDir, rawPath)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            logger.warn { "[Dedup] Prompt file missing or unreadable: ${file.absolutePath}" }
            return FileLoad(null, "missing or unreadable: ${file.absolutePath}")
        }
        val key = file.canonicalPath
        val lastModified = file.lastModified()
        val previous = cache[key]
        if (previous != null && previous.lastModified == lastModified) return FileLoad(previous.doc, null)

        val doc = try {
            val root = mapper.readTree(file) ?: return FileLoad(null, "empty file: ${file.absolutePath}")
            YamlDoc(
                extractSystem = root.at("/extract/system").asText(null)?.takeIf { !it.isNullOrEmpty() },
                extractUser = root.at("/extract/user").asText(null)?.takeIf { !it.isNullOrEmpty() },
                renderSystem = root.at("/render/system").asText(null)?.takeIf { !it.isNullOrEmpty() },
                renderUser = root.at("/render/user").asText(null)?.takeIf { !it.isNullOrEmpty() }
            )
        } catch (e: Exception) {
            logger.warn(e) { "[Dedup] Failed to parse prompt file ${file.absolutePath}" }
            val reason = e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName
            return FileLoad(null, "YAML parse error in ${file.absolutePath}: $reason")
        }
        cache[key] = CacheEntry(lastModified, doc)
        if (previous != null) {
            logger.info { "[Dedup] Prompt file changed on disk, reloaded: ${file.absolutePath}" }
            try {
                onReload?.invoke(File(key))
            } catch (e: Exception) {
                logger.warn(e) { "[Dedup] Prompt reload hook failed for ${file.absolutePath}" }
            }
        }
        return FileLoad(doc, null)
    }

    private fun String.nonBlank(): String? = takeIf { it.isNotBlank() }
}
