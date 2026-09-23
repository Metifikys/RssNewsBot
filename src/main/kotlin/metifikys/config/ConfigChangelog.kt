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
        Entry("feedHealth.candidates", "2026-09-23", "candidate new feeds + autodiscovery, shown in the static report"),
        Entry("fetcher.privateHostAllowlist", "2026-09-23", "extra LAN hosts the SSRF guard allows (feed hosts are trusted automatically)"),
        Entry("storyIntel", "2026-09-23", "record story clusters (event_articles); enable early, the story lines need weeks of data"),
        Entry("storyIntel.historyRetentionDays", "2026-09-23", "event_history (delivered events, first appearance) retention; default 1095 days"),
        Entry("categories.*.post.storyLines", "2026-09-23", "story footer lines: 📢 outlets / original sources (needs storyIntel)"),
        Entry("weekly.showOutlets", "2026-09-23", "weekly badge 📢 N видань · M першоджерел instead of 🔁 N (needs storyIntel)"),
        Entry("weekly.barometer", "2026-09-23", "weekly industry barometer: PNG chart (or text sparkline) of layoffs vs releases from event_history"),
        Entry("weekly.sourceLeaderboard", "2026-09-23", "weekly 🥇 who-writes-first block (needs storyIntel; /status shows it anyway)"),
        Entry("categories.*.post.storyLines.vaporware", "2026-09-23", "🌀 re-announced without a date, counted over event_history (needs storyIntel)"),
        Entry("categories.*.post.storyLines.conflicts", "2026-09-23", "⚖️ sources-disagree check: log-only by default ([StoryConflict]), mode: post adds the line"),
        Entry("categories.*.post.also", "2026-09-23", "📰 Також: the other outlets folded into the event (start with logOnly: true)"),
        Entry("categories.*.post.official", "2026-09-23", "link the post to the press release / studio blog when the event cluster has one"),
        Entry("categories.*.post.images", "2026-09-23", "og:image first, no logos / avatars / small thumbnails, cluster fallback (needs enableImages)"),
        Entry("categories.*.post.badges.trailer", "2026-09-23", "▶️ trailer link: embedded YouTube video verified via oEmbed (badges.trailerEventTypes)"),
        Entry("categories.*.post.crossLinks", "2026-09-23", "🔗 link to the related post in another category's channel (needs semanticDedup.eventEnabled; start with logOnly: true)"),
        Entry("categories.*.post.longForm.telegraph", "2026-09-23", "⚡ long post as a telegra.ph Instant View page (teaser | full), token in TELEGRAPH_TOKEN or bot_state"),
        Entry("weekly.album", "2026-09-23", "photo album of the stories' pictures before the weekly roundup text (sendMediaGroup)"),
        Entry("categories.*.dedup.digest.antiBubble", "2026-09-23", "🧭 weekly pick of an important event outside the audience's taste, variant explore (needs feedback.enabled)"),
        Entry("categories.*.post.persona", "2026-09-23", "💬 editor-persona remark under the top event, A/B persona / control (needs your own promptFile; /status 🧪 Variants)"),
    )

    /** Entries whose path does not occur in [presentPaths] (schema-form paths found in the YAML). */
    fun missing(presentPaths: Set<String>): List<Entry> = ENTRIES.filter { it.path !in presentPaths }
}
