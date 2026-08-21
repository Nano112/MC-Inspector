package dev.harrison.mcinspector

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

object McInspectorClient : ClientModInitializer {
    const val MOD_ID = "mcinspector"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        LOGGER.info("[MCInspector] client init")
    }
}
