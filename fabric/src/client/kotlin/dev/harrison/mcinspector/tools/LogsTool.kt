package dev.harrison.mcinspector.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.harrison.mcinspector.log.LogRingBuffer
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.int
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.schema
import dev.harrison.mcinspector.mcp.str

class LogsTool(private val logs: LogRingBuffer) : Tool {
    override val name = "read_logs"
    override val description = "Read recent game log lines (all mods + Minecraft) from an in-memory ring buffer. Filter by regex and/or level."
    override val inputSchema = schema(
        "lines" to prop("integer", "How many of the most recent matching lines to return (1-1000, default 100)."),
        "filter" to prop("string", "Regex applied to the formatted line (case-insensitive)."),
        "level" to prop("string", "Exact level filter", enum = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")),
    )

    override fun call(args: JsonObject): ToolResult {
        val n = (args.int("lines") ?: 100).coerceIn(1, 1000)
        val filter = args.str("filter")?.let { Regex(it, RegexOption.IGNORE_CASE) }
        val lines = logs.lines(n, filter, args.str("level"))
        return ToolResult.json(obj {
            "total" to logs.size()
            "returned" to lines.size
            "lines" to JsonArray().apply { lines.forEach { add(JsonPrimitive(it.format())) } }
        })
    }
}
