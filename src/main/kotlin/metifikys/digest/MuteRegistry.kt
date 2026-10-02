package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.AdminMuteKind
import metifikys.db.AdminMuteRow
import metifikys.db.NewsDatabase
import metifikys.format.EventTypeLabels
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * The effective keyword-mute lists: the YAML ones (`preferences.mute` for every category,
 * `categories.<name>.mute` for one) plus the DB overlay `admin_mutes` that Telegram commands,
 * mute suggestions, moderation and inspector buttons write. [filterFor] is what Step 0 of the
 * digest uses.
 *
 * Event-type mutes (`admin_mutes.kind = event_type`, DB only, always one category) are a separate
 * list: [mutedEventTypes] is what Step 1 post-processing drops shortlist items by — an event type
 * exists only once Step 1 has run. [entries], [covering], [add] and [remove] are keyword-only;
 * the type side has [typeEntries], [addEventType] and [removeEventType].
 *
 * Filters are built lazily and cached until [invalidate] — every write through [add] / [remove]
 * invalidates, so a new mute takes effect on the next digest run, including for articles already
 * waiting UNPROCESSED. Without a [db] (tests) only the YAML lists exist. A DB read failure falls
 * back to the YAML lists (logged) rather than failing the digest.
 */
class MuteRegistry(
    private val config: AppConfig,
    private val db: NewsDatabase? = null,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    enum class Origin(val label: String) {
        CONFIG_GLOBAL("config.yaml · preferences.mute"),
        CONFIG_CATEGORY("config.yaml · categories.<cat>.mute"),
        DB("бот")
    }

    /**
     * One effective mute. [category] null = global; [row] is set for DB entries. [kind] is
     * [AdminMuteKind.KEYWORD] or, for [typeEntries], [AdminMuteKind.EVENT_TYPE] ([keyword] then
     * holds the normalized event type).
     */
    data class Entry(
        val keyword: String,
        val category: String?,
        val origin: Origin,
        val row: AdminMuteRow? = null,
        val kind: String = AdminMuteKind.KEYWORD
    )

    sealed interface AddResult {
        data class Added(val id: Int) : AddResult
        data class AlreadyMuted(val existing: Entry) : AddResult
        data class Invalid(val reason: String) : AddResult
    }

    sealed interface RemoveResult {
        data class Removed(val count: Int) : RemoveResult
        /** Nothing in the DB, but YAML has it — only a config edit can remove it. */
        data class InConfig(val entry: Entry) : RemoveResult
        object NotFound : RemoveResult
    }

    /** Hits of a keyword over a window: matching articles, and how many of them were published. */
    data class Preview(val articles: Int, val published: Int)

    private val lock = Any()
    @Volatile private var cached: Map<String, TopicMuteFilter>? = null
    @Volatile private var cachedEntries: List<Entry>? = null
    @Volatile private var cachedTypes: List<Entry>? = null

    /** Drops the cached filters; the next [filterFor] / [entries] / [typeEntries] re-reads the DB. */
    fun invalidate() {
        synchronized(lock) {
            cached = null
            cachedEntries = null
            cachedTypes = null
        }
    }

    /** Step-0 filter for [category]: the global mutes plus the category's own. */
    fun filterFor(category: String): TopicMuteFilter {
        cached?.let { return it[category] ?: TopicMuteFilter.EMPTY }
        synchronized(lock) {
            val built = cached ?: build().also { cached = it }
            return built[category] ?: TopicMuteFilter.EMPTY
        }
    }

    private fun build(): Map<String, TopicMuteFilter> {
        val all = entries()
        return config.categories.keys.associateWith { name ->
            TopicMuteFilter.of(all.filter { it.category == null || it.category == name }.map { it.keyword })
        }
    }

    /** Every effective mute: YAML first (global, then per category), then the DB rows. */
    fun entries(): List<Entry> {
        cachedEntries?.let { return it }
        synchronized(lock) {
            cachedEntries?.let { return it }
            val fromConfig = config.preferences.mute.map { Entry(it.trim(), null, Origin.CONFIG_GLOBAL) } +
                config.categories.flatMap { (name, cat) -> cat.mute.map { Entry(it.trim(), name, Origin.CONFIG_CATEGORY) } }
            val fromDb = dbRows().filter { it.kind == AdminMuteKind.KEYWORD }.map { Entry(it.keyword, it.category, Origin.DB, it) }
            return (fromConfig + fromDb).also { cachedEntries = it }
        }
    }

    /** Every event-type mute (DB only), oldest first. */
    fun typeEntries(): List<Entry> {
        cachedTypes?.let { return it }
        synchronized(lock) {
            cachedTypes?.let { return it }
            return dbRows()
                .filter { it.kind == AdminMuteKind.EVENT_TYPE && it.category != null }
                .map { Entry(EventTypeLabels.normalize(it.keyword), it.category, Origin.DB, it, AdminMuteKind.EVENT_TYPE) }
                .also { cachedTypes = it }
        }
    }

    /** Event types muted in [category] (normalized); what Step 1 post-processing drops. */
    fun mutedEventTypes(category: String): Set<String> =
        typeEntries().filter { it.category == category }.map { it.keyword }.toSet()

    /** The type mute covering [eventType] in [category], or null. */
    fun coveringType(eventType: String, category: String): Entry? {
        val t = EventTypeLabels.normalize(eventType)
        return typeEntries().firstOrNull { it.category == category && it.keyword == t }
    }

    /** Mutes [eventType] in [category] (from the next digest on); same results as [add]. */
    fun addEventType(eventType: String, category: String, by: Long, source: String): AddResult {
        val t = EventTypeLabels.normalize(eventType)
        EventTypeLabels.validate(t)?.let { return AddResult.Invalid(it) }
        if (category !in config.categories) return AddResult.Invalid("невідома категорія $category")
        coveringType(t, category)?.let { return AddResult.AlreadyMuted(it) }
        val store = db ?: return AddResult.Invalid("немає БД")
        val id = store.insertAdminMute(t, category, by, source, AdminMuteKind.EVENT_TYPE)
        invalidate()
        logger.info { "[Mute] +type $t scope=$category by=$by source=$source" }
        return AddResult.Added(id)
    }

    /** Removes the type mute of [eventType] in [category]: [RemoveResult.Removed] or [RemoveResult.NotFound]. */
    fun removeEventType(eventType: String, category: String, by: Long): RemoveResult {
        val t = EventTypeLabels.normalize(eventType)
        val removed = db?.deleteAdminMutes(t, category, AdminMuteKind.EVENT_TYPE) ?: 0
        if (removed == 0) return RemoveResult.NotFound
        invalidate()
        logger.info { "[Mute] -type $t scope=$category by=$by" }
        return RemoveResult.Removed(removed)
    }

    private fun dbRows(): List<AdminMuteRow> {
        val store = db ?: return emptyList()
        return try {
            store.fetchAdminMutes()
        } catch (e: Exception) {
            logger.warn(e) { "[Mute] reading admin_mutes failed — using the config.yaml lists only" }
            emptyList()
        }
    }

    /**
     * The entry that already mutes [keyword] in [category]'s scope: an exact (case-insensitive)
     * match that is global, or in the same category. A category-scoped request is covered by a
     * global entry; a global request only by a global one.
     */
    fun covering(keyword: String, category: String?): Entry? =
        entries().firstOrNull {
            it.keyword.equals(keyword.trim(), ignoreCase = true) && (it.category == null || it.category == category)
        }

    /** Adds a DB mute unless it is invalid or already covered. `/mute`, suggestions and moderation all come here. */
    fun add(keyword: String, category: String?, by: Long, source: String): AddResult {
        val kw = keyword.trim()
        validate(kw)?.let { return AddResult.Invalid(it) }
        if (category != null && category !in config.categories) return AddResult.Invalid("невідома категорія $category")
        covering(kw, category)?.let { return AddResult.AlreadyMuted(it) }
        val store = db ?: return AddResult.Invalid("немає БД")
        val id = store.insertAdminMute(kw, category, by, source)
        invalidate()
        logger.info { "[Mute] +$kw scope=${category ?: "global"} by=$by source=$source" }
        return AddResult.Added(id)
    }

    /** Removes the DB mutes of [keyword] in exactly [category]'s scope. YAML entries are never touched. */
    fun remove(keyword: String, category: String?, by: Long): RemoveResult {
        val kw = keyword.trim()
        val removed = db?.deleteAdminMutes(kw, category) ?: 0
        if (removed > 0) {
            invalidate()
            logger.info { "[Mute] -$kw scope=${category ?: "global"} by=$by" }
            return RemoveResult.Removed(removed)
        }
        val inConfig = entries().firstOrNull {
            it.origin != Origin.DB && it.keyword.equals(kw, ignoreCase = true) && it.category == category
        } ?: entries().firstOrNull { it.origin != Origin.DB && it.keyword.equals(kw, ignoreCase = true) }
        return if (inConfig != null) RemoveResult.InConfig(inConfig) else RemoveResult.NotFound
    }

    /**
     * How many articles of [category] (every category when null) published in the last [days]
     * days [keyword] would have dropped, and how many of those reached a channel. Reads the
     * articles table, so it is for previews, never for the digest path.
     */
    fun preview(keyword: String, category: String?, days: Long): Preview {
        val store = db ?: return Preview(0, 0)
        val filter = TopicMuteFilter.of(listOf(keyword))
        val since = clock().minusDays(days)
        val categories = if (category != null) listOf(category) else config.categories.keys.toList()
        val hits = categories.flatMap { store.fetchRecentArticles(it, days, PREVIEW_LIMIT) }.filter { filter.matches(it) }
        if (hits.isEmpty()) return Preview(0, 0)
        val published = store.fetchDigestArticleLinksSince(category, since)
        return Preview(hits.size, hits.count { it.link in published })
    }

    companion object {
        /** Cap on the articles one category contributes to a preview (a bounded full scan). */
        const val PREVIEW_LIMIT = 50_000

        /** Null when [keyword] is an acceptable mute, else why not: 2..100 chars, not only digits. */
        fun validate(keyword: String): String? = when {
            keyword.length < 2 -> "закоротке (мінімум 2 символи)"
            keyword.length > 100 -> "задовге (максимум 100 символів)"
            keyword.all { it.isDigit() || it.isWhitespace() } -> "самі цифри"
            else -> null
        }
    }
}
