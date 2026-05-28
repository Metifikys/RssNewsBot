package metifikys.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortlistSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `round trip ExtractionResult with mixed statuses`() {
        val original = ExtractionResult(
            extractions = listOf(
                ExtractionItem(
                    articleIndex = 0,
                    eventKey = "k1",
                    subject = "s",
                    franchise = "f",
                    eventType = "release",
                    coreFact = "fact1",
                    importance = 9,
                    url = "https://a/1",
                    status = "new"
                ),
                ExtractionItem(
                    articleIndex = 1,
                    eventKey = "k1",
                    coreFact = "fact1 restated",
                    url = "https://a/2",
                    status = "duplicate"
                ),
                ExtractionItem(
                    articleIndex = 2,
                    eventKey = "k2",
                    coreFact = "fact2",
                    url = "https://b/1",
                    status = "meaningful_update"
                ),
                ExtractionItem(
                    articleIndex = 3,
                    eventKey = "k3",
                    coreFact = "junk",
                    url = "https://c/1",
                    status = "rejected"
                )
            ),
            shortlist = listOf(
                ShortlistItem(
                    eventKey = "k1",
                    coreFact = "fact1",
                    url = "https://a/1",
                    status = "new",
                    articleIndices = listOf(0, 1)
                ),
                ShortlistItem(
                    eventKey = "k2",
                    coreFact = "fact2",
                    url = "https://b/1",
                    status = "meaningful_update",
                    articleIndices = listOf(2)
                )
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ExtractionResult>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `decode tolerates unknown top level keys`() {
        val raw = """
        {
          "extractions": [],
          "shortlist": [],
          "surprise": {"deep": "should-be-ignored"}
        }
        """.trimIndent()
        val decoded = json.decodeFromString<ExtractionResult>(raw)
        assertTrue(decoded.extractions.isEmpty())
        assertTrue(decoded.shortlist.isEmpty())
    }

    @Test
    fun `decode tolerates unknown keys inside shortlist items`() {
        val raw = """
        {
          "extractions": [],
          "shortlist": [
            {"eventKey":"k","coreFact":"x","url":"u","status":"new","mystery":123}
          ]
        }
        """.trimIndent()
        val decoded = json.decodeFromString<ExtractionResult>(raw)
        assertEquals(1, decoded.shortlist.size)
        assertEquals("k", decoded.shortlist[0].eventKey)
    }

    @Test
    fun `shortlist items with defaults decode minimally`() {
        val raw = """
        {
          "extractions": [],
          "shortlist": [
            {"eventKey":"k","coreFact":"x","url":"u","status":"new"}
          ]
        }
        """.trimIndent()
        val decoded = json.decodeFromString<ExtractionResult>(raw)
        val item = decoded.shortlist.single()
        assertEquals("", item.subject)
        assertEquals("", item.franchise)
        assertEquals(0, item.importance)
        assertTrue(item.articleIndices.isEmpty())
    }
}
