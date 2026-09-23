package metifikys.ai.dedup.golden

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Step 1 golden tests: every case under `src/test/resources/golden/step1/` replays its saved model
 * output (`response.json`) through the real [metifikys.ai.dedup.EventExtractor] — no network — and
 * compares the result with `expected.json`; a case with `render.txt` also checks the Step 2 topic
 * preparation against `expected-topics.json`.
 *
 * Approve mode: `GOLDEN_UPDATE=1` (env of the run configuration) writes the expected files from the
 * current code instead of comparing — review the diff before committing. Without it a missing
 * expected file fails the case. Recording and re-recording against a live model are the manual
 * [GoldenRecordManualTest] / [GoldenRerecordManualTest], never part of the suite.
 */
class Step1GoldenTest {

    private val update = System.getenv("GOLDEN_UPDATE") == "1"

    @Test
    fun `the golden set is not empty`() {
        assertTrue(GoldenIo.caseDirs().size >= 10, "expected at least 10 golden cases in ${GoldenIo.root.absolutePath}")
    }

    @TestFactory
    fun step1(): List<DynamicTest> = GoldenIo.caseDirs().map { dir ->
        DynamicTest.dynamicTest(dir.name) { checkStep1(dir) }
    }

    @TestFactory
    fun step2Topics(): List<DynamicTest> = GoldenIo.caseDirs().filter { File(it, GoldenIo.RENDER).isFile }.map { dir ->
        DynamicTest.dynamicTest(dir.name) { checkTopics(dir) }
    }

    private fun checkStep1(dir: File) {
        val case = GoldenIo.readCase(dir)
        val response = File(dir, GoldenIo.RESPONSE).readText()
        val run = GoldenRunner.run(case, GoldenRunner.replayClient(response))

        // The prompt carried the whole batch — a regression in prompt building shows up here too.
        val user = assertNotNull(run.userPrompt, "${dir.name}: Step 1 was never called")
        for (a in case.articles) assertTrue(a.link in user, "${dir.name}: ${a.link} missing from the Step 1 prompt")

        val expectedFile = File(dir, GoldenIo.EXPECTED)
        if (update) {
            GoldenIo.write(expectedFile, run.expected)
            return
        }
        val expected = GoldenIo.readExpected(dir)
            ?: fail("${dir.name}: no ${GoldenIo.EXPECTED} — run with GOLDEN_UPDATE=1 and review the written file")
        assertEquals(expected, run.expected, "${dir.name}: Step 1 result differs from ${GoldenIo.EXPECTED}")
    }

    private fun checkTopics(dir: File) {
        val case = GoldenIo.readCase(dir)
        val actual = GoldenRunner.topics(case, File(dir, GoldenIo.RENDER).readText())
        val expectedFile = File(dir, GoldenIo.EXPECTED_TOPICS)
        if (update) {
            GoldenIo.write(expectedFile, actual)
            return
        }
        val expected = GoldenIo.readTopics(dir)
            ?: fail("${dir.name}: no ${GoldenIo.EXPECTED_TOPICS} — run with GOLDEN_UPDATE=1 and review the written file")
        assertEquals(expected, actual, "${dir.name}: prepared topics differ from ${GoldenIo.EXPECTED_TOPICS}")
    }
}
