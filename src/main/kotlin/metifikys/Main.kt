package metifikys

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.ConfigLoader
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val configPath = args.firstOrNull() ?: "config.yaml"
    val config = ConfigLoader.load(configPath)
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
