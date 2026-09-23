package metifikys.telegraph

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import metifikys.config.TelegraphConfig
import metifikys.db.NewsDatabase
import metifikys.db.TelegraphPageRow
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * Publishes long-form pages under the bot's telegra.ph account. The token is
 * [TelegraphConfig.accessToken] (`TELEGRAPH_TOKEN` already folded in by the config loader), else
 * `bot_state["telegraph:token"]`, else a `createAccount` made once and stored there. The token
 * is never logged. Every page is recorded in `telegraph_pages`.
 *
 * Fail-soft: [publish] returns null on any failure — the caller then sends the long post as it
 * would without Telegraph.
 */
class TelegraphPublisher(
    private val config: TelegraphConfig,
    private val db: NewsDatabase,
    private val client: TelegraphClient = TelegraphClient(config.timeoutSeconds)
) {

    /** The page URL, or null when Telegraph could not publish it. */
    fun publish(category: String, eventKey: String, title: String, content: JsonArray, authorUrl: String?): String? {
        val author = config.authorUrl.ifBlank { authorUrl.orEmpty() }.ifBlank { null }
        return try {
            val (token, stored) = token(author)
            val page = try {
                client.createPage(token, title, config.authorName, author, content)
            } catch (e: TelegraphClient.TelegraphException) {
                // A stored token that stopped working is dropped, so the next page makes a new account.
                if (stored && e.error == "ACCESS_TOKEN_INVALID") forgetStoredToken()
                throw e
            }
            recordPage(category, eventKey, page)
            logger.info { "[Category:$category][LongForm][Telegraph] $eventKey: published ${page.url}" }
            page.url
        } catch (e: Exception) {
            logger.warn { "[Category:$category][LongForm][Telegraph] $eventKey: page not published (${e.javaClass.simpleName}: ${e.message}) — sending the long post without it" }
            null
        }
    }

    /** The token and whether it came from `bot_state`; creates the account on first use. */
    @Synchronized
    private fun token(authorUrl: String?): Pair<String, Boolean> {
        if (config.accessToken.isNotBlank()) return config.accessToken to false
        db.getState(STATE_KEY)?.takeIf { it.isNotBlank() }?.let { return it to true }
        val created = client.createAccount(config.shortName, config.authorName, authorUrl)
        db.setState(STATE_KEY, created)
        logger.info { "[Telegraph] created account '${config.shortName}' — token stored in bot_state ($STATE_KEY)" }
        return created to true
    }

    @Synchronized
    private fun forgetStoredToken() {
        runCatching { db.deleteState(STATE_KEY) }
        logger.warn { "[Telegraph] stored token rejected (ACCESS_TOKEN_INVALID) — a new account is created next time" }
    }

    private fun recordPage(category: String, eventKey: String, page: TelegraphClient.Page) {
        try {
            db.insertTelegraphPage(TelegraphPageRow(category, eventKey, page.path, page.url, LocalDateTime.now()))
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][LongForm][Telegraph] $eventKey: page ${page.url} not recorded" }
        }
    }

    companion object {
        const val STATE_KEY = "telegraph:token"
    }
}
