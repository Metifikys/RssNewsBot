package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Tiny key/value store for bot runtime state that must survive restarts. Currently holds the
 * Telegram `getUpdates` offset so the updates poller resumes without reprocessing or missing.
 */
object BotStateTable : Table("bot_state") {
    val key = varchar("key", 100)
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}

interface BotStateStore {
    /** Reads a persisted state value, or null if the key was never set. */
    fun getState(key: String): String?

    /** Upserts a persisted state value. */
    fun setState(key: String, value: String)

    /** Removes a state key; true when it existed. */
    fun deleteState(key: String): Boolean
}

internal class BotStateRepository : BotStateStore {

    override fun getState(key: String): String? = transaction {
        BotStateTable
            .select(BotStateTable.value)
            .where { BotStateTable.key eq key }
            .map { it[BotStateTable.value] }
            .firstOrNull()
    }

    override fun setState(key: String, value: String) {
        transaction {
            val updated = BotStateTable.update({ BotStateTable.key eq key }) {
                it[BotStateTable.value] = value
            }
            if (updated == 0) {
                BotStateTable.insertIgnore {
                    it[BotStateTable.key] = key
                    it[BotStateTable.value] = value
                }
            }
        }
    }

    override fun deleteState(key: String): Boolean = transaction {
        BotStateTable.deleteWhere { BotStateTable.key eq key } > 0
    }
}
