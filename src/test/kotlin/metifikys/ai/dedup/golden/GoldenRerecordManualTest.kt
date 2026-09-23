package metifikys.ai.dedup.golden

import metifikys.config.ConfigLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.LocalDate

/**
 * Manual re-record, not part of the suite (it calls a live model): after a prompt change, asks
 * `GOLDEN_RERECORD=<provider>:<model>` for a fresh answer to every recorded golden case, built with
 * the category's CURRENT prompt file from `GOLDEN_CONFIG` (default `config.yaml`), and prints how
 * the Step 1 result moved against the golden one (Jaccard of the shortlist URLs, status and score
 * changes, new drops, "prompt changed since record"). Asserts nothing.
 *
 * The answer lands next to the original as `response.<model>.<yyyy-MM-dd>.json`; the golden
 * `response.json` is never overwritten. To accept the new answer: rename it to `response.json` and
 * run [Step1GoldenTest] with `GOLDEN_UPDATE=1`.
 *
 * `GOLDEN_CASES` narrows the run: comma-separated directory names, or `all` to include the
 * synthetic edge cases (skipped by default — their inputs are invented around one response).
 */
class GoldenRerecordManualTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "GOLDEN_RERECORD", matches = ".+")
    fun `re-record golden Step 1 cases and print the diff`() {
        val spec = System.getenv("GOLDEN_RERECORD")
        val model = spec.substringAfter(':', "")
        val config = ConfigLoader.load(System.getenv("GOLDEN_CONFIG") ?: "config.yaml")
        val filter = System.getenv("GOLDEN_CASES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val out = StringBuilder()

        for (dir in GoldenIo.caseDirs()) {
            val case = GoldenIo.readCase(dir)
            val selected = when {
                filter == listOf("all") -> true
                filter.isNotEmpty() -> dir.name in filter
                else -> case.recordedWith != "synthetic"
            }
            if (!selected) continue
            val dedup = config.categories[case.category]?.dedup
            if (dedup == null) {
                out.append("== ${dir.name} ==\n  skipped: category '${case.category}' has no dedup: block in the config\n")
                continue
            }
            val golden = GoldenIo.readExpected(dir)
                ?: GoldenRunner.run(case, GoldenRunner.replayClient(File(dir, GoldenIo.RESPONSE).readText())).expected
            val run = try {
                GoldenRunner.run(case, GoldenLive.client(config, case.category, spec), prompts = dedup.prompts, promptFile = dedup.promptFile)
            } catch (e: Exception) {
                out.append("== ${dir.name} ==\n  model call failed: ${e.message}\n")
                continue
            }
            run.rawResponse?.let {
                File(dir, "response.${GoldenLive.fileSafe(model)}.${LocalDate.now()}.json").writeText(it)
            }
            val currentSha = dedup.promptFile?.let { File(it) }?.takeIf { it.isFile }?.let { GoldenIo.sha(it) }
            val promptChanged = case.promptSha != null && currentSha != null && case.promptSha != currentSha
            out.append(GoldenDiff.report(dir.name, golden, run.expected, promptChanged))
        }
        println(out.ifEmpty { "no golden cases selected" })
    }
}
