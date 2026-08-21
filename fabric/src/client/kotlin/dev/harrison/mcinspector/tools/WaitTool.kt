package dev.harrison.mcinspector.tools

import com.google.gson.JsonObject
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.int
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.schema

class WaitTool : Tool {
    override val name = "wait_ticks"
    override val description = "Wait for N client ticks (20 ticks = 1 second, max 1200) so animations, screen transitions or held keys can play out."
    override val inputSchema = schema("ticks" to prop("integer", "1-1200"), required = listOf("ticks"))
    override fun call(args: JsonObject): ToolResult {
        val n = (args.int("ticks") ?: 1).coerceIn(1, 1200)
        TickScheduler.awaitTicks(n, timeoutMs = n * 50L + 30_000)
        return ToolResult.json(obj { "waited" to n; "tick" to TickScheduler.tick })
    }
}
