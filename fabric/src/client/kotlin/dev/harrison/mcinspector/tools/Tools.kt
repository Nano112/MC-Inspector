package dev.harrison.mcinspector.tools

import dev.harrison.mcinspector.config.InspectorConfig
import dev.harrison.mcinspector.log.LogRingBuffer
import dev.harrison.mcinspector.mcp.ToolRegistry

/** Single place where every tool is registered. Must be safe to call without a running game (tests). */
object Tools {
    fun registerAll(reg: ToolRegistry, cfg: InspectorConfig, logs: LogRingBuffer) {
        // populated in later tasks
    }
}
