package metifikys

import ch.qos.logback.classic.LoggerContext
import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.ConfigCheck
import metifikys.config.ConfigLoader
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    // No display on the server; the weekly barometer draws its chart with java.awt (headless).
    System.setProperty("java.awt.headless", "true")
    if (args.firstOrNull() == "--check-config") {
        consoleLoggingOnly()
        exitProcess(ConfigCheck.main(args.toList()))
    }
    val configPath = args.firstOrNull() ?: "config.yaml"
    val config = ConfigLoader.load(configPath)
    ConfigCheck.logStartupReport(configPath)
    try {
        NewsBot(config).start()
    } catch (e: Exception) {
        // A refused start (e.g. prompt validation) must end the process, not leave it idling
        // with half-started threads; systemd then shows the reason in the journal.
        logger.error(e) { "Startup failed: ${e.message}" }
        exitProcess(1)
    }
    Thread.currentThread().join()
}

/**
 * `--check-config` runs next to the live service, usually from its directory: keep its few log
 * lines on the console instead of appending them to the service's rolling log files.
 */
private fun consoleLoggingOnly() {
    val ctx = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    root.iteratorForAppenders().asSequence().toList()
        .filter { it !is ch.qos.logback.core.ConsoleAppender<*> }
        .forEach { it.stop(); root.detachAppender(it) }
}
