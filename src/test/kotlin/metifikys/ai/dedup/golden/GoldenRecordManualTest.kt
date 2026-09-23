package metifikys.ai.dedup.golden

import metifikys.config.ConfigLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Manual recorder, not part of the suite (it calls a live model): builds a golden case from one
 * production digest window of a DB snapshot and records the model's Step 1 answer to it.
 *
 * Env:
 * - `GOLDEN_RECORD=<provider>:<model>` (e.g. `codexcli:gpt-5.5`), or `dry` — write `case.json` only,
 *   no LLM call, to look at the input first;
 * - `REPLAY_DB=<path>` — a COPY of the prod `news.db`; opened read-only;
 * - `GOLDEN_CATEGORY=<name>` — a category of `GOLDEN_CONFIG` (default `config.yaml`) with `dedup:`;
 * - optional `GOLDEN_AT` (ISO local time; default = the category's latest digest), `GOLDEN_WINDOW_HOURS`
 *   (default 6), `GOLDEN_MAX_ARTICLES` (default 30), `GOLDEN_CASE` (directory name).
 *
 * The case holds the window's articles (descriptions cut to 600 chars) and the `covered_events`
 * that existed before `GOLDEN_AT` — approximate, since the upsert rewrites `covered_at`. The prompt
 * comes from the category's current prompt file; its hash goes into the case so a re-record can
 * say the prompt changed. Review the written `expected.json` before committing the case.
 */
class GoldenRecordManualTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "GOLDEN_RECORD", matches = ".+")
    fun `record a golden Step 1 case from a prod snapshot`() {
        val spec = System.getenv("GOLDEN_RECORD")
        val dbPath = requireNotNull(System.getenv("REPLAY_DB")) { "REPLAY_DB=<copy of news.db> is required" }
        val category = requireNotNull(System.getenv("GOLDEN_CATEGORY")) { "GOLDEN_CATEGORY is required" }
        val config = ConfigLoader.load(System.getenv("GOLDEN_CONFIG") ?: "config.yaml")
        val cat = requireNotNull(config.categories[category]) { "category '$category' is not in the config" }
        val dedup = requireNotNull(cat.dedup) { "category '$category' has no dedup: block" }
        val windowHours = System.getenv("GOLDEN_WINDOW_HOURS")?.toLong() ?: 6
        val maxArticles = System.getenv("GOLDEN_MAX_ARTICLES")?.toInt() ?: 30

        val case = readOnly(dbPath).use { conn ->
            val at = System.getenv("GOLDEN_AT")?.let { LocalDateTime.parse(it) } ?: latestDigest(conn, category)
            val articles = conn.prepareStatement(
                "SELECT title, link, description, pub_date, summary FROM articles " +
                    "WHERE category = ? AND pub_date > ? AND pub_date <= ? ORDER BY pub_date DESC LIMIT ?"
            ).use { st ->
                st.setString(1, category)
                st.setString(2, sql(at.minusHours(windowHours)))
                st.setString(3, sql(at))
                st.setInt(4, maxArticles)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            GoldenArticle(
                                title = rs.getString(1),
                                link = rs.getString(2),
                                description = rs.getString(3).take(600),
                                pubDate = iso(rs.getString(4)),
                                summary = rs.getString(5)?.take(600)
                            )
                        )
                    }
                }
            }.reversed()
            val covered = conn.prepareStatement(
                "SELECT event_key, subject, franchise, event_type, core_fact, importance, newsworthiness, digest_fit, url, covered_at " +
                    "FROM covered_events WHERE category = ? AND covered_at < ? AND covered_at >= ? ORDER BY covered_at DESC LIMIT ?"
            ).use { st ->
                st.setString(1, category)
                st.setString(2, sql(at))
                st.setString(3, sql(at.minusDays(dedup.contextDays)))
                st.setInt(4, dedup.maxContextEvents)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            GoldenCovered(
                                eventKey = rs.getString(1), subject = rs.getString(2), franchise = rs.getString(3),
                                eventType = rs.getString(4), coreFact = rs.getString(5), importance = rs.getInt(6),
                                newsworthiness = rs.getInt(7), digestFit = rs.getInt(8), url = rs.getString(9),
                                coveredAt = iso(rs.getString(10))
                            )
                        )
                    }
                }
            }
            require(articles.isNotEmpty()) { "no $category articles in the $windowHours h before $at" }
            val promptFile = dedup.promptFile
            GoldenCase(
                category = category,
                now = at.toString(),
                description = "recorded from the $category digest window ending $at",
                recordedWith = if (spec == "dry") "dry" else spec,
                promptFile = promptFile,
                promptSha = promptFile?.let { File(it) }?.takeIf { it.isFile }?.let { GoldenIo.sha(it) },
                emoji = cat.emoji,
                digest = dedup.digest?.let { GoldenIo.mapper.valueToTree(it) },
                articles = articles,
                covered = covered
            )
        }

        val id = System.getenv("GOLDEN_CASE") ?: "$category-${case.nowTime.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}"
        val dir = File(GoldenIo.root, id)
        GoldenIo.write(File(dir, GoldenIo.CASE), case)
        println("case: ${dir.path} (${case.articles.size} articles, ${case.covered.size} covered)")
        if (spec == "dry") return

        val run = GoldenRunner.run(
            case, GoldenLive.client(config, category, spec),
            prompts = dedup.prompts, promptFile = dedup.promptFile
        )
        File(dir, GoldenIo.RESPONSE).writeText(requireNotNull(run.rawResponse) { "the model was never called" })
        GoldenIo.write(File(dir, GoldenIo.EXPECTED), run.expected)
        println("recorded ${run.expected.shortlist.size} shortlisted, ${run.expected.drops.size} dropped — review ${GoldenIo.EXPECTED}")
    }

    private fun readOnly(path: String): Connection =
        SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:$path")

    private fun latestDigest(conn: Connection, category: String): LocalDateTime =
        conn.prepareStatement("SELECT MAX(created_at) FROM summaries WHERE category = ?").use { st ->
            st.setString(1, category)
            st.executeQuery().use { rs ->
                rs.next()
                LocalDateTime.parse(iso(requireNotNull(rs.getString(1)) { "no digest of $category in the snapshot" }))
            }
        }

    /** SQLite's `2026-09-23 06:19:44.798` → `2026-09-23T06:19:44.798`. */
    private fun iso(s: String): String = s.trim().replace(' ', 'T')

    private fun sql(t: LocalDateTime): String = t.toString().replace('T', ' ')
}
