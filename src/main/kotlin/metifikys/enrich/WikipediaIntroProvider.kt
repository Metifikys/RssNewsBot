package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.config.IntroConfig
import metifikys.db.EntityIntroRow
import metifikys.db.NewsDatabase
import metifikys.format.Hashtags
import metifikys.model.ShortlistItem
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/** First-mention memory behind [WikipediaIntroProvider]; the DB in production, a map in tests. */
interface IntroStore {
    fun get(category: String, entityKey: String): EntityIntroRow?
    fun put(row: EntityIntroRow)
}

class DbIntroStore(private val db: NewsDatabase) : IntroStore {
    override fun get(category: String, entityKey: String): EntityIntroRow? = db.getEntityIntro(category, entityKey)
    override fun put(row: EntityIntroRow) = db.putEntityIntro(row)
}

class MemoryIntroStore : IntroStore {
    val rows = HashMap<String, EntityIntroRow>()
    override fun get(category: String, entityKey: String): EntityIntroRow? = synchronized(rows) { rows["$category $entityKey"] }
    override fun put(row: EntityIntroRow) = synchronized(rows) { rows["${row.category} ${row.entityKey}"] = row }
}

/**
 * `📚 Team Cherry: австралійська студія відеоігор · [Вікіпедія](url)`
 *
 * The first time a franchise (or, failing that, a subject) shows up in a category, the bullet
 * gets one line from Wikipedia: the page's short description when it has one, else the first
 * sentence of the summary. Grounded in an encyclopedia, not in the LLM. Languages are tried in
 * the configured order and the extract is printed as found (`languages: [uk]` by default — an
 * English sentence in a Ukrainian channel is the operator's call).
 *
 * The (category, entity) is recorded in [IntroStore] whether or not an article was found, so a
 * name is introduced once per [IntroConfig.repeatAfterDays] and an unknown name is searched
 * again only after [MISS_RETRY_DAYS]. Transport failures record nothing (retried next digest).
 */
class WikipediaIntroProvider(
    private val http: BadgeHttp,
    private val store: IntroStore,
    private val baseUrlFor: (lang: String) -> String = { lang -> "https://$lang.wikipedia.org" }
) {
    companion object {
        /** How long a "nothing found" record suppresses a new search. */
        val MISS_RETRY_DAYS: Long = 30
        const val MAX_CHARS = 200
        private val SENTENCE_END = Regex("""(?<=[.!?…])\s+""")

        /** The entity a bullet introduces: the franchise, else the subject; null when neither is usable. */
        internal fun entityName(item: ShortlistItem): String? =
            listOf(item.franchise, item.subject).map { it.trim() }.firstOrNull { it.length >= 2 && it.any(Char::isLetter) }
    }

    /**
     * The intro line for [item] in [category], or null when the entity is known, stop-listed,
     * recently looked up without result, or not on Wikipedia.
     */
    fun intro(category: String, item: ShortlistItem, config: IntroConfig, now: LocalDateTime = LocalDateTime.now()): String? {
        val name = entityName(item) ?: return null
        val key = Hashtags.entityKey(name) ?: return null
        val known = store.get(category, key)
        if (known != null) {
            val age = Duration.between(known.introducedAt, now).toDays()
            val suppressedFor = if (known.extract != null) config.repeatAfterDays else MISS_RETRY_DAYS
            if (age < suppressedFor) return null
        }
        val found = try {
            config.languages.firstNotNullOfOrNull { lang -> lookup(name, key, lang) }
        } catch (e: Exception) {
            logger.warn { "[Intro] Wikipedia lookup failed for '$name': ${e.message}" }
            return null
        }
        store.put(
            EntityIntroRow(
                category = category, entityKey = key,
                title = found?.title, extract = found?.line, url = found?.url, lang = found?.lang,
                introducedAt = now
            )
        )
        if (found == null) {
            logger.debug { "[Intro] no Wikipedia article for '$name' ($category)" }
            return null
        }
        logger.info { "[Category:$category][Intro] first mention of '$name' → ${found.lang}:${found.title}" }
        return "📚 ${found.line} · [Вікіпедія](${found.url})"
    }

    private data class Found(val title: String, val line: String, val url: String, val lang: String)

    /** Null = no matching, non-disambiguation article in this language (cacheable). Throws on transport failure. */
    private fun lookup(name: String, key: String, lang: String): Found? {
        val base = baseUrlFor(lang)
        val q = URLEncoder.encode(name, Charsets.UTF_8)
        val search = http.getJson("$base/w/api.php?action=query&list=search&srsearch=$q&srlimit=3&srprop=&format=json")
            ?.jsonObject?.get("query")?.jsonObject?.get("search")?.jsonArray ?: return null
        for (hit in search) {
            val title = hit.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!TitleMatch.similar(Hashtags.entityKey(title), key)) continue
            val summary = http.getJson("$base/api/rest_v1/page/summary/${encodeTitle(title)}")?.jsonObject ?: continue
            if (summary["type"]?.jsonPrimitive?.contentOrNull == "disambiguation") continue
            val pageTitle = summary["title"]?.jsonPrimitive?.contentOrNull ?: title
            val description = summary["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val extract = summary["extract"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val url = summary["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.get("page")?.jsonPrimitive?.contentOrNull
                ?: "$base/wiki/${encodeTitle(title)}"
            val line = when {
                description.isNotEmpty() -> "$pageTitle: ${description.replaceFirstChar { it.lowercase() }}"
                extract.isNotEmpty() -> firstSentence(extract)
                else -> continue
            }
            return Found(pageTitle, clip(line), url, lang)
        }
        return null
    }

    private fun firstSentence(extract: String): String = SENTENCE_END.split(extract.trim(), limit = 2).first().trim()

    private fun clip(s: String): String {
        if (s.length <= MAX_CHARS) return s
        val cut = s.substring(0, MAX_CHARS)
        val boundary = cut.lastIndexOf(' ')
        return (if (boundary > MAX_CHARS / 2) cut.substring(0, boundary) else cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    private fun encodeTitle(title: String): String =
        URLEncoder.encode(title.replace(' ', '_'), Charsets.UTF_8).replace("+", "%20")
}

/** Shared "is this the same title" test on normalized entity keys (see [Hashtags.entityKey]). */
object TitleMatch {
    fun similar(candidate: String?, wanted: String, minJaccard: Double = 0.75): Boolean {
        if (candidate == null) return false
        if (candidate == wanted) return true
        val a = candidate.split('_').filter { it.isNotEmpty() }.toSet()
        val b = wanted.split('_').filter { it.isNotEmpty() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        return (a intersect b).size.toDouble() / (a union b).size >= minJaccard
    }
}
