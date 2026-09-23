package metifikys.db

import org.jetbrains.exposed.sql.Database

/**
 * Facade over the bot's SQLite DB: connects, creates the schema, runs the migrations, and exposes
 * every table's queries by delegating to one repository per table (group). Consumers and test
 * mocks (`mockk<NewsDatabase>()`) use this class; the code lives in the per-table files.
 *
 * Where new code goes:
 * - New table: its own file in this package (see `BotState.kt` for the smallest example) with
 *   `object FooTable : Table("foo")`, its row DTOs, a public `interface FooStore` (default
 *   arguments go on the interface, never on the override) and a stateless
 *   `internal class FooRepository : FooStore` using the global `transaction { }`. Then register
 *   `FooTable` in [Schema.ALL_TABLES] and add `FooStore by FooRepository()` below.
 * - New query on an existing table: that table's repository. A join lives with its main table;
 *   read-only cross-table aggregates for `/status` go to `StatusQueries.kt`.
 * - New column: add it to the table object — `createMissingTablesAndColumns` adds it on start
 *   (NOT NULL needs a `.default(…)` for existing rows). Data rewrites / hand-made indexes go
 *   into [SchemaMigrations.run] and must be idempotent.
 */
class NewsDatabase(dbPath: String) :
    ArticleStore by ArticleRepository(),
    BatchStore by BatchRepository(),
    SummaryStore by SummaryRepository(),
    CoveredEventStore by CoveredEventRepository(),
    RejectedEventStore by RejectedEventRepository(),
    LlmCallStore by LlmCallRepository(),
    StatusQueryStore by StatusQueryRepository(),
    EmbeddingStore by EmbeddingRepository(),
    DigestMessageStore by DigestMessageRepository(),
    AffinityStore by AffinityRepository(),
    BadgeCacheStore by BadgeCacheRepository(),
    TrackerStore by TrackerRepository(),
    EntityIntroStore by EntityIntroRepository(),
    AdminMuteStore by AdminMuteRepository(),
    MuteSuggestionStore by MuteSuggestionRepository(),
    AdminFeedbackStore by AdminFeedbackRepository(),
    FeedStore by FeedRepository(),
    FeedCandidateStore by FeedCandidateRepository(),
    EventArticleStore by EventArticleRepository(),
    EventHistoryStore by EventHistoryRepository(),
    BotStateStore by BotStateRepository(),
    TelegraphPageStore by TelegraphPageRepository() {

    /** Canonical path of the DB file; side jobs (e.g. [RetentionArchiver]) place their files next to it. */
    val canonicalPath: String

    /** JDBC URL of the main DB (WAL + busy_timeout), for raw-JDBC side jobs such as [RetentionArchiver]. */
    val jdbcUrl: String

    init {
        // Security: reject path traversal and resolve to a canonical absolute path
        require(dbPath.isNotBlank()) { "Database path must not be blank" }
        require(!dbPath.contains("..")) { "Database path must not contain '..' (path traversal): $dbPath" }
        canonicalPath = java.io.File(dbPath).canonicalPath

        // Concurrency hardening for the multi-writer workload (category workers, reaction poller,
        // batch callbacks, weekly digest). Xerial parses these query params into SQLiteConfig and
        // applies them per-connection, so every Exposed `transaction { }` (each opens its own
        // connection) is covered:
        //   journal_mode=WAL   — readers no longer block the single writer; kills most
        //                        "database is locked". WAL is a persistent property of the db file.
        //   synchronous=NORMAL — SQLite-recommended pairing with WAL; holds write locks for less
        //                        time. Only the last transaction(s) can be lost on an OS/power
        //                        crash (not on a normal app crash) — fine for a news bot.
        //   busy_timeout=15000 — a writer that still hits a held write lock waits-and-retries up
        //                        to 15s instead of failing fast with SQLITE_BUSY.
        jdbcUrl = "jdbc:sqlite:$canonicalPath" +
            "?journal_mode=WAL" +
            "&synchronous=NORMAL" +
            "&busy_timeout=15000"
        // The repositories are stateless and share this global connection; their constructors
        // (run as the delegates above, before this block) never touch the DB.
        Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
        Schema.create()
        SchemaMigrations.run(jdbcUrl)
    }
}
