package dev.harrison.mcinspector.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolRegistry
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.int
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.require
import dev.harrison.mcinspector.mcp.schema
import dev.harrison.mcinspector.mcp.str
import dev.harrison.mcinspector.script.ScriptEngine

class ScriptTool(registry: ToolRegistry) : Tool {
    private val engine by lazy { ScriptEngine(registry) }

    override val name = "exec_script"
    override val description = """Run JavaScript (Rhino, ES6-ish) inside the game client for anything the other tools don't cover.
Globals: mc (Minecraft), player, level, window, packets (PacketStore), panellib (PanelLibApi|null),
tools.call(name, args) → result of any other MCP tool as a JS object, log(...) → collected output, Packages.<fqcn> / importClass(Packages.a.b.C) for Java.
The last expression is the result (JS objects are JSON-stringified). Runs on the render thread by default
(safe for game state; keep it short — a deadline aborts runaway scripts). Examples:
  `player.getHealth()`   ·   `mc.options.guiScale().get()`   ·   `tools.call("get_state").player.x`
  `var ps = packets.query(new Packages.dev.harrison.mcinspector.packets.PacketQuery(null,null,null,null,0,5,true)); ps.size()`""".trimIndent()
    override val inputSchema = schema(
        "code" to prop("string", "JavaScript source."),
        "thread" to prop("string", "render (default) or background (off the game thread; do not touch game state there).", enum = listOf("render", "background")),
        "timeoutMs" to prop("integer", "Deadline in ms (default 5000, max 60000)."),
        required = listOf("code"),
    )

    override fun call(args: JsonObject): ToolResult {
        val code = args.require("code")
        val timeout = (args.int("timeoutMs") ?: 5000).coerceIn(50, 60_000).toLong()
        val background = args.str("thread") == "background"
        val started = System.nanoTime()
        val outcome = try {
            if (background) engine.run(code, timeout) else RenderThread.run(timeoutMs = timeout + 2000) { engine.run(code, timeout) }
        } catch (t: Throwable) {
            val cause = generateSequence(t) { it.cause }.last()
            return ToolResult.error("${cause::class.simpleName}: ${cause.message}")
        }
        return ToolResult.json(obj {
            "result" to outcome.value
            "log" to JsonArray().apply { outcome.log.forEach { add(JsonPrimitive(it)) } }
            "ms" to (System.nanoTime() - started) / 1_000_000
        })
    }
}
