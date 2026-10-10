package metifikys.ai.dedup.golden

import io.mockk.mockk
import metifikys.ai.LlmClient
import metifikys.ai.LlmClientsFactory
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.CategoryLlmOverrides
import metifikys.config.LlmOverride
import metifikys.db.NewsDatabase
import java.util.Locale

/** Live-model helpers of the manual record / re-record modes, and their diff report. */
object GoldenLive {

    /**
     * The Step 1 client of [categoryName] with `llm.extract` pinned to [spec] (`<provider>:<model>`,
     * e.g. `codexcli:gpt-5.5`), built by the production factory (no metering, sync only).
     */
    fun client(config: AppConfig, categoryName: String, spec: String): LlmClient {
        val provider = spec.substringBefore(':').trim()
        val model = spec.substringAfter(':', "").trim()
        require(provider.isNotBlank()) { "expected <provider>:<model>, got '$spec'" }
        val cat = config.categories[categoryName] ?: error("category '$categoryName' is not in the config")
        val pinned: CategoryConfig = cat.copy(
            llm = (cat.llm ?: CategoryLlmOverrides()).copy(extract = LlmOverride(provider, model))
        )
        val patched = config.copy(categories = config.categories + (categoryName to pinned))
        return LlmClientsFactory(patched, mockk<NewsDatabase>(relaxed = true)).forExtract(pinned)
    }

    /** `gpt-5.5` → `gpt-5.5`, `minimax/minimax-m3:free` → `minimax_minimax-m3_free`: safe in a file name. */
    fun fileSafe(model: String): String = model.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "default" }
}

/** The re-record report: how a new response's Step 1 result differs from the golden one. Prints only. */
object GoldenDiff {

    fun report(caseId: String, old: GoldenExpected, new: GoldenExpected, promptChanged: Boolean): String {
        val sb = StringBuilder("== $caseId ==\n")
        if (promptChanged) sb.append("  prompt changed since record\n")
        if (old.fallbackToLegacy != new.fallbackToLegacy) {
            sb.append("  fallbackToLegacy: ${old.fallbackToLegacy} -> ${new.fallbackToLegacy}\n")
        }
        val oldByUrl = old.shortlist.associateBy { it.url }
        val newByUrl = new.shortlist.associateBy { it.url }
        sb.append(String.format(Locale.ROOT, "  shortlist Jaccard %.2f (golden %d, new %d)\n",
            jaccard(oldByUrl.keys, newByUrl.keys), oldByUrl.size, newByUrl.size))
        (newByUrl.keys - oldByUrl.keys).forEach { sb.append("  + $it\n") }
        (oldByUrl.keys - newByUrl.keys).forEach { sb.append("  - $it\n") }
        for (url in oldByUrl.keys intersect newByUrl.keys) {
            val a = oldByUrl.getValue(url)
            val b = newByUrl.getValue(url)
            val changes = buildList {
                if (a.status != b.status) add("status ${a.status}->${b.status}")
                if (a.newsworthiness != b.newsworthiness) add("nw ${a.newsworthiness}->${b.newsworthiness}")
                if (a.digestFit != b.digestFit) add("fit ${a.digestFit}->${b.digestFit}")
                if (a.eventDate != b.eventDate) add("date ${a.eventDate}->${b.eventDate}")
            }
            if (changes.isNotEmpty()) sb.append("  ~ $url: ${changes.joinToString()}\n")
        }
        val oldDrops = old.drops.toSet()
        (new.drops.filter { it !in oldDrops }).forEach { sb.append("  new drop ${it.reason}: ${it.url}\n") }
        if (old.rejectedSaved != new.rejectedSaved) sb.append("  rejected/duplicate: ${old.rejectedSaved} -> ${new.rejectedSaved}\n")
        return sb.toString()
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        val union = (a union b).size
        return if (union == 0) 1.0 else (a intersect b).size.toDouble() / union
    }
}
