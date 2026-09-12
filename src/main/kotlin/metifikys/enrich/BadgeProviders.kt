package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.NewsDatabase

private val logger = KotlinLogging.logger {}

/** Composition root for the badge providers: builds only what some category actually enables. */
object BadgeProviders {

    fun resolverFor(config: AppConfig, db: NewsDatabase): BadgeResolver? {
        val needed = config.categories.values.mapNotNull { it.post?.badges?.enabledIds }.flatten().toSet()
        if (needed.isEmpty()) return null
        val settings = config.badges
        val http = BadgeHttp(timeoutSeconds = settings.timeoutSeconds)
        val cache = DbBadgeCache(db)
        val providers = buildList {
            if (SteamBadgeProvider.ID in needed) {
                add(SteamBadgeProvider(http, cache, country = settings.steamCountry, language = settings.steamLanguage))
            }
            if (HackerNewsBadgeProvider.ID in needed) add(HackerNewsBadgeProvider(http, cache))
            if (GitHubBadgeProvider.ID in needed) add(GitHubBadgeProvider(http, cache, token = settings.githubToken))
        }
        logger.info {
            "[Badge] providers enabled: ${providers.map { it.id }} (timeout ${settings.timeoutSeconds}s each, " +
                "${settings.maxTotalSeconds}s per digest)"
        }
        return BadgeResolver(providers, settings.timeoutSeconds, settings.maxTotalSeconds)
    }
}
