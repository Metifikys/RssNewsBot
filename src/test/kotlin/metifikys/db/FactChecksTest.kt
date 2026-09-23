package metifikys.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.assertEquals

class FactChecksTest {

    private fun row(checkedAt: LocalDateTime, verdict: String) = FactCheckRow(
        category = "tech", chatId = -100L, messageId = 7, eventKey = "k", postText = "**Post.**\n\n[Джерело](https://a.example/1)",
        sourceChars = 1200, verdict = verdict, claimsJson = """[{"claim":"x","status":"unsupported","note":"y"}]""",
        judgeProvider = "openrouter", judgeModel = "m", checkedAt = checkedAt
    )

    @Test
    fun `insert, fetch since and prune`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("fc.db").toString())
        val now = LocalDateTime.now()
        db.insertFactCheck(row(now.minusDays(100), FactCheckRow.SUPPORTED))
        db.insertFactCheck(row(now.minusDays(2), FactCheckRow.UNSUPPORTED))

        val recent = db.fetchFactChecks(now.minusDays(7))
        assertEquals(1, recent.size)
        assertEquals(FactCheckRow.UNSUPPORTED, recent.single().verdict)
        assertEquals("k", recent.single().eventKey)
        assertEquals(1200, recent.single().sourceChars)

        db.deleteOldFactChecks(90)
        assertEquals(1, db.fetchFactChecks(now.minusYears(1)).size)
    }
}
