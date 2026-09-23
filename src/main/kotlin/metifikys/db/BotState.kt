package metifikys.db

import org.jetbrains.exposed.sql.Table

/**
 * Tiny key/value store for bot runtime state that must survive restarts. Currently holds the
 * Telegram `getUpdates` offset so the updates poller resumes without reprocessing or missing.
 */
object BotStateTable : Table("bot_state") {
    val key = varchar("key", 100)
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}
