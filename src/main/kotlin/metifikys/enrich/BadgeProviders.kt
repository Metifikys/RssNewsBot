package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.NewsDatabase

private val logger = KotlinLogging.logger {}

/** Composition root for the badge providers: builds only what some category actually enables. */
object BadgeProviders {

    /**
     * @param pageHtml article page fetcher for the trailer badge's delivery-time lookup
     *   (`badges.trailerFetchOnDelivery`); null = ingest meta only.
     */
    fun resolverFor(config: AppConfig, db: NewsDatabase, pageHtml: ((String) -> String?)? = null): BadgeResolver? {
        val needed = config.categories.values.mapNotNull { it.post?.badges?.enabledIds }.flatten().toSet()
        if (needed.isEmpty()) return null
        val settings = config.badges
        val http = BadgeHttp(timeoutSeconds = settings.timeoutSeconds)
        val cache = DbBadgeCache(db)
        // One store search + one appdetails per game for every Steam-based badge.
        val steamApps = SteamAppResolver(http, cache, country = settings.steamCountry, language = settings.steamLanguage)
        val providers = buildList {
            if (SteamBadgeProvider.ID in needed) {
                add(SteamBadgeProvider(http, cache, settings.steamCountry, settings.steamLanguage, resolver = steamApps))
            }
            if (UkLocalizationBadgeProvider.ID in needed) {
                add(UkLocalizationBadgeProvider(http, cache, steamApps, settings.ukLocalizationShowMissing, settings.steamCountry))
            }
            if (CriticBadgeProvider.ID in needed) add(CriticBadgeProvider(steamApps))
            if (DeckBadgeProvider.ID in needed) add(DeckBadgeProvider(http, cache, steamApps))
            if (HackerNewsBadgeProvider.ID in needed) add(HackerNewsBadgeProvider(http, cache))
            if (GitHubBadgeProvider.ID in needed) add(GitHubBadgeProvider(http, cache, token = settings.githubToken))
            if (TrailerBadgeProvider.ID in needed) {
                val fetchPage = pageHtml.takeIf { settings.trailerFetchOnDelivery }
                add(TrailerBadgeProvider(http, cache, settings.trailerEventTypes, fetchPage))
            }
        }
        logger.info {
            "[Badge] providers enabled: ${providers.map { it.id }} (timeout ${settings.timeoutSeconds}s each, " +
                "${settings.maxTotalSeconds}s per digest)"
        }
        return BadgeResolver(providers, settings.timeoutSeconds, settings.maxTotalSeconds)
    }
}
