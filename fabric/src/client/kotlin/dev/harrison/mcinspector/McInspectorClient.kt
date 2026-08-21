package dev.harrison.mcinspector

import dev.harrison.mcinspector.config.InspectorConfig
import dev.harrison.mcinspector.input.InputInjector
import dev.harrison.mcinspector.log.LogRingBuffer
import dev.harrison.mcinspector.mcp.McpProtocol
import dev.harrison.mcinspector.mcp.McpServer
import dev.harrison.mcinspector.mcp.McpStats
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.mcinspector.mcp.ToolRegistry
import dev.harrison.mcinspector.tools.Tools
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files

object McInspectorClient : ClientModInitializer {
    const val MOD_ID = "mcinspector"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    lateinit var config: InspectorConfig
        private set
    val logs = LogRingBuffer()
    private var server: McpServer? = null

    override fun onInitializeClient() {
        val loader = FabricLoader.getInstance()
        logs.install()
        config = InspectorConfig.load(loader.configDir.resolve("mcinspector.json"))
        TickScheduler.install()
        if (!config.enabled) {
            LOGGER.info("[MCInspector] MCP server disabled by config")
            return
        }
        InputInjector.forceWindowActive = true
        InputInjector.lockCursor = config.lockCursor
        val registry = ToolRegistry()
        Tools.registerAll(registry, config, logs)
        val version = loader.getModContainer(MOD_ID).map { it.metadata.version.friendlyString }.orElse("dev")
        val mcp = McpServer(McpProtocol(registry, "mcinspector", version), { registry.size }, port = config.port, authToken = config.authToken)
        try {
            val port = mcp.start()
            server = mcp
            McpStats.boundPort = port
            val portFile = loader.gameDir.resolve(".mcp-port")
            Files.writeString(portFile, port.toString())
            LOGGER.info("[MCInspector] MCP server listening on http://127.0.0.1:$port/mcp ({} tools)", registry.size)
        } catch (t: Throwable) {
            LOGGER.error("[MCInspector] failed to start MCP server on port {}", config.port, t)
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { server?.stop() }
    }
}
