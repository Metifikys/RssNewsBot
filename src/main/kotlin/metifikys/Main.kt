package metifikys

import metifikys.config.ConfigLoader

fun main(args: Array<String>) {
    val configPath = args.firstOrNull() ?: "config.yaml"
    val config = ConfigLoader.load(configPath)
    NewsBot(config).start()
    Thread.currentThread().join()
}
