package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.config.PostImagesConfig
import metifikys.db.NewsDatabase
import metifikys.enrich.BadgeCache
import metifikys.enrich.DbBadgeCache
import metifikys.fetch.ArticleFetcher
import metifikys.fetch.ImageCandidate
import metifikys.fetch.ImagePicker
import metifikys.fetch.ImageProbe
import metifikys.fetch.UrlGuard
import metifikys.log.RunContext
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * The picture of a post at delivery (`post.images`, see [PostImagesConfig]): the post article's
 * `og:image` first, then the stored feed images of the topic and of the event's cluster, each
 * judged by [ImagePicker] and, with `probe`, sized from its first bytes when the size is unknown.
 * Network lookups run on a worker under the per-lookup timeout and one deadline per digest
 * ([newDeadline]); a lookup that times out or fails just drops that candidate.
 */
class PostImages(
    /** The page's og:image with its declared size ([metifikys.fetch.ArticleFetcher.fetchPreviewImage]). */
    private val ogImage: (String) -> ImageCandidate?,
    /** Pixel size from the first bytes ([metifikys.fetch.ImageProbe.dimensions]). */
    private val probe: (String) -> Pair<Int, Int>?,
    /** og:image results by page URL (`badge_cache`, provider [CACHE_PROVIDER]); null = no cache. */
    private val cache: BadgeCache? = null
) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "post-image").also { it.isDaemon = true } }

    fun newDeadline(cfg: PostImagesConfig): Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(cfg.maxTotalSeconds)

    /**
     * The first acceptable picture for a post whose article is [postUrl], among [feedImages] (the
     * stored images in priority order: the topic's articles, then — the caller's `fromCluster` —
     * the cluster's) and, with `ogFirst`, [postUrl]'s og:image ahead of them. Null → text post.
     */
    fun choose(
        label: String,
        postUrl: String?,
        feedImages: List<String>,
        cfg: PostImagesConfig,
        deadlineNanos: Long
    ): String? {
        val picker = ImagePicker(cfg.minWidth, cfg.ignore)
        val candidates = buildList {
            if (cfg.ogFirst && postUrl != null) ogImageOf(label, postUrl, cfg, deadlineNanos)?.let { add(it) }
            feedImages.distinct().forEach { add(ImageCandidate(it)) }
        }.distinctBy { it.url }
        for (c in candidates) {
            val reason = picker.rejectReason(c)
            if (reason != null) {
                logger.info { "$label[Image] rejected ${c.url}: $reason" }
                continue
            }
            if (cfg.probe && c.width == null) {
                val size = timed(label, "probe", cfg, deadlineNanos) { probe(c.url) }
                if (size != null) {
                    val sized = c.copy(width = size.first, height = size.second)
                    val sizedReason = picker.rejectReason(sized)
                    if (sizedReason != null) {
                        logger.info { "$label[Image] rejected ${c.url}: $sizedReason (probed)" }
                        continue
                    }
                }
            }
            return c.url
        }
        if (candidates.isNotEmpty()) logger.info { "$label[Image] no acceptable picture among ${candidates.size} — text post" }
        return null
    }

    private fun ogImageOf(label: String, postUrl: String, cfg: PostImagesConfig, deadlineNanos: Long): ImageCandidate? {
        cache?.get(CACHE_PROVIDER, postUrl, CACHE_MAX_AGE)?.let { return decode(it) }
        val found = timed(label, "og:image", cfg, deadlineNanos) { ogImage(postUrl) }
        if (found != null) {
            logger.debug { "$label[Image] og:image of $postUrl: ${found.url} (${found.width}x${found.height})" }
            cache?.put(CACHE_PROVIDER, postUrl, encode(found))
        }
        return found
    }

    private fun <T> timed(label: String, what: String, cfg: PostImagesConfig, deadlineNanos: Long, block: () -> T?): T? {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remainingMs <= 0) {
            logger.debug { "$label[Image] budget exhausted — skipping $what" }
            return null
        }
        val future = pool.submit(RunContext.wrap(Callable { block() }))
        val waitMs = minOf(TimeUnit.SECONDS.toMillis(cfg.timeoutSeconds), remainingMs)
        return try {
            future.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            logger.warn { "$label[Image] $what timed out after ${waitMs}ms" }
            null
        } catch (e: ExecutionException) {
            logger.warn { "$label[Image] $what failed: ${e.cause?.message ?: e.message}" }
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            null
        }
    }

    companion object {
        /** Ingest-time pickers for the categories with `post.images` (the RSS fetcher's `imagePickers`). */
        fun ingestPickers(config: AppConfig): Map<String, ImagePicker> =
            config.categories.mapNotNull { (name, c) -> c.post?.images?.let { name to ImagePicker(it.minWidth, it.ignore) } }.toMap()

        /** The delivery-side chooser, or null when no category with `enableImages` sets `post.images`. */
        fun forConfig(config: AppConfig, db: NewsDatabase, articleFetcher: ArticleFetcher): PostImages? {
            val configs = config.categories.values.filter { it.enableImages }.mapNotNull { it.post?.images }
            if (configs.isEmpty()) return null
            val probe = ImageProbe(UrlGuard.forConfig(config), timeoutSeconds = configs.maxOf { it.timeoutSeconds })
            return PostImages(articleFetcher::fetchPreviewImage, probe::dimensions, DbBadgeCache(db))
        }

        const val CACHE_PROVIDER = "ogimage"
        private val CACHE_MAX_AGE: Duration = Duration.ofDays(7)

        internal fun encode(c: ImageCandidate): String = listOf(c.url, c.width?.toString().orEmpty(), c.height?.toString().orEmpty()).joinToString("\t")

        internal fun decode(s: String): ImageCandidate? {
            val parts = s.split('\t')
            val url = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            return ImageCandidate(url, parts.getOrNull(1)?.toIntOrNull(), parts.getOrNull(2)?.toIntOrNull())
        }
    }
}
