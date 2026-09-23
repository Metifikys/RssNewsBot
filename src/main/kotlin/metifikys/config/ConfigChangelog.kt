package metifikys.config

/**
 * Hand-kept register of config options worth knowing about when the prod `config.yaml` (synced by
 * hand) does not set them yet — a new option otherwise just runs on its default unnoticed, as
 * `processing.categoryDeadlineMinutes` and `dedup.extractMaxArticles` did. Deliberately NOT "every
 * key missing from the file": there are hundreds of defaulted keys and nobody reads that list.
 *
 * Add an entry together with a new option (path in [ConfigSchema] form; `ConfigChangelogTest`
 * fails on a path the schema does not have, which also catches renames).
 */
object ConfigChangelog {

    data class Entry(val path: String, val since: String, val hint: String)

    val ENTRIES: List<Entry> = listOf(
        Entry("processing.categoryDeadlineMinutes", "2026-07-15", "cycle-mode barrier; 0 waits for all categories"),
        Entry("categories.*.dedup.extractMaxArticles", "2026-07-16", "cap on articles per Step 1 call (prod tech needs ~40)"),
        Entry("preferences.mute", "2026-08-28", "global keyword blocklist before Step 1"),
        Entry("openrouter.models", "2026-08-28", "OpenRouter failover ladder"),
        Entry("scheduler.perCategory", "2026-09-05", "independent per-category schedulers"),
        Entry("fetcher.intervalMinutes", "2026-09-05", "decoupled ingestion timer"),
        Entry("fetcher.scrubRulesFile", "2026-09-05", "site-chrome scrub rules file"),
        Entry("categories.*.post.threads", "2026-09-12", "follow-ups as replies to the earlier post"),
        Entry("categories.*.post.hashtags", "2026-09-12", "hashtag footer"),
        Entry("categories.*.post.badges", "2026-09-12", "Steam / HN / GitHub badges"),
        Entry("categories.*.post.intro", "2026-09-12", "first-mention Wikipedia intro"),
        Entry("categories.*.trackers", "2026-09-12", "living tracker messages (needs eventDate in the extract prompt)"),
        Entry("categories.*.dedup.digest.breakingNewsworthiness", "2026-09-12", "breaking lane past the hold-back"),
        Entry("categories.*.dedup.digest.maxPostsPerEvent", "2026-09-12", "hard cap on posts per story"),
        Entry("categories.*.post.longForm", "2026-09-23", "long-form posts for the top events (render prompt needs depth)"),
        Entry("archive", "2026-09-23", "copy expired rows to monthly SQLite archives before deleting"),
        Entry("admin.userIds", "2026-09-23", "who may use admin commands and buttons"),
        Entry("admin.commands", "2026-09-23", "/pause /resume /run /mute … in the admin chat"),
        Entry("admin.muteSuggestions", "2026-09-23", "weekly mute proposals with buttons"),
        Entry("admin.moderation", "2026-09-23", "per-post delete / mute / more-less buttons in the admin chat"),
        Entry("health", "2026-09-23", "claude / codex login and usage-limit alerts in the admin chat"),
        Entry("feedHealth", "2026-09-23", "per-feed quality metrics + fetch health, /status 📡 Feeds section"),
        Entry("categories.*.feeds[].kind", "2026-09-23", "converted feeds (RSSHub): social / release / video handling"),
        Entry("fetcher.pathScopedThrottleHosts", "2026-09-23", "per-route 429 throttle for the RSSHub host"),
    )

    /** Entries whose path does not occur in [presentPaths] (schema-form paths found in the YAML). */
    fun missing(presentPaths: Set<String>): List<Entry> = ENTRIES.filter { it.path !in presentPaths }
}
