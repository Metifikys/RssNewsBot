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
        private const val MIN_SENTENCE_CHARS = 40
        private val SENTENCE_BOUNDARY = Regex("""[.!?…]\s+""")
        /** `Diablo IV / Blizzard Entertainment`, `Minecraft, Roblox / YouTube`, `A | B`, `A; B`. */
        private val SEGMENT_SPLIT = Regex("""\s*(?:/|\||;|,)\s*""")
        private val TRAILING_PAREN = Regex("""\s*\([^()]*\)\s*$""")
        /** Segments that name a kind of thing, not a thing — never worth an encyclopedia line. */
        private val GENERIC = setOf(
            "indie", "indies", "indie game", "community", "industry", "various", "multiple", "misc", "other",
            "інді", "спільнота", "індустрія", "різне", "інше"
        )

        /**
         * The entities a bullet may introduce, most specific first. Step 1 writes the franchise as
         * `Game / Studio` (or `Game (indie)`), so the field is split into segments — the game, then
         * the studio — each without a trailing parenthetical; the subject is the fallback when the
         * franchise is blank. Generic words ("indie", "community") and one-letter tokens are dropped.
         */
        internal fun entityCandidates(item: ShortlistItem): List<String> {
            val source = item.franchise.trim().ifEmpty { item.subject.trim() }
            return source.split(SEGMENT_SPLIT)
                .map { TRAILING_PAREN.replace(it, "").trim() }
                .filter { it.length >= 2 && it.any(Char::isLetter) && it.lowercase() !in GENERIC }
                .distinctBy { it.lowercase() }
        }

        /** Kept for callers/tests: the primary entity, i.e. the first candidate. */
        internal fun entityName(item: ShortlistItem): String? = entityCandidates(item).firstOrNull()

        /** `Super Smash Bros. (серія відеоігор)` → key of `Super Smash Bros.`; Wikipedia's disambiguators are not part of the name. */
        internal fun titleKey(title: String): String? = Hashtags.entityKey(TRAILING_PAREN.replace(title, ""))
    }

    /**
     * The intro line for [item] in [category], or null when the entity is already introduced,
     * stop-listed, recently looked up without result, or not on Wikipedia. Candidates are tried
     * most-specific first (the game before its studio): an already-introduced candidate ends the
     * search — the bullet's entity is known — while a candidate without an article falls through
     * to the next one. Every looked-up candidate is remembered under its own key.
     */
    fun intro(category: String, item: ShortlistItem, config: IntroConfig, now: LocalDateTime = LocalDateTime.now()): String? {
        for (name in entityCandidates(item)) {
            val key = Hashtags.entityKey(name) ?: continue
            val known = store.get(category, key)
            if (known != null) {
                val age = Duration.between(known.introducedAt, now).toDays()
                if (known.extract != null) {
                    if (age < config.repeatAfterDays) return null   // introduced already — nothing to add
                } else if (age < MISS_RETRY_DAYS) {
                    continue                                           // known miss — try the next candidate
                }
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
                continue
            }
            logger.info { "[Category:$category][Intro] first mention of '$name' → ${found.lang}:${found.title}" }
            return "📚 ${found.line} · [Вікіпедія](${found.url})"
        }
        return null
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
            if (!TitleMatch.similar(titleKey(title), key)) continue
            val summary = http.getJson("$base/api/rest_v1/page/summary/${encodeTitle(title)}")?.jsonObject ?: continue
            if (summary["type"]?.jsonPrimitive?.contentOrNull == "disambiguation") continue
            val pageTitle = summary["title"]?.jsonPrimitive?.contentOrNull ?: title
            // The summary follows redirects; a page whose own title no longer matches the entity
            // ("Super Smash Bros." → the 3DS/Wii U installment) is not the entity's article.
            if (!TitleMatch.similar(titleKey(pageTitle), key)) continue
            val description = summary["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val extract = summary["extract"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val url = summary["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.get("page")?.jsonPrimitive?.contentOrNull
                ?: "$base/wiki/${encodeTitle(title)}"
            val sentence = extract.takeIf { it.isNotEmpty() }?.let { firstSentence(it) }
            // A short opening sentence says more than the one-line description ("відеогра 2016
            // року"); a long one is cut mid-thought, so then the description wins.
            val line = when {
                sentence != null && sentence.length <= MAX_CHARS -> sentence
                description.isNotEmpty() -> "$pageTitle: ${description.replaceFirstChar { it.lowercase() }}"
                sentence != null -> clip(sentence)
                else -> continue
            }
            return Found(pageTitle, line, url, lang)
        }
        return null
    }

    /**
     * The opening sentence of an article, without its final period. A boundary is skipped when
     * the text so far is too short to be a sentence or ends in an abbreviation ("Super Smash
     * Bros. — серія…", "Inc.", "St."): a short capitalized word before the period.
     */
    private fun firstSentence(extract: String): String {
        val text = extract.trim()
        for (m in SENTENCE_BOUNDARY.findAll(text)) {
            val candidate = text.substring(0, m.range.first + 1)
            if (candidate.length < MIN_SENTENCE_CHARS) continue
            val lastWord = candidate.trimEnd('.', '!', '?', '…').substringAfterLast(' ')
            val abbreviation = candidate.endsWith(".") &&
                (lastWord.length == 1 || (lastWord.length <= 4 && lastWord.first().isUpperCase()))
            if (abbreviation) continue
            return candidate.removeSuffix(".").trim()
        }
        return text.removeSuffix(".").trim()
    }

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
