package metifikys.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShadowRunsTest {

    private fun row(createdAt: LocalDateTime, bError: String? = null) = ShadowRunRow(
        category = "tech", stage = ShadowRunRow.STAGE_EXTRACT, createdAt = createdAt, inputHash = "in",
        aProvider = "codexcli", aModel = "gpt-a", aPromptHash = "pa", bProvider = "openrouter", bModel = "m-b", bPromptHash = "pb",
        aOutput = """{"shortlist":[]}""", bOutput = if (bError == null) "{}" else null, bError = bError, bDurationMs = 1234,
        aUrls = listOf("https://a.example/1", "https://a.example/2"), bUrls = listOf("https://a.example/2")
    )

    @Test
    fun `insert, fetch since and prune`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("shadow.db").toString())
        val now = LocalDateTime.now()
        db.insertShadowRun(row(now.minusDays(40)))
        db.insertShadowRun(row(now.minusHours(2), bError = "timeout"))
        db.insertShadowRun(row(now.minusMinutes(5)))

        val recent = db.fetchShadowRuns(now.minusDays(1))
        assertEquals(2, recent.size)
        assertEquals("timeout", recent[0].bError)
        assertNull(recent[0].bOutput)
        assertEquals(listOf("https://a.example/1", "https://a.example/2"), recent[1].aUrls)
        assertEquals(listOf("https://a.example/2"), recent[1].bUrls)
        assertEquals(1234L, recent[1].bDurationMs)

        db.deleteOldShadowRuns(30)
        assertEquals(2, db.fetchShadowRuns(now.minusYears(1)).size)
    }
}
