package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Cache for footer badges built from public APIs (Steam, HN, GitHub): one row per
 * (provider, key), holding the rendered badge text — or "" for a negative result, so an
 * unknown title is not looked up again every digest. Rows expire by age (TTL per provider)
 * and are pruned on the maintenance tick.
 */
object BadgeCacheTable : Table("badge_cache") {
    val provider = varchar("provider", 40)
    val cacheKey = varchar("cache_key", 500)
    val payload = text("payload")
    val fetchedAt = datetime("fetched_at").index()

    override val primaryKey = PrimaryKey(provider, cacheKey)
}

interface BadgeCacheStore {
    /** Cached badge text for (provider, key) when younger than [maxAge]; "" is a cached negative. */
    fun getBadgeCache(provider: String, key: String, maxAge: java.time.Duration): String?

    /** Upserts the badge text for (provider, key), stamping it with the current time. */
    fun putBadgeCache(provider: String, key: String, payload: String)

    fun deleteOldBadgeCache(days: Long = 7)
}

internal class BadgeCacheRepository : BadgeCacheStore {

    override fun getBadgeCache(provider: String, key: String, maxAge: java.time.Duration): String? = transaction {
        val cutoff = LocalDateTime.now().minus(maxAge)
        BadgeCacheTable
            .select(BadgeCacheTable.payload)
            .where {
                (BadgeCacheTable.provider eq provider) and
                    (BadgeCacheTable.cacheKey eq key) and
                    (BadgeCacheTable.fetchedAt greaterEq cutoff)
            }
            .firstOrNull()
            ?.get(BadgeCacheTable.payload)
    }

    override fun putBadgeCache(provider: String, key: String, payload: String) {
        transaction {
            val now = LocalDateTime.now()
            val updated = BadgeCacheTable.update(
                { (BadgeCacheTable.provider eq provider) and (BadgeCacheTable.cacheKey eq key) }
            ) {
                it[BadgeCacheTable.payload] = payload
                it[fetchedAt] = now
            }
            if (updated == 0) {
                BadgeCacheTable.insertIgnore {
                    it[BadgeCacheTable.provider] = provider
                    it[cacheKey] = key
                    it[BadgeCacheTable.payload] = payload
                    it[fetchedAt] = now
                }
            }
        }
    }

    override fun deleteOldBadgeCache(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            BadgeCacheTable.deleteWhere { fetchedAt less cutoff }
        }
    }
}
