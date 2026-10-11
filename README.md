# RssNewsBot

A Kotlin/JVM Telegram bot that aggregates RSS feeds by category, **deduplicates near-identical
stories at four independent layers** (link, semantic vector, LLM event-extraction, editorial
ranker), generates Ukrainian-language digests through a two-step LLM pipeline
(**extract → render**), and delivers them to Telegram channels on a configurable schedule.

Pluggable across **OpenAI, Anthropic, and OpenRouter** with per-category, per-use-case routing;
first-class support for **OpenAI Batch API** and **Anthropic Message Batches** (50 %-priced
async) with three-tier sync fallback; SQLite persistence; per-call cost telemetry surfaced
via a `/status` admin snapshot.

---

## Table of contents

1. [Feature overview](#feature-overview)
2. [End-to-end pipeline](#end-to-end-pipeline)
3. [The deduplication stack](#the-deduplication-stack) ← all four layers explained
4. [LLM routing & multi-provider support](#llm-routing--multi-provider-support)
5. [Batch API, backpressure, three-tier fallback](#batch-api-backpressure-three-tier-fallback)
6. [Article lifecycle & status state machine](#article-lifecycle--status-state-machine)
7. [Telegram delivery & formatting](#telegram-delivery--formatting)
8. [Cost telemetry & the `/status` snapshot](#cost-telemetry--the-status-snapshot)
9. [Security & hardening](#security--hardening)
10. [Setup](#setup)
11. [Configuration reference](#configuration-reference)
12. [Build, run, tests](#build-run-tests)
13. [Database schema](#database-schema)
14. [Project layout](#project-layout)
15. [Dependencies](#dependencies)

---

## Feature overview

### News intake
- **RSS / Atom fetching** via Rome through one shared bounded queue (`fetcher:` block):
  retries on 408/429/5xx with `Retry-After`, a wall-clock deadline per fetch stage, and a
  self-tuning per-host throttle shared with the article fetcher.
- **Ingestion hygiene** — tracking variants of a link are normalized to one row, feed HTML is
  stripped from descriptions, site chrome is scrubbed from extracted text by the operator's
  rules (`fetcher.scrubRulesFile`, see `scrub-rules.example.yaml`), and reddit link posts are
  repointed at the article they link to.
- **Converted feeds** — non-RSS sources (Telegram channels, X accounts, …) come in through a
  self-hosted converter (RSSHub); platform feeds (GitHub releases, YouTube, Mastodon, Bluesky) are
  read directly. Per-feed options, all off for a plain feed: `kind` (`article` / `social` /
  `release` / `video`) derives a title from the post's first line when the item has none
  ([`FeedTitles`](src/main/kotlin/metifikys/fetch/FeedTitles.kt); `social` also replaces a title
  that merely repeats the post), reads YouTube's `media:group` thumbnail and gives Step 1 a
  `source` hint (`official account: <label>`); `minTextChars` drops stickers and one-emoji posts;
  `titlePrefix` / `exclude` shape GitHub release titles and drop `-rc` / nightly builds;
  `repointLinks` turns a short post with one outbound article link into that article
  ([`SocialLinkExtractor`](src/main/kotlin/metifikys/fetch/SocialLinkExtractor.kt); never a social,
  store, converter or private host), keeping the post URL in `articles.meta_json`.
  `fetcher.pathScopedThrottleHosts` gives each converter route its own 429 throttle.
- **Keyword mute** (`preferences.mute` + per-category `mute:`, plus `/mute` from the admin
  chat) — whole-word blocklist applied before Step 1; matching articles never reach an LLM.
- **SSRF guard** ([`UrlGuard`](src/main/kotlin/metifikys/fetch/UrlGuard.kt)) — only `http`/`https`;
  a host that RESOLVES to a loopback / RFC-1918 / link-local / CGNAT / unique-local / multicast
  address (IPv4 or IPv6, IPv4-mapped / NAT64 / 6to4 forms included) is refused. The only
  exceptions are the `host:port` of every configured feed URL (so a LAN RSSHub works with no extra
  config) and `fetcher.privateHostAllowlist`. Article links, reddit / converter repoints, the
  long-form refetch, feed discovery and the badge / intro APIs get no other exception, and every
  redirect hop is re-checked — by hand for feeds and discovery, by an OkHttp network interceptor
  (judging the address actually connected to) for article pages, markdown.new and badges.
- **No-redirect HTTP** — Telegram client refuses redirects so the bot token cannot leak to a
  third-party host.
- **Image extraction from RSS** — falls back across `<enclosure>`, Media RSS module
  (`<media:content>`, `<media:thumbnail>`), and the first `<img>` in the description HTML.
- **Optional full-article fetch** (`fetchFullContent: true` per feed) via Jsoup, before
  per-article summarization.

### Deduplication (four independent layers — see [§3](#the-deduplication-stack))
1. **Link dedup** in SQL — `articles.link` unique index + pre-enrichment skip of already-known
   URLs.
2. **Semantic vector dedup** — OpenAI `text-embedding-3-small` + L2-normalized cosine; soft
   log threshold + optional hard reject against already-PROCESSED neighbours.
3. **LLM event extraction (Step 1)** — JSON-mode shortlist of new/updated events vs. a
   structured covered-events memory.
4. **Editorial ranker + cooldown** — `newsworthiness × digestFit` composite score, diversity
   caps per franchise/subject, weak-shortlist hold-back, meaningful-update cooldown.

### LLM rendering & routing (see [§4](#llm-routing--multi-provider-support))
- **Multi-provider**: OpenAI / Anthropic / OpenRouter plus the subscription-billed Claude CLI
  (`claude -p`) and Codex CLI (`codex exec`), mixable per category and per use case
  (`extract`, `render`, `batch`, `summarize`, `batchFallback`, `persona`, `updateDiff`, …).
- **OpenRouter model ladder** — `openrouter.models` is a priority list walked on failure, so a
  withdrawn free-tier model fails over to the next instead of taking the use case down.
- **Bounded Step-1 prompt** — `dedup.extractMaxArticles` / `extractMaxPromptChars` keep the
  extract call inside a CLI timeout; overflow waits for the next run.
- **Audience feedback loop** (`feedback:` block) — channel reactions aggregate into an affinity
  table that can re-rank the shortlist (`ranker.reactionWeight`) and brief the extract prompt
  (`{{AUDIENCE_SIGNALS}}`), log-only until switched on.
- **Weighted provider split** — any slot can be `{ providers: [ { percent, provider, model }, … ] }`:
  every call goes to one entry so the day's calls follow the percents exactly (20/10/70 → 2/1/7
  per 10 calls, interleaved). Tallies are per category + slot in `bot_state`, survive a restart,
  reset at local midnight. Replaces the old Step 1 A/B (`extractAlternate`) — a 50/50 split.
- **🌓 Shadow mode** (`llm.shadow`) — a "B" model and/or prompt file runs on exactly the input
  of the live "A" (Step 1: same batch and covered context; render: A's shortlist), in parallel
  on its own thread; only A is posted. Both outputs pass the same post-processing and land in
  `shadow_runs`; `node bench/shadow-dump.mjs <db> <outDir>` writes `shadow.md` + `shadow.html`
  with A | B side by side (shortlist URLs both / only A / only B with statuses and scores, or
  the topics), Jaccard, B parse-fail rate and latency; `/status` shows
  `🌓 shadow 24h: N runs · B fail K · Jaccard 0.xx`. B calls are metered as
  `SHADOW_EXTRACT` / `SHADOW_RENDER`.
- **On-failure fallback** — any sync slot can carry a nested `fallback` provider+model; when the
  primary call fails (usage limit, expired login, bad model, timeout) the same call is retried
  against the fallback (e.g. Claude CLI → Codex CLI). Distinct from the weighted split (`providers:`) and
  backpressure (`batchFallback`).
- **Per-article summarization** — opt-in per feed; stored on `articles.summary` and used by
  downstream prompts in place of the raw RSS description. The model reads up to
  `fetcher.summarizeInputChars` (12000) of the extracted page text, while the stored description
  stays cut at 1500.
- **Inline prompt overrides** — `dedup.prompts.extractSystem/extractUser/renderSystem/renderUser`
  take priority over the prompt file.

### Batch API (see [§5](#batch-api-backpressure-three-tier-fallback))
- **Native OpenAI `/v1/batches`** and **Anthropic `/v1/messages/batches`** — 50 % cheaper,
  async, used for Step 1 extract and Step 2 render.
- **Three-tier routing**: primary batch → `batchFallback` (sync alt LLM) → sync render.
- **Restart-safe** — pending batches persist in DB and are polled to completion after a
  crash/restart.

### Scheduling & concurrency
- **One scheduler per category** (`scheduler.perCategory`, default) — each category runs on its
  own thread at `categories.<name>.intervalMinutes` (default `scheduler.intervalMinutes`), first
  run at startup. Runs of one category never overlap; a slow or stuck category never delays the
  others.
- **Optional decoupled ingestion** — with `fetcher.intervalMinutes` (or a per-category
  `fetchIntervalMinutes`) the fetch → enrich → summarize → insert half runs on its own timer, and
  the digest run is only Step 1 + Step 2 on what is already in the DB.
- **Maintenance tick** on the shared interval: retention cleanup, affinity rebuild, `/status`
  post.
- **Legacy global cycle** (`perCategory: false`) still available: all categories fanned out in
  parallel, cycle ends when the slowest finishes (`processing.categoryDeadlineMinutes` opts back
  into an interrupting barrier).
- Graceful 30-second shutdown that stops every scheduler and interrupts in-flight runs; rows a
  killed worker left `PROCESSING` are reclaimed at the next startup.

### Telegram delivery
- Direct HTTP via OkHttp (no `telegrambots` library).
- **Markdown → plain-text fallback** on parse errors.
- **Photo + caption mode** (`enableImages: true`) — when the first linked article has an
  image, the bullet is posted as a single `sendPhoto` call (caption truncated to 1024 chars).
- **Strict three-block topic layout** (emoji + bold headline / body / source link) with
  graceful degradation when the LLM emits unexpected shapes.
- **At-least-once with rollback** — partial-send failures revert just the affected articles to
  `UNPROCESSED`, success wins on overlap.
- **Post presentation** (`post:` per category, every sub-block optional; only the text that goes
  to Telegram changes — the stored summary and the reaction rows keep the clean bullet):
  - **Footer order** — the lines under a bullet always come in one order, which is also the
    priority when a caption is tight (the last lines are dropped first,
    [`FooterLines`](src/main/kotlin/metifikys/format/FooterLines.kt)): 💬 persona, badges
    (incl. ▶️ trailer), 📚 intro, 🧭 anti-bubble, 🔗 other channel, story lines (📢 / 🥇, ⚖️, 🌀),
    📰 also, hashtags. A slot whose feature is off stays empty.
  - **threads** — a `meaningful_update` bullet is sent as a Telegram *reply* to the latest post
    about the same story (looked up in `digest_messages` by the item's related/own event key),
    so the channel shows a quote header that jumps to it.
  - **hashtags** — static category tags plus tags from Step 1's franchise / event type /
    subject (`#gaming #silksong`), Latin diacritics folded, Cyrillic kept.
  - **badges** — deterministic live-data lines from public APIs, cached with a hard time
    budget: Steam price / review score / release date, Hacker News points and comments
    (only from `badges.hnMinPoints`, default 10 — a fresh `newest?q=` submission shows nothing),
    GitHub stars (only from `badges.githubMinStars`, default 50), and `▶️ [Трейлер](https://youtu.be/…)` (`trailer`, event types `badges.trailerEventTypes`): the
    YouTube video the article embeds ([`VideoLinks`](src/main/kotlin/metifikys/fetch/VideoLinks.kt) — the feed item at
    ingest, else the post page at delivery with `trailerFetchOnDelivery`; article body only, no
    sidebars / related blocks), shown only when YouTube's oEmbed knows it and its title names the
    subject or franchise, so a site's promo clip gets no line; cached 7 days.
    Steam-based badges, each its own flag, share one store search + `appdetails` per game
    ([`SteamAppResolver`](src/main/kotlin/metifikys/enrich/SteamAppResolver.kt), appid cached 7 days). Step 1's
    subject is a Ukrainian topic, so the store is searched for the Latin titles inside it that share a word
    with the franchise's game ("Titan Quest II" from "Вихід Titan Quest II з раннього доступу"), then for
    the franchise's game with an exact title match only (no "Grand Theft Auto V" for a GTA 6 story):
    `ukLocalization` — `🇺🇦 Українська: інтерфейс і субтитри, без озвучки` from Steam's
    `IStoreBrowseService/GetItems` (Ukrainian = language 26; the `appdetails` language list as the
    fallback), no line without Ukrainian unless `badges.ukLocalizationShowMissing`;
    `deck` — `🕹 Steam Deck: ✅ перевірено · ProtonDB: Platinum (354 звіти)` from Valve's Deck
    compatibility report and ProtonDB's summary (both undocumented, keyless; ProtonDB from 5
    reports; also on `patch` events), cached 24 h;
    `critic` — `🏆 [Metacritic](…): 90` on release / review events of a game that is out: the PC
    Metascore Steam's `appdetails` already carries (Metacritic has no API; Steam fills the field
    some days after release), plus `OpenCritic: 91, рекомендують 97%` when `badges.openCriticKey` (env
    `OPENCRITIC_RAPIDAPI_KEY`, a RapidAPI key) is set — without it no request goes to OpenCritic;
    `steamTrend` — `📈 Steam: №4 у хітах продажу · №12 серед найочікуваніших` when the game is in the top
    `badges.steamTrendTopN` (25) of a chart: top sellers, most wishlisted, popular upcoming (the store
    search page's undocumented JSON, region `steamCountry`) or most played (Web API
    `ISteamChartsService/GetMostPlayedGames`); at most two charts, each cached whole for 3 h.
    `cve` (for tech) — `🛡 [CVE-2021-44228](…): CVSS 10.0, критична · експлуатується (CISA KEV)` for up
    to 2 CVE IDs the bullet or its articles name, from the NVD API 2.0 (CVSS v4 → v3.1 → v3.0 → v2,
    NVD's score before the CNA's; the provider keeps NVD's 5 requests / 30 s window, 50 with
    `badges.nvdApiKey` / env `NVD_API_KEY`); analyzed records cached 7 days, pending 6 h, misses 3 h.
    *This product uses the NVD API but is not endorsed or certified by the NVD.*
  - **intro** — the first time a franchise appears in the category, one Wikipedia line
    (`📚 Team Cherry: австралійська студія відеоігор · Вікіпедія`), remembered in `entity_intros`.
    An article counts only when it is on topic (`intro.topicWords`: video game / developer / studio …;
    an off-topic hit retries the name with "відеогра" / "video game" — Rust the game, not the language)
    and, for the game of a `Game / Studio` franchise, names that studio (not another game of that name).
  - **longForm** — the top-ranked events of a digest (composite ≥ `minComposite`, at most
    `maxPerDigest`) go to render marked `"depth": "long"`, with the article page refetched as
    source text, and come back as a two-paragraph post; one longer than a photo caption is sent
    as text with the image as a large link preview above it. Needs the render prompt's
    LONG-FORM ITEMS block.
    With **telegraph** the long post is also published as a telegra.ph page (picture, paragraphs,
    «Джерела», channel link; [`TelegraphPage`](src/main/kotlin/metifikys/telegraph/TelegraphPage.kt))
    that Telegram opens with ⚡ Instant View: `mode: teaser` posts the headline, the first
    paragraph, `⚡ [Читати повністю](…)`, the source and the footer with the page as the preview;
    `full` keeps the post and adds the ⚡ line; `link` posts only the page — a hidden link, so the
    channel shows just its Instant View card (no text, source or footer). Any Telegraph failure or timeout sends the long post
    as before. The account (`telegraph:` at the top level) uses `TELEGRAPH_TOKEN` / `accessToken`,
    else a token created once and kept in `bot_state`; pages are recorded in `telegraph_pages`.
    *Not implemented, by decision:* feeding a long post from the transcript of the video it embeds
    (idea 20). Every keyless route (watch-page caption tracks / timedtext, the innertube player
    endpoint, `yt-dlp --write-auto-subs`) automates access to YouTube outside its API, which
    YouTube's Terms of Service forbid; the Data API's `captions.download` serves only the video
    owner's own captions; server IPs get bot checks and blocks that would also take out the ▶️
    trailer badge. How often a long
    event has a video at all can be read without touching YouTube: the ingest already stores the
    embedded id (`articles.meta_json` → `video.youtubeId`) for categories with the trailer badge.
  - **storyLines** (needs `storyIntel.enabled`) — deterministic footer facts from the story
    clusters, placed before the hashtags. **hype**: `📢 9 видань · 1 першоджерело` — outlets are
    distinct domains; a member is a retelling when its title/teaser credits another outlet
    ([`Attribution`](src/main/kotlin/metifikys/story/Attribution.kt): `via`, `according to`,
    `як повідомляє`, `з посиланням на`, …; its own outlet does not count) or is a near-copy
    (`reprintSimilarity`) of an earlier outlet's article; all crediting one outlet gives
    `усі з посиланням на X`. Shown from `minOutlets` outlets. The weekly roundup can show the same
    fact over the complete week's clusters instead of `🔁 N` (`weekly.showOutlets`). **first**:
    `🥇 першим був insider-gaming.com (+2 год)` — the outlet with the earliest article
    ([`Genealogy`](src/main/kotlin/metifikys/story/Genealogy.kt); feed date, or `fetched_at` when
    the feed date lies in its future), from `minOutlets` outlets and a `minLeadMinutes` lead; with
    hype on both share one line. The per-outlet ranking
    ([`SourceLeaderboard`](src/main/kotlin/metifikys/story/SourceLeaderboard.kt): first rate,
    median lag, retelling share) is a `/status` section `🥇 Хто першим`, an optional weekly block
    (`weekly.sourceLeaderboard`, copiers off by default) and the "Who writes first" section of
    `bench/db-audit/report.mjs`.
    **conflicts**: `⚖️ Джерела розходяться: дата 14.10.2026 (ign.com) чи 21.10.2026 (pcgamer.com)` —
    dates, prices and head counts read deterministically from each member's title + teaser
    ([`FactSlots`](src/main/kotlin/metifikys/story/FactSlots.kt),
    [`ConflictDetector`](src/main/kotlin/metifikys/story/ConflictDetector.kt)) that disagree between
    outlets (`minSupport` outlets per value; dates beyond `dateToleranceDays`, amounts beyond 1%; an
    article with several values in a slot is skipped). `mode: log` (default) only logs
    `[StoryConflict]` — plus `[StoryConflict][late]` when a Layer 2 reprint contradicts its cluster —
    so the precision can be checked before `mode: post` adds the line.
    **vaporware**: `🌀 анонсовано вчетверте, дати досі немає (вперше: 03.2025)` — for an undated
    item of an `announceTypes` type, the waves of undated announcements about the same subject in
    `event_history` (a gap of `minGapDays` starts a new wave;
    [`Vaporware`](src/main/kotlin/metifikys/story/Vaporware.kt)), from the `minAnnouncements`-th; a
    day / month date anywhere in its history means no line (a year / quarter only with
    `softDatesStop`).
  - **also** — `📰 Також: [IGN](…) · [PC Gamer](…)`: the other outlets of the batch that Step 1
    folded into the event (`ShortlistItem.sourceLinks` — its `articleIndices` plus the in-batch
    duplicates, which are never posted on their own), without the post's own host, one per host,
    at most `max`; names from `names` (a parent domain matches), else the feed `label`, else the
    domain ([`AlsoLine`](src/main/kotlin/metifikys/format/AlsoLine.kt)). `[Also]` logs the count
    for every event; `logOnly` keeps the line out. Late Layer-2 reprints are not added (that
    would need editing the sent post).
  - **official** — when the event's batch cluster holds an article on one of `domains` (subdomains
    included; the first listed domain that matches wins; `matchEntityDomain` also takes a host
    named after the franchise / subject), the post links to that press release / studio blog
    instead of the outlet Step 1 picked — image, long-form source and `covered_events.url` follow,
    the outlet goes to the also line. After the ranker; logs `[Official] <key>: ign.com →
    blog.playstation.com` ([`OfficialSource`](src/main/kotlin/metifikys/digest/OfficialSource.kt)).
  - **images** (with `enableImages`) — [`ImagePicker`](src/main/kotlin/metifikys/fetch/ImagePicker.kt)
    drops logos, avatars, tracking pixels, `.svg` / `.ico`, known widths under `minWidth` and
    aspect ratios beyond 4:1 / 1:3 (plus `ignore` URL substrings). At ingest the first acceptable
    feed image is kept; at delivery the post article's `og:image` goes first (`ogFirst`, one page
    fetch per post under `timeoutSeconds` / `maxTotalSeconds`, cached in `badge_cache` as
    `ogimage`), then the topic's stored images, then the cluster's (`fromCluster`); `probe` sizes an
    undeclared picture from its first 64 KB (`Range` GET through the SSRF guard). No acceptable
    picture → a text post.
  - **crossLinks** — `🔗 У 🖥 Tech: [<subject>](https://t.me/…)`: the post about the same story
    in another category's channel. The event's stored Layer-3.5 vector (`event_embeddings`) is
    compared with those of `categories` over `maxAgeDays` (same model); the best match at cosine
    >= `minSimilarity` that has a `digest_messages` row is linked (a category on the same channel
    is skipped). No LLM, no extra embedding call; needs `semanticDedup.eventEnabled` on both
    sides ([`CrossLinks`](src/main/kotlin/metifikys/digest/CrossLinks.kt)). `[CrossLink]` logs
    each match; `logOnly` keeps the line out. Links point back only (newer post → older).
  - **persona** — `💬 <remark>`: one characterful line from the editor under the digest's top
    event, as an A/B test. The event key fixes the arm (`share` of keys → `persona`, the rest →
    `control` with no call); both go to `digest_messages.variant` and `/status` compares them
    (`🧪 Variants` (30d): posts, reactions/post, share reacted, share of positive reactions). One
    extra LLM call per digest at most (`llm.persona`, else the render route; metered as `PERSONA`).
    [`PersonaRemark`](src/main/kotlin/metifikys/format/PersonaRemark.kt) guards the remark (length,
    no URL / markup / banned phrase, no number the topic lacks); a failed guard or call means no
    line and no variant. The voice is the owner's: `promptFile` (see
    `prompts/persona.example.yaml`) is required.
- **Industry barometer** (opt-in, `weekly.barometer`, needs `storyIntel`) — after the weekly
  roundup, a 1200×675 PNG of distinct subjects per ISO week over the last 12 weeks from
  `event_history`, one panel per series with its own scale: layoffs / closures (by type, or a
  keyword in an `industry_news` subject) and release news (`release_date` / `delay`, by the week it
  was posted; `byEventDate` counts by the release day instead, sparse). Drawn with java.awt headless and a bundled DejaVu Sans
  subset (`src/main/resources/fonts`, Bitstream Vera / public-domain licence, ~160 KB), posted as a
  multipart photo through the same pacer / 429 handling. `format: text` — and a chart that cannot
  be drawn — puts a `▁▂▅█` sparkline block into the roundup instead; nothing before `minWeeks`.
- **Weekly album** (opt-in, `weekly.album`) — before the roundup text, one `sendMediaGroup` of
  the pictures of the stories its bullets link (their stored `articles.image_url`, in bullet order,
  deduplicated, judged by the category's `post.images` picker and a HEAD preflight: `image/…`, at
  most `maxBytes`), the header line as the caption of the first photo; `minPhotos`..`maxPhotos`
  (2..5). Fewer usable pictures or a failed album → the text alone, as before.
- **Living trackers** (`trackers:` per category) — e.g. a pinned "📅 Найближчі релізи" message
  the bot edits after every digest from `release_date` / `delay` events and their `eventDate`
  (year cross-checked against the article), with `⏳ було <old date>` on a delay, a
  "released this week" section, and a link to the latest post per story.
- **Breaking lane** (`dedup.digest.breakingNewsworthiness`) — one post-ranker item at or above
  the threshold skips the weak-shortlist hold-back, so a lone top story goes out at once; and a
  **posts-per-story cap** (`dedup.digest.maxPostsPerEvent`) that no newsworthiness score can
  override.
- **Anti-bubble** (opt-in, `dedup.digest.antiBubble`, needs the ranker and `feedback`) — at most
  once per `intervalDays`, one event the ranker left out with newsworthiness >= `minNewsworthiness`
  and audience affinity <= `maxAffinity` (never under the digestFit floor, never a subject already
  in the digest) is added past `maxDigestItems`
  ([`AntiBubble`](src/main/kotlin/metifikys/ai/dedup/AntiBubble.kt)). It posts with a `🧭 Поза
  звичним вибором` line and `digest_messages.variant = explore`, does not count toward the
  hold-back or the breaking lane, never goes long-form; the interval (`bot_state`) starts only
  once the post was sent. Logs `[AntiBubble] <key> nw=9 affinity=-0.21 (dropped: maxDigestItems)`.
- **Update diff** (opt-in, `dedup.digest.updateDiff`) — every `meaningful_update` in the final
  render shortlist goes, in one LLM call (`llm.updateDiff`, else the render route; metered as
  `UPDATE_DIFF`), to a model together with the story's last posts as the channel published them
  (`digest_messages.text`, the clean topic without footer; the chain follows `related_event_key`;
  older posts fall back to the covered event's subject + coreFact) and the update's source
  articles. No fact absent from every earlier post → the update is dropped (`[UpdateDiff] skip
  <key>: <reason>`); otherwise it carries `newFacts`, the render writes only those (`Оновлення: …`,
  as a reply) and it never goes long-form ([`UpdateDiff`](src/main/kotlin/metifikys/digest/UpdateDiff.kt)).
  Fail-open; `logOnly` (default) only logs `[UpdateDiff][LOG-ONLY]`. Prompts: built-in, or
  `updateDiff.system` / `.user` in the dedup prompt file; the render prompt needs a `newFacts` rule.

### Observability
- **Logback** with day-rolling, gzipped archive; structured `[Category:foo]` /
  `[SemanticDedup]` / `[Batch]` / `[Ranker]` prefixes.
- **Run ids + JSON log** — every category run gets an MDC `runId`
  (`<category>-<phase>-<yyyyMMdd-HHmmss>-<4 hex>`, phase run / ingest / digest / cmd / resume /
  weekly / maint) that follows its work into the fetch pools and the batch callbacks
  ([`RunContext`](src/main/kotlin/metifikys/log/RunContext.kt)); the text log shows it after the
  thread name and `/run` replies with it. Alongside the text log, `logs/rssbot.json.log` holds the
  same stream as one JSON object per line (logback's built-in `JsonEncoder`, MDC under `mdc`), kept
  for two days only: `zcat -f logs/rssbot*.json.log* | jq -c 'select(.mdc.runId=="tech-digest-…")'`.
- **Reaction tracking** (opt-in, `telegram.updatesPolling: true`) — a `getUpdates` long-poll loop
  records anonymous channel-post reaction counts (`message_reaction_count`) into `reaction_counts`,
  joined back to the rendering event via `digest_messages`, and surfaced in the `/status`
  "Reactions (7d)" panel. One `getUpdates` consumer per token; the bot must be a channel admin.
- **`/status` admin snapshot** — per-category last digest + ready count + pending batches +
  24h/7d LLM cost (per provider × category), posted one-message-at-a-time so the admin chat
  always shows the latest state.
- **🧐 Fact-check** (opt-in, `factCheck:`) — after delivery a sampled share of the sent posts
  goes, on its own thread, to a judge model together with exactly the sources the render saw
  (`PromptBuilder.renderSourcesFor`: the item's articles, long-form full text included; for a
  legacy topic the articles it links); the judge answers
  `{verdict: supported|minor|unsupported, claims: [...]}` and the row lands in `fact_checks`.
  Log-only — nothing is edited or blocked. `/status`: `🧐 Fact-check 7d: 42 · unsupported 3 (7%)
  · minor 5` once `alert.minChecks` checks exist, and a ⚠️ line with links to the 3 latest
  unsupported posts above `alert.maxUnsupportedRate`. `node bench/factcheck-dump.mjs` lists the
  verdicts with their claims for calibrating the prompt (`prompts/factcheck.example.yaml`).
- **Admin commands** (opt-in, `admin.commands.enabled`) — the same `getUpdates` poller also
  subscribes to `message` + `callback_query` and routes them to
  [`AdminCommandRouter`](src/main/kotlin/metifikys/telegram/AdminCommandRouter.kt) /
  [`AdminCallbackRouter`](src/main/kotlin/metifikys/telegram/AdminCallbackRouter.kt). Only users in
  `admin.userIds`, only in the admin chat or a private chat with the bot; strangers get no answer.
  Commands run one at a time on an `admin-commands` thread, their update offset is saved before
  they run (a crash never replays a command), and one older than 10 minutes is refused as stale.
  Commands ([`AdminCommands`](src/main/kotlin/metifikys/telegram/AdminCommands.kt)): `/status`,
  `/pause <cat> [6h|2d]` / `/resume <cat>` (digest only, ingestion continues; kept in `bot_state`),
  `/run <cat> [fetch]` (queued on the category's own scheduler thread, reports the post count),
  `/mute <word|"phrase"> [cat]` with a 7-day hit preview, `/unmute`, `/mutes [yaml]`;
  `/mute type:<eventType> <cat>` (e.g. `type:rumor gaming`) mutes a Step 1 event type in one category —
  such shortlist items are dropped after Step 1, before the ranker (`[Mute] type rumor dropped <key>`),
  and their articles are reconciled like any other dropped item; `/unmute type:rumor gaming` lifts it. Telegram
  mutes go to the `admin_mutes` table and are merged over the YAML lists by
  [`MuteRegistry`](src/main/kotlin/metifikys/digest/MuteRegistry.kt), so a hand-synced prod config
  cannot wipe them; `/mutes yaml` prints them as a config fragment to move into the repo.
- **Mute proposals** (opt-in, `admin.muteSuggestions`) —
  [`MuteSuggester`](src/main/kotlin/metifikys/feedback/MuteSuggester.kt) runs on the maintenance
  tick every `everyDays`: disliked franchises (negative affinity with enough reacted posts) and
  franchises Step 1 keeps rejecting without ever covering become keywords, checked against 30
  days of titles, and up to `maxPerRun` go to the admin chat as silent messages with
  in-category / global / no buttons. Decisions are kept in `mute_suggestions`.
- **Post moderation in the admin chat** (opt-in, `admin.moderation`) — no buttons under channel
  posts (every reader would see them); instead
  [`ModerationNotifier`](src/main/kotlin/metifikys/telegram/ModerationNotifier.kt) sends one
  silent message per published post to the private admin chat, after the digest and on its own
  thread: 🗑 delete (confirmed; every chunk, within Telegram's 48 h; `covered_events` kept so the
  story is not re-posted), 🔇 mute the franchise (same DB mute as `/mute`), 👍/👎 an explicit
  signal in `admin_feedback` that affinity counts as `feedback.explicitWeight` reactions at once.
- **Post inspector** (`admin.inspect`, on whenever `admin.commands` is) —
  [`PostInspector`](src/main/kotlin/metifikys/telegram/PostInspector.kt): send the bot `/inspect <link>`,
  a message with up to 5 post links (`t.me/c/<id>/<msg>`, `t.me/<username>/<msg>`; a later chunk of a
  chunked post resolves to the same post) or forward a channel post, and get one compact card per post
  ([`PostCard`](src/main/kotlin/metifikys/telegram/PostCard.kt)): category, time, A/B variant, the story
  chain (`↩` the previous post, `↪` later updates), the Telegraph page; the event (key, new / update,
  then Step 1's `covered_events` fields under their own names — subject, franchise, eventType,
  coreFact, importance · newsworthiness · digestFit, plus the ranker's composite — and "why it passed"
  only where the data proves it — explore, the digestFit override, the breaking threshold); each
  source with its feed (`label`), `published → fetched (+N h)`, status / drop reason, the feed's
  `feedHealth` share of published / junk and its `articles.summary` as a collapsed quote (or a note
  that there is none; an over-long card drops the quotes before it drops sources); the story cluster (outlets, who was first, reprints); the
  reactions, your 👍/👎 and the affinity of its franchise / type / subject / source; and which mutes
  already hit it. Buttons: 🔇 the franchise and 2–4 keyword candidates (in-category / global, the same
  DB mute as `/mute`) and 🔇 the event type in this category. Same users and chats as the commands;
  in a group with privacy mode on only `/inspect` reaches the bot, so prefer the private chat.
- **Raw feed browser** (on with `admin.commands`) —
  [`RawBrowser`](src/main/kotlin/metifikys/telegram/RawBrowser.kt): `/raw` (or `/raw <cat>`) opens a
  keyboard of categories → their configured feeds; a feed sends its 10 latest articles (insert order),
  one message each: title and link, `published → fetched`, the row's status in words (queued, Step 0
  mute, `new_feed` backlog, semantic duplicate of #id, processed), what Step 1 left behind (the
  `event_articles` event and its status, the `rejected_events` verdict with scores, the channel post
  of the event) and the stored text (`articles.description`) plus the AI summary as collapsed quotes.
- **Admin chat threads** (`admin.threads`) — with Threaded Mode on in @BotFather the private chat
  with the bot gets topics; `status` takes the status snapshot and everything the bot posts on its
  own (alerts, mute proposals, moderation), `inspect` the `/inspect` cards, `raw` the `/raw` browser.
  A command's own reply goes back to the thread it came from; `/thread` prints a thread's id.
- **CLI login health** (opt-in, `health.enabled`) —
  [`CliHealthMonitor`](src/main/kotlin/metifikys/ai/CliHealthMonitor.kt) gets the outcome of every
  `claude -p` / `codex exec` run, classified by the shared
  [`CliErrorClassifier`](src/main/kotlin/metifikys/ai/CliErrorClassifier.kt) (auth / limit / model /
  timeout / error). Entering auth, limit or model — and recovering — sends one message to the
  admin chat, even when a `fallback` provider served the call (an expired login used to be visible
  only in the logs). State lives in `bot_state`, so a restart neither repeats nor forgets an alert.
  The maintenance tick probes a CLI with no successful run in `cliProbe.intervalHours` (codex: the
  cheap `codex login status` first when available) and warns daily before a `claude setup-token`
  expires (`claudeTokenExpires`). `/status` shows `CLI: claude ✅ 2h · codex ⛔ auth з 03:10`.
- **Feed quality metrics** — every article records the configured feed that brought it
  (`articles.feed_url`), its first-ingest time (`fetched_at`) and a Step 0 mute (`drop_reason`);
  a link a second feed also brought lands in `feed_sightings`. With `feedHealth.enabled` the
  fetcher keeps one outcome per feed per pass (a 304 is healthy; flushed to `feed_stats` on the
  maintenance tick) and `/status` gets a `📡 Feeds` section: a per-category `ok` count plus at most
  seven anomaly lines — 💀 dead, 💤 nothing new, 🗑 noisy (little published, mostly muted / Step 1
  rejected / never selected), 🪞 mirror (rarely the first feed with a published story). Pure logic
  in [`FeedQuality`](src/main/kotlin/metifikys/feeds/FeedQuality.kt); suggestions only, nothing is
  disabled automatically. The full per-feed table (published %, junk %, first %, echo lag, fetch
  errors) is the "Per-feed quality" section of `bench/db-audit/report.mjs`. A junk-classifier score
  column (`articles.junk_score`) is averaged per feed once it exists.
- **Feed candidates** (opt-in, `feedHealth.candidates`) — weekly on the maintenance tick,
  [`FeedCandidateFinder`](src/main/kotlin/metifikys/feeds/FeedCandidateFinder.kt) ranks the domains
  the channel published from without a configured feed on them (they came through reddit, hnrss
  or the converter) and runs feed autodiscovery on the best few
  ([`FeedDiscovery`](src/main/kotlin/metifikys/feeds/FeedDiscovery.kt): `<link rel=alternate>`,
  then `/feed`, `/rss`, …; public hosts only, redirects re-validated, per-run request cap). Results
  land in `feed_candidates` and the log, and the static report lists them under "Кандидати" with
  how many of the found feed's links other feeds already bring. Log-only: `feeds:` is never changed.
- **Story clusters** (opt-in, `storyIntel.enabled`) — the dedup layers record which articles they
  folded into which event in `event_articles`: Step 1 (every extraction, joined to its article by
  `url` — a url outside the batch is dropped — and the shortlist's `articleIndices`), Layer 2 (a
  hard-rejected late reprint joins its anchor article's event, with the cosine) and Layer 3.5 (a
  dropped copy's articles move under the covered event). The article → event mapping is resolved
  once in [`EventMembership`](src/main/kotlin/metifikys/story/EventMembership.kt), which also fills
  the shortlist's in-memory `sourceLinks`; [`EventCluster`](src/main/kotlin/metifikys/story/EventCluster.kt)
  derives outlets (distinct domains) and publish times. Recording only; the story lines read it.
  Every delivered event is also appended to `event_history` (first appearance only, kept
  `storyIntel.historyRetentionDays`, 3 years), which the first start warms up from
  `covered_events` / `rejected_events` duplicates.
- **Per-LLM-call cost ledger** — `llm_calls` table records `(provider, model, category,
  useCase, promptTokens, completionTokens, estCostUsd, ts)` for every call, including the
  Batch API auto-halving.

### Operational ergonomics
- **Configurable via YAML** with environment overrides for every secret
  (`TELEGRAM_BOT_TOKEN`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`).
- **Schema validation at load time** — invalid `hardThreshold` ranges, missing provider
  blocks for `summarize: anthropic`, unsupported `provider: openrouter` on the Batch API,
  path-traversal in `database.path`, etc. all fail fast with explicit messages.
- **Strict config keys + `--check-config`** — an unknown key anywhere (feed entries included)
  fails the load with its full path and the keys accepted there (`Unknown config key
  'categories.gaming.post.hashtag' — keys accepted here: badges, hashtags, … (did you mean
  'hashtags'?)`). At startup one INFO line per option registered in
  [`ConfigChangelog`](src/main/kotlin/metifikys/config/ConfigChangelog.kt) that the file does not
  set yet (`[Config] new option since 2026-09-23 not set: categories.*.post.longForm — …`).
  `java -jar RssNewsBot.jar --check-config config.yaml [--against config.example.yaml]` runs the
  full load, validation and prompt check without starting, prints the key-path diff (never
  values) and exits 0/1; `deploy/restart.sh` runs it before installing a new jar.
- **Prompt validation at startup** —
  [`PromptValidator`](src/main/kotlin/metifikys/ai/dedup/PromptValidator.kt) checks every prompt
  the config will use. The bot refuses to start (listing every finding) when a `dedup.promptFile`
  is missing / unparseable / has `..`, any of the four prompt keys does not resolve (formerly a
  silent fallback to the legacy single-step flow), extract lacks `{{CURRENT_BATCH_JSON}}`,
  `render.user` lacks `{{SHORTLIST_JSON}}` or the weekly prompts lack `{{CANDIDATES_JSON}}`.
  Warnings (no `{{PREVIOUSLY_COVERED_EVENTS_JSON}}`, unknown `{{X}}` sent literally, any
  placeholder in `render.system`, `{{AUDIENCE_SIGNALS}}` with feedback off, `post.longForm` with no
  `depth` in the render prompt, trackers with no `eventDate` in extract) go to the log and as one
  message to `admin.statusChatId`. A prompt file edited on the running server is re-checked when it
  is reloaded; errors then go to the log, `/status` and the admin chat.
- **Dependency locking** — `gradle.lockfile` pins all transitive versions to defend against
  silent supply-chain shifts.
- **Auto-migration** — schema changes via Exposed `createMissingTablesAndColumns`; legacy
  `articles.processed = 1` rows are migrated to the `status` column on first boot.

---

## End-to-end pipeline

One category's path, top to bottom. In per-category mode each category runs this on its own
scheduler thread; with `fetcher.intervalMinutes` set, the upper half (fetch → insert →
SemanticDedupDetector) ticks on the ingest timer and the lower half (CategoryProcessor onward)
on the digest timer. The trailing cleanup and status post run on the separate maintenance tick.

```
config.yaml ──► AppConfig (env overrides + validation)
                        │
                        ▼
       ┌────────────  DigestCycle ────────────┐
       │                                       │
       ▼                                       │
RssFetcher (Rome + SSRF guard + retry)         │
       │                                       │
       ▼                                       │
Pre-link-dedup ─► findExistingLinks() ─────────┤  Layer 1
       │                                       │
       ▼                                       │
ArticleFetcher (Jsoup, optional full-page)     │
       │                                       │
       ▼                                       │
ArticleSummarizer (per-feed `summarize:`)      │
       │                                       │
       ▼                                       │
NewsDatabase.insertArticles (insertIgnore)     │
       │                                       │
       ▼                                       │
SemanticDedupDetector ─► [HIT] / [REJECT] ─────┤  Layer 2
       │                                       │
       ▼                                       │
CategoryProcessor.process(byCategory) ─────────┤
       │                                       │
       ├─ pre-cycle gates: minArticles, batch backpressure
       │                                       │
       ▼                                       │
EventExtractor (Step 1, JSON mode) ────────────┤  Layer 3
       │                                       │
       ├─ URL whitelist filter ────────────────┤
       ├─ meaningful_update cooldown ──────────┤  Layer 4
       ├─ ShortlistRanker.rank ────────────────┤  Layer 4
       │                                       │
       ▼                                       │
EventSemanticAnalyzer ─► [HIT] (log-only) ─────┤  Layer 3.5
       │                                       │
       ▼                                       │
PromptBuilder ─► LlmClient (Batch or sync)     │
       │                                       │
       ▼                                       │
DigestDeliverer ─► TopicFormatter ─► Telegram  │
       │                                       │
       ▼                                       │
status reconciliation + CoveredEvent persist   │
       │                                       │
       ▼                                       │
 ─ ─ maintenance tick (every scheduler.intervalMinutes) ─ ─
retention cleanup ─► affinity rebuild ─► StatusPoster.post() (admin chat)
```

---

## The deduplication stack

The bot is built around the assumption that **the same story will be filed by many
different sources**, and that the LLM should only ever see a clean shortlist. There are four
independent dedup layers, applied in this order:

### Layer 1 — Link dedup (cheapest, exact match)

- `articles.link` carries a SQLite `UNIQUE INDEX`.
- Before enrichment, `NewsDatabase.findExistingLinks(...)` filters out already-stored URLs so
  full-article fetching and per-article summarization never run for duplicates.
- `insertArticles` uses `insertIgnore`, so a race with a parallel feed is still safe.

### Layer 2 — Semantic vector dedup (catches paraphrased / re-syndicated stories)

Implemented in [`SemanticDedupDetector`](src/main/kotlin/metifikys/digest/SemanticDedupDetector.kt)
and [`VectorMath`](src/main/kotlin/metifikys/digest/VectorMath.kt).

After articles are inserted, for each category that has `semanticDedup.enabled: true`:

1. Build the embedding text: `title + (summary | description)`, capped at 6000 chars.
2. Call OpenAI `/v1/embeddings` (`text-embedding-3-small` by default) — batched per
   category — and L2-normalize the vector so cosine ≡ dot product.
3. Persist into `article_embeddings(article_id, model, dim, vector, created_at)` as
   little-endian Float32 bytes (`dim * 4` bytes per row).
4. Pull recent neighbours: rows in the same category with `pubDate ≥ now - windowDays`,
   capped at `maxRecent`, excluding the just-inserted ids.
5. Brute-force cosine; sort descending; take `topK`.
6. Emit one log line per article:
   - `[SemanticDedup][HIT]` when `top_sim ≥ threshold`
   - `[SemanticDedup][REJECT]` when `top_sim ≥ hardThreshold` **and** the neighbour's status
     is already `PROCESSED` (was sent in a previous digest)
   - `[SemanticDedup]` otherwise

The **hard filter** is opt-in (`hardThreshold` null by default). When it fires it calls
`db.markDuplicate(articleId, canonicalId)` which:

- sets `articles.status = DUPLICATE`
- writes `articles.duplicate_of = <canonical row id>` (soft pointer, no FK constraint —
  the canonical row may be pruned by `deleteOlderThan` later)
- **only updates rows whose current status is neither `PROCESSED` nor `PROCESSING`** — the
  detector cannot demote articles the digest pipeline has already claimed

Why "only against PROCESSED neighbours"? Matches against `UNPROCESSED` / `PROCESSING`
neighbours are deliberately *not* rejected here — the Step 1 event extractor still owns
within-batch dedup of unsent articles, and we don't want the embedding detector pre-empting
that decision.

**Schema-level invariant:** `hardThreshold ≥ threshold` is validated at config load — the
hard filter is a strict superset of what gets logged.

**Cost:** ~$0.02 / 1M input tokens. At ~200 tokens/article, ~$0.000004/article — cheaper
than a single line of Step 1 prompt context.

**Why opt-in?** It's designed as a **stat-collection phase first**: run with logs only,
inspect `[HIT]` lines to calibrate `threshold` and `hardThreshold` for your corpus
(suggested production values in `config.example.yaml`: gaming/tech ≈ 0.85, politics ≈ 0.78),
*then* turn on the hard filter.

### Layer 3 — LLM event extraction (semantic, structured)

Implemented in [`EventExtractor`](src/main/kotlin/metifikys/ai/dedup/EventExtractor.kt) +
[`PromptLoader`](src/main/kotlin/metifikys/ai/dedup/PromptLoader.kt).

When a category has a `dedup:` block, the bot runs **Step 1** before submitting Step 2 (the
digest renderer). Step 1 calls an OpenAI-compatible chat.completions endpoint in **JSON
mode** with:

- `{{CURRENT_BATCH_JSON}}` — serialized 0-indexed input articles (title, url, description,
  pubDate).
- `{{PREVIOUSLY_COVERED_EVENTS_JSON}}` — structured events already covered in the
  `covered_events` table within `dedup.contextDays`, capped at `maxContextEvents`.

The LLM returns:

```json
{
  "extractions": [
    {
      "event_key": "...",
      "subject": "...",
      "franchise": "...",
      "event_type": "release_date|delay|major_announcement|…",
      "core_fact": "...",
      "importance": 0-10,
      "newsworthiness": 0-10,
      "digest_fit": 0-10,
      "url": "...",
      "status": "new|meaningful_update|duplicate|rejected",
      "related_previous_event_key": "...",
      "article_index": 0,
      "eventDate": "2026-10-14 | 2026-10 | 2027-Q1 | 2027 | null"
    }
  ],
  "shortlist": [ /* subset of extractions, with status in {new, meaningful_update} */ ]
}
```

What happens with the result:

- **`rejected` / `duplicate`** rows are saved to `rejected_events` for prompt-tuning
  retrospectives (pruned after 30 days).
- **`new` / `meaningful_update`** rows flow into the shortlist.
- **URL hallucination filter** — any shortlist item whose `url` is not in the current
  article set is dropped with a warning. (Defends against prompt-injection payloads
  smuggled in RSS titles or descriptions.)
- **Empty shortlist** → all input articles are marked `PROCESSED` and the cycle skips Step 2.

**Provider split.** `llm.extract` may be a weighted `providers:` split (see §4); a 50/50 split is
the old A/B pair. Without an `extract` override the default is a built-in 50/50 split of
`openai` and the OpenRouter `models:` ladder when an `openrouter:` block exists, else `openai`
alone. A batch submitted by a `batch: true` entry is polled back through the entry matching its
batch id (`msgbatch_…` = Anthropic) — polling does not count as a pick.

### Layer 4 — Editorial ranker + meaningful-update cooldown

Implemented in [`ShortlistRanker`](src/main/kotlin/metifikys/ai/dedup/ShortlistRanker.kt)
and the cooldown helper inside `EventExtractor.applyMeaningfulUpdateCooldown`.

Applied **after Step 1's URL filter, before Step 2 render**. Driven by `dedup.digest.*`:

**Meaningful-update cooldown** (run first, removes flapping):
- For every `status: meaningful_update` item, look up its `related_previous_event_key` in the
  covered-events memory.
- If the previous coverage was less than `meaningfulUpdateCooldownMinutes` ago **and** the
  follow-up's `newsworthiness < newsworthinessOverride`, drop it.
- Strong updates (`newsworthiness ≥ newsworthinessOverride`, e.g. a release date confirmation
  after a teaser) bypass the cooldown.

**Floor filter:**
- `digestFit < minDigestFit` drops the item, **unless** `newsworthiness ≥
  newsworthinessOverride` rescues it.

**Composite scoring & priority tiebreaks:**
- `score = newsworthinessWeight * newsworthiness + (1 - newsworthinessWeight) * digestFit`.
- Ties broken by **priority event types** in this order: `major_announcement`,
  `release_date`, `platform_policy`, `industry_news`, `major_dlc_expansion`, `delay`,
  `cancellation`, `lawsuit`, `acquisition`, `studio_closure`.
- Final tiebreak: `newsworthiness` descending.

**Diversity caps (subject is narrower than franchise, so it wins on overlap):**
- `maxPerSubject` — typically 1 per specific game title.
- `maxPerFranchise` — typically 2 per franchise.

**Hard size clamp:** `maxDigestItems` — anything beyond is dropped with reason
`maxDigestItems=N reached`.

**Weak-shortlist hold-back** (in [`CategoryProcessor`](src/main/kotlin/metifikys/digest/CategoryProcessor.kt)):
- If post-rank shortlist size `< minStrongItems`, skip publishing this cycle and mark the
  articles `PROCESSED` (we've already seen them — no point reprocessing).
- **Unless** the last digest was more than `maxWaitHours` ago, in which case fall back to
  `minItemsOnForcePublish` as the floor (so quiet days still get a digest eventually).

All drops are logged as `[Category:X][Ranker] drop <eventKey>: <reason>` for QA.

### Layer 3.5 — Event-level semantic dedup (log + optional hard filter)

Implemented in [`EventSemanticAnalyzer`](src/main/kotlin/metifikys/digest/EventSemanticAnalyzer.kt).
The **event-level counterpart of Layer 2's article-level `SemanticDedupDetector`** — it embeds
Step 1's *structured events* instead of raw articles.

For each category whose `semanticDedup.eventEnabled: true`, once the finalized shortlist is
about to be rendered (the single chokepoint in `CategoryProcessor.submitOrSync`, so the sync,
extract-batch, and startup-resume routes all pass through it):

1. Embed each shortlist event's `subject + coreFact`, keyed by `event_key`, via the same
   OpenAI `/v1/embeddings` client (reusing `model` / `windowDays` / `topK` / `maxRecent`).
2. L2-normalize and brute-force cosine-scan recent **previously-covered** event vectors in the
   same category (`event_embeddings` table, candidate set excludes the current shortlist's own
   keys).
3. Log one line per event with `sameSubject`/`sameFranchise` diagnostic flags:
   `[EventSemanticDedup][HIT]` when `top_sim ≥ eventThreshold`, `[EventSemanticDedup]` otherwise.
4. Persist the kept event's vector so it becomes a candidate next cycle.

**Hard filter (`eventHardThreshold`, opt-in, null by default).** When set, a shortlist event
with `status == "new"` whose top covered-event cosine ≥ `eventHardThreshold` is **dropped from
the shortlist** before Step 2 renders — logged `[EventSemanticDedup][REJECT]`, not persisted (the
canonical event is already stored), and never reaches the digest. If *every* event is dropped, the
category's articles are marked `PROCESSED` and submission is skipped. `meaningful_update` items are
never dropped (they are intentional follow-ups). The filter is **fail-open**: a billing limit or
any embed/scan error returns the shortlist unchanged, so a transient failure can never empty a
digest. Validated `eventHardThreshold ≥ eventThreshold` at config load.

**Cosine-only, no subject gate — and per-category.** A week of production logs (1600 events, 221
hits at `eventThreshold=0.72`) showed ~97% precision and zero false positives ≥0.80, but also that
subject equality is category-brittle (gaming 65% same-subject among hits, tech only 7% — tech
subjects are free-text Ukrainian prose Step 1 rewrites each cycle). So the gate is pure cosine,
tuned per category via the per-category `semanticDedup` block (suggested ~0.80 for both tech and
gaming, where the boilerplate-collision false positives all sit below 0.78).

> Caveat: vectors are persisted for every shortlisted event regardless of Telegram delivery
> success, so the candidate set is "events we shortlisted", a close proxy for "previously
> covered". A stricter delivered-only persistence is a possible follow-up.

### Extra: URL whitelist after Step 2 ("prompt injection defence in depth")

After Step 2 renders the digest text, [`DigestDeliverer`](src/main/kotlin/metifikys/digest/DigestDeliverer.kt)
runs one more pass:

- Extract every `[label](url)` from the rendered digest.
- Drop entire topics whose URLs are not all present in the current article set
  (`foreignUrls = topicUrls - allArticleLinks`). This catches LLM hallucinations and prompt-
  injection payloads that survived earlier filters.
- Persist only filtered topics to the `summaries` table — never the raw LLM output —
  so the previous-digest URL set used for cross-cycle dedup can never be polluted.

---

## LLM routing & multi-provider support

Three providers, six use cases, all per-category overridable.

| Use case             | Default                                      | Overridable? | Batch-capable provider? |
|----------------------|----------------------------------------------|--------------|--------------------------|
| `extract`            | 50/50 OpenAI + OpenRouter ladder (else OpenAI) | yes        | OpenAI ✓, Anthropic ✓    |
| `render`             | OpenRouter when configured, else OpenAI      | yes          | n/a (sync)               |
| `batch`              | OpenAI (`openai.batchModel`)                 | yes          | OpenAI ✓, Anthropic ✓    |
| `summarize`          | per-feed `summarize:` provider               | yes          | n/a (sync)               |
| `batchFallback`      | none                                         | yes          | n/a (sync)               |

Any sync slot (`extract`, `render`, `summarize`, `batchFallback`, `persona`, `updateDiff`) may also
carry a nested `fallback: { provider, model }` — an on-failure secondary tried when the primary
call fails (usage limit, expired login, bad model, exhausted-retry timeout). Sync-only, may chain
(depth ≤ 3), and independent of the weighted split and the backpressure `batchFallback`.

**Weighted split.** Any slot (also `batch`, `shadow.*`, `factCheck.judge`) may instead list
several overrides with a share each:

```yaml
llm:
  summarize:
    providers:
      - { percent: 20, provider: openai,     model: gpt-5-mini-2025-08-07 }
      - { percent: 10, provider: anthropic,  model: claude-haiku-4-5-20251001 }
      - { percent: 70, provider: openrouter }          # no model = the openrouter.models ladder
```

Every call of the slot goes to the entry furthest below its share of today's calls (ties → the
earlier entry), so 20/10/70 gives exactly 2/1/7 per 10 calls, interleaved, no randomness. The
tally is per category + slot (`persona` / `updateDiff` count apart from `render` even when they
inherit its split), stored in `bot_state` under `llm_split:<category>/<slot>`, survives a
restart and starts over at local midnight. An entry is a full override — its own `batch`,
`fallback`, model. On `summarize` the feed's value wins: a feed with `summarize: auto` goes through the
category's `llm.summarize` (split or plain; none = the global sync default), a feed naming a
provider (`summarize: openrouter`) stays on it — the category override or the split's entry on
that provider only refines its model, without a pick. It replaces `extractAlternate`
(a config still carrying it is rejected with a hint).

Validation rules enforced at config load:

- `provider: openrouter` is rejected on `batch` / `extract.batch=true` (also inside a split)
  (OpenRouter has no compatible Batch API).
- `provider: anthropic` requires the top-level `anthropic:` block.
- `provider: openrouter` requires the top-level `openrouter:` block.
- `extractAlternate` is rejected — write `extract: { providers: [...] }` with two 50% entries.
- A `providers:` split: percents 1..100 summing to exactly 100, no provider/model/fallback of its
  own, no nested split, no provider+model listed twice; each entry is validated like a plain
  override. `percent` outside a split is rejected.
- A `fallback` must differ from its slot's primary and from every other link in the chain
  (cycles rejected), may not exceed depth 3, and may not set `batch: true`.
- `fallback` is rejected on the `batch` slot and on a `batch: true` extract
  leg — the async Batch API path has no sync fallback.
- `summarize: openrouter` / `summarize: anthropic` on a feed requires the matching
  top-level block.
- Every `pricing[]` entry must declare non-negative input/output.

---

## Batch API, backpressure, three-tier fallback

Step 2 and (optionally) Step 1 can run as async batches at 50 % of the sync price. Both
[OpenAI `/v1/batches`](src/main/kotlin/metifikys/ai/OpenAIBatch.kt) and
[Anthropic `/v1/messages/batches`](src/main/kotlin/metifikys/ai/AnthropicBatch.kt) are
implemented natively.

**Why backpressure?** A stuck OpenAI batch endpoint would otherwise block every cycle: each
new cycle adds another pending batch, work piles up, and digests stop flowing. So the
scheduler reads the per-category pending count and routes accordingly:

```
pending <  primaryMaxPending                            → primary batch (llm.batch)
primaryMaxPending ≤ pending < primary + secondary       → llm.batchFallback (sync alt LLM)
pending ≥ primary + secondary                            → sync render (llm.render)
```

When the category has no `batchFallback` configured, the secondary band collapses and the
sync cutoff drops to `primaryMaxPending` — preserving the original two-tier behaviour.

Set `primaryMaxPending: 0` to disable the primary render Batch API entirely: no primary
batch is ever submitted, so every render goes synchronously — via `llm.batchFallback` when
the category configures one, otherwise via the sync render client. (The Step-1 extract batch
is governed separately by whether the extract endpoint is batch-capable, not by this knob.)

Defaults from `config.example.yaml`:

- `primaryMaxPending: 2` (`0` disables batch)
- `secondaryMaxPending: 1`

Additional protections:

- **Volume gate** — when batching is already stuck, the cycle is skipped entirely for a
  category if `newArticles < 2 × processing.minArticles`. Tiny cycles don't justify
  burning the sync quota.
- **Sync-fallback cap** — synchronous render submissions are capped at 60 articles
  (`SYNC_FALLBACK_CAP` in `CategoryProcessor`).
- **Restart resume** — pending batches persist in `pending_batches` with their kind
  (`render` / `extract`), category, article links, and serialized shortlist JSON. On
  startup, `DigestCycle.resumePendingBatches()` polls each batch to completion and routes
  the result through the same code path as a fresh batch result.

---

## Article lifecycle & status state machine

```
                              ┌────────────────────────────┐
                              │      UNPROCESSED           │
                              │  (just inserted, or        │
                              │   reverted after failure)  │
                              └──────────────┬─────────────┘
                                             │
                       Step 1/2 submission   │
                                             ▼
                              ┌────────────────────────────┐
   stale > processing         │      PROCESSING            │
   .staleTimeoutHours         │  (claim with timestamp)    │
   ◄─────────────────────────┤                            │
   reclaimed                  └──────────────┬─────────────┘
                                             │
                       Telegram send OK      │  hard-filter REJECT
                                             ▼  (only against PROCESSED neighbour)
                              ┌────────────────────────────┐
                              │       PROCESSED            │  ─►  DUPLICATE
                              │  (terminal)                │      (duplicate_of set)
                              └────────────────────────────┘
```

- `markProcessing` does **not** downgrade `PROCESSED` rows (idempotent).
- `markUnprocessed` and `markDuplicate` similarly refuse to demote `PROCESSED`/`PROCESSING`.
- Stale `PROCESSING` rows older than `processing.staleTimeoutHours` are reclaimed by
  `fetchReadyForDigestByCategory` for the next cycle — protects against a worker that died
  mid-flight while the process kept running.
- At startup every `PROCESSING` row not owned by a pending batch job is returned to
  `UNPROCESSED` immediately (`reclaimOrphanedProcessing`), so a forced shutdown does not park
  a batch of articles for the whole stale window and then dump it into one oversized Step-1
  prompt.

---

## Telegram delivery & formatting

`TopicFormatter.applyStrictLayout` enforces a strict three-block topic shape:

```
{emoji} **{headline}.**

{body}

[{label}]({url})
```

When the LLM output doesn't match the canonical shape, a fallback regex isolates the
trailing `[label](url)` and bolts on the layout, so degenerate output is never made worse.
The `[джерело](...)` placeholder is replaced with the actual article title (truncated to 80
chars with ellipsis).

**Sending rules:**

- Per-topic: try Markdown parse mode first → on failure, strip markdown and try again.
- **Photo-and-caption mode** (`enableImages: true`): the first matched article's `imageUrl`
  is used as the photo; caption is the topic text truncated to Telegram's 1024-char
  hard limit. Bullets without a usable image fall back to plain text.
- 4096-char chunking for plain text; redirects are disabled to prevent bot-token leakage.
- **Rate limits:** every Bot API call waits for its chat's slot (`ChatPacer`: ≥ 1 s between
  calls to one chat, Telegram's per-chat guidance; different channels never wait on each
  other). A 429 puts that chat into a shared `retry_after` cooldown — every thread writing there
  waits it out — and the request is retried once. Each 429 is logged as `[TG429] <method> <chat>
  retry_after=N` and counted in `/status` ("Telegram 429 (24h)"). No global token bucket: the bot
  runs far below 30 msg/s; any `[TG429]` in prod logs is the trigger to add one.

**Partial-send reconciliation:**

- All topics sent → mark all linked articles `PROCESSED`, persist the filtered summary +
  the covered events from the shortlist.
- Some topics sent, some failed → mark links from successful topics `PROCESSED`, links
  exclusively in failed topics `UNPROCESSED` (success wins on overlap). Articles not
  referenced by any topic are treated as orphans and also marked `PROCESSED` (otherwise
  they'd loop forever).
- All topics failed → revert everything to `UNPROCESSED` for retry next cycle.

---

## Cost telemetry & the `/status` snapshot

[`MeteredLlmClient`](src/main/kotlin/metifikys/ai/MeteredLlmClient.kt) wraps every sync and
batch LLM call. It:

1. Reads token counts from the provider's usage block (estimated when absent).
2. Looks up the per-million-token price in `AppConfig.pricing[]`.
3. **Halves the cost for Batch API calls** automatically (`LlmPricing.priceFor(... batch =
   true)`).
4. Writes one row to `llm_calls(provider, model, category, useCase, promptTokens,
   completionTokens, estCostUsd, ts)`.

The `/status` admin snapshot ([`StatusCommand`](src/main/kotlin/metifikys/telegram/StatusCommand.kt))
emits:

```
📊 Status — 2026-05-28 12:34

tech 💻
  last digest: 12:01 (33 min)
  ready for next batch: 14
  pending: batch_abc123 started 11:58

gaming 🎮
  last digest: 11:45 (49 min)
  ready for next batch: 0
  pending: msgbatch_zzz started 11:46

💰 LLM cost (24h / 7d)
  total: $0.42 / $3.61
  openai · tech: $0.10 / $0.71 (32.4k in / 3.2k out, 12 calls)
  anthropic · gaming: $0.08 / $0.55 (18.9k in / 2.1k out, 7 calls)

⚠️ Recent errors
  12:11 · politics · [Dedup] · timeout after 60s
```

When `admin.statusChatId` is set, [`StatusPoster`](src/main/kotlin/metifikys/telegram/StatusPoster.kt)
posts this snapshot at the end of every cycle, **deleting the previous status message
first** so the admin chat always shows exactly one current snapshot. The `errorCommitToken`
mechanism ensures errors are cleared from `CycleErrorLog` only after they've successfully
been posted.

---

## Security & hardening

| Hardening                       | Where                                                          |
|---------------------------------|----------------------------------------------------------------|
| SSRF guard on every fetched URL + each redirect hop | `UrlGuard` (feeds, article pages, markdown.new, discovery, badges); private hosts only for configured feed hosts + `fetcher.privateHostAllowlist` |
| No-redirect HTTP to Telegram    | `TelegramSender` OkHttpClient builder                          |
| Path-traversal block on DB path | `ConfigLoader.applyEnvOverrides` (`..` rejected)               |
| `isAllowDoctypes = false`       | `RssFetcher` SyndFeedInput — blocks XXE                        |
| URL whitelist on LLM output     | `DigestDeliverer` foreign-URL filter                           |
| URL hallucination guard         | `EventExtractor.postProcess` Step 1 URL filter                  |
| Stripped markdown fallback      | `TelegramSender.sendToChannel` — never leaks raw markdown      |
| Bot token never logged          | `Anthropic*`, `OpenAI*` clients log model+baseUrl, not auth    |
| Schema validation               | `ConfigLoader` rejects misconfigurations at startup            |
| Dependency lock                 | `gradle.lockfile` + Gradle dep verification                    |

---

## Setup

### 1. Clone

```bash
git clone https://github.com/Metifikys/RssNewsBot.git
cd RssNewsBot
```

### 2. Prerequisites

- JDK 17+
- Gradle 8.x (the wrapper is not committed — see [Build](#3-build) below)
- Telegram bot from [@BotFather](https://t.me/BotFather)
- OpenAI API key (required)
- Optional: Anthropic and/or OpenRouter API keys

### 3. Configure

```bash
cp config.example.yaml config.yaml
```

Edit `config.yaml` with your tokens and feeds, or set the sensitive values as environment
variables (they override the file):

| Env var               | Required | Purpose                              |
|-----------------------|----------|--------------------------------------|
| `TELEGRAM_BOT_TOKEN`  | yes      | Telegram bot token                   |
| `OPENAI_API_KEY`      | yes      | OpenAI API key                       |
| `ANTHROPIC_API_KEY`   | no       | Used only if `anthropic:` block set  |
| `OPENROUTER_API_KEY`  | no       | Used only if `openrouter:` block set |
| `TELEGRAPH_TOKEN`     | no       | telegra.ph token for `post.longForm.telegraph` (else created once, kept in `bot_state`) |

`config.example.yaml` is a complete, working config: every block is present and active, every
optional feature ships with `enabled: false`, and values that would change behaviour (mute keys,
LAN hosts, admin chat ids, tokens) are neutral with the example in the trailing comment. A block
with `enabled: false` behaves exactly like an absent block, dependency checks included. A unit
test loads it through `--check-config`, so it cannot drift into an example that does not start.

### 4. Build

The Gradle wrapper is not checked in. First time, generate it (one-off):

```bash
gradle wrapper --gradle-version 8.10
```

Thereafter:

```bash
./gradlew shadowJar
```

Produces `build/libs/RssNewsBot-1.0-SNAPSHOT.jar` (fat JAR with all dependencies).

### 5. Run

```bash
java -jar build/libs/RssNewsBot-1.0-SNAPSHOT.jar
# or with a custom config path:
java -jar build/libs/RssNewsBot-1.0-SNAPSHOT.jar /path/to/config.yaml
# check a config (and its prompt files) without starting; exit 0 = would start:
java -jar build/libs/RssNewsBot-1.0-SNAPSHOT.jar --check-config /path/to/config.yaml [--against config.example.yaml]
```

The first cycle runs immediately on startup; subsequent cycles fire every
`scheduler.intervalMinutes`. Pending batches from a previous run are resumed before the
first cycle.

---

## Configuration reference

### Top-level blocks

| Block             | Required | Notes                                                       |
|-------------------|----------|-------------------------------------------------------------|
| `telegram`        | yes      | Bot token                                                   |
| `openai`          | yes      | API key + default sync/batch model                          |
| `openrouter`      | no       | When present, enables OpenRouter as alt sync + Step 1 "B"   |
| `anthropic`       | no       | When present, enables Anthropic for any LLM use case        |
| `claudeCli`       | no       | `claude -p` provider: `command`, `model`, `timeoutSeconds`  |
| `codexCli`        | no       | `codex exec` provider: same shape                           |
| `database`        | yes      | SQLite file path (no `..`, must be readable)                |
| `scheduler`       | yes      | `intervalMinutes` (default cadence + maintenance tick), `perCategory` (default `true`) |
| `fetcher`         | no       | `maxConcurrentFetches`, `maxAttempts`, `retryDelaySeconds`, `fetchDeadlineSeconds`, `intervalMinutes` (decoupled ingestion), `scrubRulesFile`, `pathScopedThrottleHosts` (per-route throttle for a converter host), `privateHostAllowlist` (extra LAN hosts the SSRF guard allows; feed hosts are automatic) |
| `processing`      | no       | Stale timeout, min articles, batch thresholds, retention    |
| `archive`         | no       | Copy-before-delete retention into `archive/news-archive-YYYY-MM.db` (off by default) |
| `summaryHistory`  | no       | How many previous digests to inject as context, retention   |
| `preferences`     | no       | Global `mute:` keyword blocklist                            |
| `feedback`        | no       | Reaction-feedback aggregation (off by default)              |
| `weekly`          | no       | Weekly top-story roundup (off by default); `weekly.barometer` adds the industry chart, `weekly.album` a photo album before it |
| `admin`           | no       | `statusChatId` enables the `/status` poster; `userIds` + `commands.enabled` turn on admin commands (needs `telegram.updatesPolling`), and with them the post inspector (`inspect.enabled`, default on) and `/raw`; `threads.status` / `inspect` / `raw` route them to topics of the admin chat |
| `health`          | no       | `claude` / `codex` login + usage-limit alerts in the admin chat, idle probe, `setup-token` expiry (off by default) |
| `feedHealth`      | no       | Per-feed fetch health + the `/status` 📡 Feeds anomalies: `enabled`, `windowDays` (14, max 30), `statusSection`, `thresholds`; `candidates` (candidate feeds + autodiscovery, own `enabled`) (off by default) |
| `storyIntel`      | no       | Record story clusters in `event_articles` and delivered events in `event_history`: `enabled`, `retentionDays` (180, ≥ `weekly.lookbackDays`), `historyRetentionDays` (1095), `domainAliases` (off by default; the story lines need it) |
| `telegraph`       | no       | telegra.ph account for `post.longForm.telegraph`: `accessToken` (env `TELEGRAPH_TOKEN`), `shortName`, `authorName`, `authorUrl`, `timeoutSeconds` |
| `pricing`         | no       | USD/1M tokens per (provider, model)                          |
| `factCheck`       | no       | 🧐 Log-only LLM judge of sent posts vs. their render sources: `enabled`, `sampleRate` (0.2), `categories`, `judge` (+ `fallback`), `promptFile` (`factCheck.system` / `.user`), `alert` (`windowDays` 7, `minChecks` 10, `maxUnsupportedRate` 0.15), `retentionDays` (90), `queueSize` (off by default) |
| `categories`      | yes      | Map of category name → `CategoryConfig`                     |

### `processing` knobs (all optional)

| Field                     | Default | Purpose                                          |
|---------------------------|---------|--------------------------------------------------|
| `staleTimeoutHours`       | 3       | Reclaim `PROCESSING` rows older than this        |
| `minArticles`             | 8       | Skip a category with fewer than N ready articles |
| `primaryMaxPending`       | 2       | Promote to `batchFallback` at this many pending; `0` disables primary batch |
| `secondaryMaxPending`     | 1       | Drop to sync render at primary + secondary       |
| `articleRetentionDays`    | 1500    | Delete article rows older than this (validated against weekly lookback / dedup window) |
| `llmCallRetentionDays`    | 30      | Prune the `llm_calls` ledger                     |
| `embeddingRetentionDays`  | 30      | Prune `article_embeddings`                       |
| `shadowRetentionDays`     | 30      | Prune `shadow_runs` (`llm.shadow`)               |
| `maxConcurrentCategories` | 12      | Legacy cycle mode only: fan-out pool size        |
| `categoryDeadlineMinutes` | 0       | Legacy cycle mode only: `0` waits for every category, `>0` interrupts stragglers |

### `archive` knobs (all optional; the block is absent = plain DELETE)

| Field           | Default     | Purpose |
|-----------------|-------------|---------|
| `enabled`       | false       | Master switch |
| `dir`           | `archive`   | Archive directory; relative = next to the database file (local disk) |
| `rotation`      | `monthly`   | One file per `monthly` | `quarterly` | `yearly` period of the row's own timestamp |
| `embeddings`    | `labelled`  | `article_embeddings`: `all`, `labelled` (article reached Step 1), `none` (delete unarchived) |
| `tables`        | all 8       | `article_embeddings`, `event_embeddings`, `rejected_events`, `covered_events`, `llm_calls`, `summaries`, `digest_messages`, `reaction_counts` |
| `keepFiles`     | 0           | Keep at most N archive files (oldest removed); `0` = never delete |
| `minFreeDiskMb` | 2048        | Below this free space: warn and plain DELETE without archiving |

### `CategoryConfig`

```yaml
emoji: "💻"
channelId: "@my_channel" # or "-1001234567890"
enableImages: false       # photo+caption mode
intervalMinutes: 15       # digest cadence (default: scheduler.intervalMinutes)
fetchIntervalMinutes: 10  # ingestion cadence (default: fetcher.intervalMinutes; unset = inside the digest run)
mute: [ "cod", "giveaway" ] # category-scoped keyword blocklist, merged with preferences.mute
feeds:                    # plain string OR object form
  - https://example.com/feed
  - url: https://other.com/feed
    fetchFullContent: true
    summarize: openai     # auto (= llm.summarize) | openai | openrouter | anthropic | claudecli | codexcli
    label: "Other News"   # name in the feed-health section / report (default: host + path)
  - url: http://rsshub.lan:1200/telegram/channel/studio
    kind: social          # article | social | release | video — converted / platform feeds
    minTextChars: 40      # skip shorter items
    repointLinks: true    # short post + one outbound link → ingest that article
  - url: https://github.com/owner/repo/releases.atom
    kind: release
    titlePrefix: "Repo "  # prepended unless the title already starts with it
    exclude: ["-rc", "nightly"]

systemPrompt: …           # legacy single-step prompt
userPrompt: …
summarizePrompt: …        # for feeds with summarize: set

llm:                      # per-use-case provider+model overrides
  extract:          { provider: …, model: …, batch: true|false }
                    # any slot instead: { providers: [ { percent: 70, provider: …, model: … }, … ] }  # weighted daily split
  render:           { provider: …, model: …, fallback: { provider: …, model: … } }  # nested on-failure fallback (sync slots)
  batch:            { provider: …, model: … }    # openai|anthropic only
  summarize:        { provider: …, model: … }
  batchFallback:    { provider: …, model: … }    # any provider
  persona:          { provider: …, model: … }    # post.persona remark (default: the render route)
  updateDiff:       { provider: …, model: … }    # dedup.digest.updateDiff check (default: the render route)
  shadow:           { enabled: true, extract: {…}, render: {…}, promptFile, sampleRate: 1.0, queueSize: 4 }  # 🌓 B next to A, stored only

dedup:                    # two-step pipeline opt-in
  promptFile: prompts/X.yaml
  prompts:                # inline override of any prompt
    extractSystem: …
    extractUser: …
    renderSystem: …
    renderUser: …
  contextDays: 7
  maxContextEvents: 200
  extractMaxArticles: 40  # newest-by-pubDate cap per Step-1 call (default 100); overflow waits
  extractMaxPromptChars: 80000 # char budget for the serialized article batch
  digest:                 # editorial ranker
    ranker:
      enabled: true
      newsworthinessWeight: 0.6
      reactionWeight: 0.15  # audience-affinity term (0 = off; needs feedback.enabled)
      reactionLogOnly: true # log the would-be diff instead of applying it
      maxAffinityBoost: 1.0
    minStrongItems: 4
    maxDigestItems: 6
    maxWaitHours: 4
    minItemsOnForcePublish: 2
    maxPerFranchise: 2
    maxPerSubject: 1
    minDigestFit: 3
    newsworthinessOverride: 8
    meaningfulUpdateCooldownMinutes: 90
    antiBubble:           # weekly 🧭 pick outside the audience's taste (needs feedback.enabled)
      intervalDays: 7
      minNewsworthiness: 8
      maxAffinity: -0.05
      label: "🧭 Поза звичним вибором"
    updateDiff:           # 🔁 an update goes out only with facts the earlier posts lack (start logOnly)
      logOnly: true
      maxPreviousPosts: 3
      windowDays: 14
      timeoutSeconds: 60
      maxSourceChars: 4000

semanticDedup:            # embedding detector opt-in
  enabled: true
  model: text-embedding-3-small
  windowDays: 14
  threshold: 0.92         # cosine ≥ this → [HIT] log
  topK: 5
  maxRecent: 2000
  hardThreshold: 0.85     # ≥ this AND neighbour PROCESSED → mark DUPLICATE
  eventEnabled: true      # Layer 3.5 event-level analyzer
  eventThreshold: 0.92    # cosine ≥ this → [EventSemanticDedup][HIT] log
  eventHardThreshold: 0.80 # cosine ≥ this AND status=="new" → drop event (REJECT); null=log-only
```

---

## Build, run, tests

```bash
./gradlew shadowJar            # fat JAR
./gradlew test                 # JUnit 5 + MockK
./gradlew jacocoTestReport     # coverage at build/reports/jacoco/
./gradlew dependencies --write-locks   # refresh gradle.lockfile
```

The test suite covers AI clients (OpenAI/Anthropic + their Batch APIs), config parsing &
validation, the database layer, semantic dedup (`SemanticDedupDetectorTest.kt` exercises hit
/ reject thresholds, hard-filter status gating, candidate scoring; `EventSemanticAnalyzerTest.kt`
covers the log-only event-level pass), vector math, RSS
fetching with SSRF rejection, Telegram sender, and end-to-end cycle behaviour with mocked
providers.

Property-based tests (`*PropertyTest.kt`, kotest-property) generate bullets with Cyrillic, astral
emoji, HTML specials and flaired / paren-URL links up to 6000 chars and check the formatter
invariants: Telegram-valid HTML, chunk and caption limits without split surrogates or half links,
layout idempotence, hashtag well-formedness. A failure prints its seed; rerun it locally with
`PropTestConfig(seed = …)`.

**Step 1 golden tests** (`Step1GoldenTest`, no network): each directory under
`src/test/resources/golden/step1/` holds an input (`case.json`: articles, covered context, digest
config, pinned `now`), a saved raw model answer (`response.json`) and the expected result
(`expected.json`: shortlist by URL with status / scores / `eventDate` / source links, drops with
their reason, rejected rows saved, legacy fallback). The answer is replayed through the real
`EventExtractor` — prompt building, URL filter, `eventDate` guard, cooldown, posts cap, ranker — and
an optional `render.txt` through the delivery's topic preparation (`expected-topics.json`).
`GOLDEN_UPDATE=1` rewrites the expected files from the current code (review the diff). Two manual
modes call a live model and are skipped in the suite: `GoldenRecordManualTest`
(`GOLDEN_RECORD=<provider>:<model>` or `dry`, `REPLAY_DB=<copy of news.db>`, `GOLDEN_CATEGORY`) builds
a case from one prod digest window; `GoldenRerecordManualTest` (`GOLDEN_RERECORD=<provider>:<model>`)
re-asks every recorded case with the current prompt files, writes `response.<model>.<date>.json`
next to the golden one and prints the shortlist Jaccard, status / score changes and new drops.

---

## Database schema

| Table                | Purpose                                                                  |
|----------------------|---------------------------------------------------------------------------|
| `articles`           | Canonical article rows + status (`UNPROCESSED`/`PROCESSING`/`PROCESSED`/`DUPLICATE`) + `duplicate_of` pointer + image/summary cache; `feed_url` (the configured feed that first brought the link), `fetched_at` (first-ingest time, our clock — unlike `pub_date`) and `drop_reason` (`mute` for Step 0 keyword drops) are null on rows older than the columns |
| `feed_sightings`     | `(link, feed_url, seen_at)`: a link a second feed brought after another feed already had it — the unique-link insert otherwise hides the echo. A feed re-reading its own links is not recorded |
| `feed_stats`         | Fetch health per feed url (`feedHealth.enabled`): last attempt / success, `fail_streak` (failed passes since the last success; 304 = success), totals, last error and entry count |
| `feed_candidates`    | Candidate source domains (`feedHealth.candidates`): published links in the window, discovery status (`unchecked` / `found` / `none` / `error`; `dismissed` by hand), found feed URL, entries, items per day, overlap with existing feeds |
| `event_history`      | Delivered events, append-only (`storyIntel.enabled`): one row per `(category, event_key)` at its first appearance — entity key (normalized subject), subject, franchise, type, core fact, `event_date`, newsworthiness, `covered_at`. Kept `storyIntel.historyRetentionDays` (1095); warmed up once from `covered_events` / `rejected_events` |
| `event_articles`     | Story clusters (`storyIntel.enabled`): one row per `(category, article_id)` — the event key the dedup put the article in, link, domain, `pub_date`, `seen_at` (= `fetched_at`), `role` (`step1` / `layer2` / `layer35`), Step 1 status, Layer 2 cosine. Latest decision wins; kept `storyIntel.retentionDays` |
| `pending_batches`    | In-flight batch jobs (`kind`, `category_names`, `article_links`, `shortlist_json`) for restart-safe resume |
| `summaries`          | Delivered digest text + article count; injected as context next cycle and used for previous-URL filtering |
| `covered_events`     | Step 1 dedup memory: one row per `(category, event_key)`; upsert keeps subject/franchise/eventType stable |
| `rejected_events`    | Step 1's rejected/duplicate extractions kept 30 days for prompt-tuning retrospectives |
| `llm_calls`          | Per-call token + USD ledger feeding the `/status` cost panel             |
| `article_embeddings` | One vector per article (Float32 little-endian blob, `dim * 4` bytes)     |
| `event_embeddings`   | One vector per `(category, event_key)` for the log-only event-level analyzer (Float32 LE blob) |
| `digest_messages`    | One row per delivered Telegram message linking `(chat_id, message_id)` → category + `event_key` + article links, for reaction attribution; `variant` tags an experiment arm (`explore` / `persona` / `control`, null otherwise) |
| `reaction_counts`    | Current aggregated reaction counts per `(chat_id, message_id, emoji)` from `message_reaction_count` updates (replace-all per message) |
| `bot_state`          | Key/value store for restart-surviving runtime state (the Telegram `getUpdates` offset, tracker message ids + body hashes, `/pause` state as `pause:<category>`) |
| `audience_affinity`  | Reaction-feedback scores per `(category, dimension, key)` with `n_tone` evidence counts, rebuilt on the maintenance tick |
| `badge_cache`        | Rendered footer badges per `(provider, key)` with a per-provider TTL; `""` caches a negative lookup; also `ogimage` (a post page's og:image, `post.images`) and the shared Steam lookups (`steam_app` title → appid, `steam_appdetails` narrowed card) |
| `entity_intros`      | First-mention memory for the Wikipedia intro: one row per `(category, entity key)`, hits and misses |
| `tracker_entries`    | Living-tracker state per `(category, tracker, entity)`: event date, previous date, status, latest post |
| `admin_mutes`        | Mutes added from Telegram (`/mute`, suggestions, moderation, the inspector): `kind = keyword` merged over the YAML mute lists (`category` null = global), `kind = event_type` a Step 1 event type muted in one category |
| `admin_feedback`     | Explicit 👍/👎/🗑 per post from the moderation buttons (last press wins), with the post's category, event and send time; kept apart from `reaction_counts`, which every reaction update replaces |
| `fact_checks`        | `factCheck` verdicts: per judged post its category, `chat_id` / `message_id`, event key, post text (no footer), source chars, `verdict` (`supported` / `minor` / `unsupported`), the judge's `claims_json`, judge provider / model, `checked_at`; kept `factCheck.retentionDays` (90) |
| `shadow_runs`        | `llm.shadow` pairs: per Step 1 batch or render, A's and B's provider / model / prompt hash, the post-processed output of each (Step 1 shortlist + drops, or the prepared topics; B's raw answer when it did not parse), their links, B's error and latency; kept `processing.shadowRetentionDays` |
| `mute_suggestions`   | Mute proposals sent to the admin chat with their evidence, 30-day hit counts, message id and decision (`pending`/`approved`/`rejected`) |

Pruning runs on the maintenance tick (or at the start of a legacy cycle), skipped while any
batch is pending:

- `deleteOlderThan(processing.articleRetentionDays)` — article rows (default 1500 days, long
  retention for dedup pointers); `feed_sightings` by `seen_at` on the same window.
- `deleteOldSummaries(retentionDays)` — `summaryHistory.retentionDays`;
  `pruneOldCoveredEvents` keeps `max(summaryHistory.retentionDays, feedback.lookbackDays)`.
- `deleteOldRejectedEvents(30)` — rejected events log.
- `deleteOldBatches(2)` — completed/failed batch records.
- `deleteOldDigestMessages(90)` / `deleteOldReactionCounts(90)` — reaction-tracking retention.
- `deleteOldLlmCalls(llmCallRetentionDays)` / `pruneOldEmbeddings(embeddingRetentionDays)`.
- `deleteOldShadowRuns(shadowRetentionDays)` — `llm.shadow` pairs (30 days); `deleteOldFactChecks(factCheck.retentionDays)` (90).
- `deleteOldBadgeCache(7)` / `deleteOldEntityIntros(730)` / `deleteOldTrackerEntries(365)` — footer
  badge cache, first-mention memory, tracker rows not touched by any digest.

With `archive.enabled`, every listed table is copied first: rows are partitioned by the month of
their timestamp, `INSERT OR IGNORE`-ed into `archive/news-archive-<period>.db` (same ids, plus a
snapshot of the referenced `articles` rows) and deleted from `news.db` in the same batch. A crash
between the two commits leaves a row in both files and the rerun skips it by primary key. An
archiving error keeps the rows in `news.db` until the next tick (and shows in `/status`).

---

## Project layout

```
src/main/kotlin/metifikys/
├── ai/             OpenAI / Anthropic / OpenRouter clients, batch APIs,
│                   embedder, metered LLM wrapper, pricing, prompt builder
│   └── dedup/      Event extractor, shortlist ranker, prompt loader
├── config/         YAML loader (Jackson), schema validation, env overrides
├── db/             NewsDatabase (SQLite + Exposed): articles, batches,
│                   summaries, covered/rejected events, llm_calls, embeddings
├── digest/         CategoryProcessor, DigestCycle, SemanticDedupDetector,
│                   DigestDeliverer, VectorMath, CycleErrorLog
├── feedback/       Reaction valence, affinity math + aggregator, ranker term
├── fetch/          RssFetcher (Rome + queue), UrlGuard (SSRF), ArticleFetcher (Jsoup),
│                   ArticleSummarizer (per-article LLM), HostThrottle,
│                   RedditLinkExtractor, LinkNormalizer, HtmlText
├── enrich/         Footer badges (Steam / HN / GitHub providers, resolver, cache),
│                   Wikipedia first-mention intro
├── format/         TopicFormatter (Telegram message formatting), TopicDecorator
│                   (footer lines under a bullet), Hashtags (entity-key normalizer)
├── model/          Article, ArticleStatus, CategoryInput, Dedup DTOs
├── telegram/       TelegramSender, StatusCommand, StatusPoster, updates poller
├── Main.kt         Entry point (reads config path, builds NewsBot)
└── NewsBot.kt      Composition root + per-category / maintenance schedulers

bench/              Free-LLM benchmark harness + DB audits (Node, no deps)
deploy/             systemd unit, backup / vacuum / restart scripts
```

---

## Dependencies

| Library                  | Version       | Purpose                                     |
|--------------------------|---------------|---------------------------------------------|
| Kotlin (JVM)             | 2.0.21        | Language + serialization plugin             |
| JVM toolchain            | 17            | `kotlin { jvmToolchain(17) }`               |
| OkHttp                   | 4.12.0        | HTTP client (Telegram, OpenAI, Anthropic)   |
| Jsoup                    | 1.18.1        | HTML parsing for full-article extraction    |
| Rome + rome-modules      | 1.15.0        | RSS/Atom feed parsing (+ Media RSS)         |
| Exposed                  | 0.56.0        | SQLite ORM (core, dao, jdbc, java-time)     |
| sqlite-jdbc              | 3.41.2.2      | SQLite driver                               |
| kotlinx-serialization    | 1.6.3         | JSON for LLM and Telegram request bodies    |
| Jackson YAML + Kotlin    | 2.16.1        | Config file parsing                         |
| Logback + kotlin-logging | 1.5.6 / 7.0.3 | Structured logging                          |
| JUnit Jupiter            | 5.10.2        | Test runner                                 |
| MockK                    | 1.13.10       | Kotlin-friendly mocking                     |
| kotest-property          | 5.9.1         | Property-based tests (`checkAll` inside JUnit 5 tests) |
| Shadow plugin            | 8.3.6         | Fat-JAR packaging                           |
| JaCoCo                   | 0.8.11        | Coverage reports                            |
