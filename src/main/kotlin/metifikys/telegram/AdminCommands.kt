package metifikys.telegram

import metifikys.config.AppConfig
import metifikys.db.NewsDatabase
import metifikys.digest.MuteRegistry
import metifikys.digest.PauseStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Outcome of queueing a manual `/run`. */
enum class RunQueued { QUEUED, NOT_PER_CATEGORY, NOT_STARTED }

/** Hooks of one manual run: [started] when it leaves the queue, [finished] when it is done. */
interface RunListener {
    /** Log run id the run uses (MDC `runId`), shown in the replies; null = one is generated. */
    val runId: String? get() = null
    fun started()
    fun finished()
}

/** Queues a manual category run on that category's own scheduler thread (see `NewsBot`). */
fun interface ManualRunner {
    fun enqueue(category: String, withFetch: Boolean, listener: RunListener): RunQueued
}

/**
 * The admin command set (`/status`, `/pause`, `/resume`, `/run`, `/mute`, `/unmute`, `/mutes`),
 * registered on an [AdminCommandRouter]. Handlers run on the `admin-commands` thread and answer
 * with plain-text replies; `/run` only queues the run and reports back from the category thread
 * once it is done.
 */
class AdminCommands(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val mutes: MuteRegistry,
    private val pauses: PauseStore,
    private val status: StatusCommand,
    private val sender: TelegramSender,
    /** Null = `/run` answers "unsupported". */
    private val runner: ManualRunner?,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    fun registerAll(router: AdminCommandRouter) {
        router.register("status", "/status", "знімок стану зараз") { _, ctx -> status(ctx) }
        router.register("pause", "/pause <cat> [6h|2d]", "призупинити дайджест категорії") { cmd, ctx -> pause(cmd.args, ctx) }
        router.register("resume", "/resume <cat>", "зняти паузу") { cmd, ctx -> resume(cmd.args, ctx) }
        router.register("run", "/run <cat> [fetch]", "прогнати дайджест зараз") { cmd, ctx -> runNow(cmd.args, ctx) }
        router.register("mute", "/mute <слово|\"фраза\"> [cat]", "заглушити слово") { cmd, ctx -> mute(cmd.args, ctx) }
        router.register("unmute", "/unmute <слово> [cat]", "зняти mute, доданий ботом") { cmd, ctx -> unmute(cmd.args, ctx) }
        router.register("mutes", "/mutes [yaml]", "усі mute і їхні джерела") { cmd, ctx -> listMutes(cmd.args, ctx) }
    }

    private fun status(ctx: AdminCommandRouter.Context) {
        if (sender.sendMessageReturningId(ctx.chatId, status.build()) == null) ctx.reply("⚠️ не вдалося надіслати знімок")
    }

    /** The category named by [arg], or null after telling the admin which ones exist. */
    private fun category(arg: String?, ctx: AdminCommandRouter.Context, usage: String): String? {
        if (arg == null) {
            ctx.reply("Використання: $usage\nКатегорії: ${config.categories.keys.joinToString(", ")}")
            return null
        }
        if (arg !in config.categories) {
            ctx.reply("Невідома категорія «$arg». Є: ${config.categories.keys.joinToString(", ")}")
            return null
        }
        return arg
    }

    private fun pause(args: List<String>, ctx: AdminCommandRouter.Context) {
        val cat = category(args.getOrNull(0), ctx, "/pause <cat> [6h|2d]") ?: return
        val durationArg = args.getOrNull(1)
        val until = if (durationArg == null) null else {
            val d = PauseStore.parseDuration(durationArg) ?: run {
                ctx.reply("Не розумію тривалість «$durationArg». Приклади: 30m, 6h, 2d, 1w.")
                return
            }
            clock().plus(d)
        }
        pauses.pause(cat, until)
        val untilText = until?.let { "до ${it.format(stamp)}" } ?: "до /resume"
        ctx.reply("⏸ $cat: дайджест на паузі $untilText. Статті збираються далі; /resume $cat — зняти, /run $cat — прогнати попри паузу.")
    }

    private fun resume(args: List<String>, ctx: AdminCommandRouter.Context) {
        val cat = category(args.getOrNull(0), ctx, "/resume <cat>") ?: return
        val wasPaused = pauses.resume(cat)
        val backlog = db.fetchReadyForDigest(cat, config.processing.staleTimeoutHours).size
        val sb = StringBuilder(if (wasPaused) "▶️ $cat: пауза знята." else "ℹ️ $cat не був на паузі.")
        sb.append(" У черзі на дайджест: $backlog стат.")
        if (backlog > BACKLOG_WARN) {
            val cap = config.categories[cat]?.dedup?.extractMaxArticles
            sb.append("\n⚠️ Великий беклог: наступний Step 1 візьме")
            sb.append(if (cap != null) " не більше $cap (extractMaxArticles)," else "")
            sb.append(" решта піде наступними прогонами.")
        }
        ctx.reply(sb.toString())
    }

    private fun runNow(args: List<String>, ctx: AdminCommandRouter.Context) {
        val cat = category(args.getOrNull(0), ctx, "/run <cat> [fetch]") ?: return
        val withFetch = args.getOrNull(1)?.equals("fetch", ignoreCase = true) == true
        val r = runner ?: run {
            ctx.reply("ℹ️ /run не підтримується в цьому режимі.")
            return
        }
        val runId = "$cat-cmd-${ctx.updateId}"
        val listener = object : RunListener {
            override val runId: String = runId
            @Volatile var startedAt: LocalDateTime = clock()
            @Volatile var pendingBefore = 0

            override fun started() {
                startedAt = clock()
                pendingBefore = runCatching { db.countPendingBatchesForCategory(cat) }.getOrDefault(0)
            }

            override fun finished() {
                val posts = runCatching { db.countDigestPostsSince(cat, startedAt) }.getOrDefault(0)
                val pendingAfter = runCatching { db.countPendingBatchesForCategory(cat) }.getOrDefault(0)
                ctx.reply(
                    when {
                        posts > 0 -> "✅ $cat: $posts пост(ів)"
                        pendingAfter > pendingBefore -> "📦 $cat: пішло в Batch API, пости прийдуть з результатом"
                        else -> "ℹ️ $cat: нічого не опубліковано (мало статей, hold-back або все відсіяно) — деталі в лозі, runId=$runId"
                    }
                )
            }
        }
        when (r.enqueue(cat, withFetch, listener)) {
            RunQueued.QUEUED -> ctx.reply("▶️ $cat: у черзі" + (if (withFetch) " (спершу збір статей)" else "") + ", runId=$runId")
            RunQueued.NOT_PER_CATEGORY -> ctx.reply("ℹ️ /run працює лише з scheduler.perCategory: true")
            RunQueued.NOT_STARTED -> ctx.reply("ℹ️ планувальник ще не запущений — спробуй за хвилину")
        }
    }

    private fun mute(args: List<String>, ctx: AdminCommandRouter.Context) {
        val (keyword, scope) = parseMuteArgs(args, config.categories.keys) ?: run {
            ctx.reply("Використання: /mute <слово|\"фраза\"> [cat]\nОстанній аргумент, що збігається з категорією, — область; фразу з таким словом у кінці бери в лапки.")
            return
        }
        when (val res = mutes.add(keyword, scope, ctx.fromId, SOURCE_COMMAND)) {
            is MuteRegistry.AddResult.Added -> {
                val p = mutes.preview(keyword, scope, PREVIEW_DAYS)
                ctx.reply(
                    "🔇 «$keyword» (${scope ?: "глобально"}): за $PREVIEW_DAYS днів збігів ${p.articles}, " +
                        "з них у дайджесті ${p.published}. Діє з наступного дайджесту."
                )
            }
            is MuteRegistry.AddResult.AlreadyMuted -> ctx.reply("Вже є: ${describe(res.existing)}")
            is MuteRegistry.AddResult.Invalid -> ctx.reply("✖ «$keyword»: ${res.reason}")
        }
    }

    private fun unmute(args: List<String>, ctx: AdminCommandRouter.Context) {
        val (keyword, scope) = parseMuteArgs(args, config.categories.keys) ?: run {
            ctx.reply("Використання: /unmute <слово|\"фраза\"> [cat]")
            return
        }
        when (val res = mutes.remove(keyword, scope, ctx.fromId)) {
            is MuteRegistry.RemoveResult.Removed -> ctx.reply("🔊 «$keyword» (${scope ?: "глобально"}) знято.")
            is MuteRegistry.RemoveResult.InConfig -> {
                val where = res.entry.category?.let { "categories.$it.mute" } ?: "preferences.mute"
                ctx.reply("«${res.entry.keyword}» задано в config.yaml ($where) — прибери там і перезапусти бота.")
            }
            MuteRegistry.RemoveResult.NotFound -> {
                val elsewhere = mutes.entries().filter { it.row != null && it.keyword.equals(keyword, ignoreCase = true) }
                ctx.reply(
                    if (elsewhere.isEmpty()) "«$keyword» не зам'ючено."
                    else "«$keyword» є в іншій області: " + elsewhere.joinToString(", ") {
                        "/unmute \"${it.keyword}\"" + (it.category?.let { c -> " $c" } ?: "")
                    }
                )
            }
        }
    }

    private fun listMutes(args: List<String>, ctx: AdminCommandRouter.Context) {
        val all = mutes.entries()
        if (args.firstOrNull()?.equals("yaml", ignoreCase = true) == true) {
            val fromDb = all.filter { it.origin == MuteRegistry.Origin.DB }
            ctx.reply(
                if (fromDb.isEmpty()) "У БД немає mute — усе вже в config.yaml."
                else "Mute з БД як фрагмент config.yaml (допиши до наявних списків, потім /unmute тут):\n\n" +
                    mutesYaml(fromDb.map { it.keyword to it.category })
            )
            return
        }
        if (all.isEmpty()) {
            ctx.reply("Mute немає.")
            return
        }
        val sb = StringBuilder("🔇 Mute (${all.size}):")
        for ((scope, entries) in all.groupBy { it.category }.toSortedMap(nullsFirst())) {
            sb.append("\n${scope ?: "Глобально"}:")
            for (e in entries) sb.append("\n  • ${e.keyword} — ${source(e)}")
        }
        ctx.reply(sb.toString())
    }

    private fun source(e: MuteRegistry.Entry): String = when (e.origin) {
        MuteRegistry.Origin.CONFIG_GLOBAL -> "config.yaml"
        MuteRegistry.Origin.CONFIG_CATEGORY -> "config.yaml"
        MuteRegistry.Origin.DB -> "бот, ${e.row?.createdAt?.format(stamp)} (${e.row?.source})"
    }

    private fun describe(e: MuteRegistry.Entry): String =
        "«${e.keyword}» (${e.category ?: "глобально"}, ${source(e)})"

    companion object {
        const val SOURCE_COMMAND = "command"
        const val PREVIEW_DAYS = 7L
        const val BACKLOG_WARN = 60

        /**
         * `/mute` arguments → keyword and scope. The last argument names the scope when it is a
         * category and something precedes it; everything else is the keyword (unquoted words are
         * joined with spaces, so `/mute call of duty` works too). Null when nothing is left.
         */
        fun parseMuteArgs(args: List<String>, categories: Set<String>): Pair<String, String?>? {
            if (args.isEmpty()) return null
            val scoped = args.size >= 2 && args.last() in categories
            val words = if (scoped) args.dropLast(1) else args
            val keyword = words.joinToString(" ").trim()
            if (keyword.isEmpty()) return null
            return keyword to (if (scoped) args.last() else null)
        }

        /** DB mutes as a YAML fragment shaped like `config.yaml` (`preferences.mute`, `categories.<cat>.mute`). */
        fun mutesYaml(entries: List<Pair<String, String?>>): String {
            fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val sb = StringBuilder()
            val global = entries.filter { it.second == null }
            if (global.isNotEmpty()) {
                sb.append("preferences:\n  mute:\n")
                global.forEach { sb.append("    - ${q(it.first)}\n") }
            }
            val byCat = entries.filter { it.second != null }.groupBy { it.second!! }.toSortedMap()
            if (byCat.isNotEmpty()) {
                sb.append("categories:\n")
                for ((cat, list) in byCat) {
                    sb.append("  $cat:\n    mute:\n")
                    list.forEach { sb.append("      - ${q(it.first)}\n") }
                }
            }
            return sb.toString().trimEnd()
        }
    }
}
