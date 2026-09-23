package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

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
