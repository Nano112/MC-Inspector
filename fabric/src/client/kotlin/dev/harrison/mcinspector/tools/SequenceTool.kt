package dev.harrison.mcinspector.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.harrison.mcinspector.compat.Compat
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolRegistry
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.bool
import dev.harrison.mcinspector.mcp.int
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.schema
import dev.harrison.mcinspector.mcp.str
import net.minecraft.client.Minecraft

/**
 * Runs several tool calls in order with optional waits between them, so multi-step flows (navigate
 * menus → load a world → screenshot) are one MCP round-trip.
 */
class SequenceTool(private val registry: ToolRegistry) : Tool {
    override val name = "run_sequence"
    override val description = """Run a list of tool calls sequentially in ONE request. Each step: {"tool": name, "args": {...},
"waitTicks": n, "waitFor": {...}}. After a step, `waitTicks` sleeps N ticks (20 = 1 s) and/or `waitFor` polls until a
condition holds: {"inWorld": true|false, "screen": "<SimpleName>"|"none", "overlayOpen": true|false, "timeoutTicks": 600}.
Stops at the first failing step (or timeout) unless "continueOnError": true. Returns each step's text result; image
results (screenshot) are attached as image blocks in order. Example:
[{"tool":"click_widget","args":{"text":"Singleplayer"}},{"tool":"click_widget","args":{"index":3}},
 {"tool":"click_widget","args":{"text":"Play Selected World"},"waitFor":{"inWorld":true,"screen":"none","timeoutTicks":1200}},
 {"tool":"wait_ticks","args":{"ticks":40}},{"tool":"screenshot"}]""".trimIndent()
    override val inputSchema = schema(
        "steps" to prop("array", "Ordered steps; see description for the step object shape.").apply {
            add("items", JsonObject().apply { addProperty("type", "object") })
        },
        "continueOnError" to prop("boolean", "Keep going after a failed step (default false)."),
        required = listOf("steps"),
    )

    override fun call(args: JsonObject): ToolResult {
        val steps = args.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw IllegalArgumentException("'steps' must be an array")
        val continueOnError = args.bool("continueOnError") ?: false
        val report = JsonArray()
        val images = ArrayList<JsonObject>()
        var failed = false
        for ((i, el) in steps.withIndex()) {
            val step = el.asJsonObject
            val toolName = step.str("tool") ?: throw IllegalArgumentException("step $i: missing 'tool'")
            if (toolName == name) throw IllegalArgumentException("step $i: run_sequence cannot nest")
            val tool = registry.get(toolName) ?: throw IllegalArgumentException("step $i: unknown tool '$toolName'")
            val stepArgs = step.get("args")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val entry = obj { "step" to i; "tool" to toolName }
            val started = System.nanoTime()
            val result = try { tool.call(stepArgs) } catch (t: Throwable) { ToolResult.error("${t::class.simpleName}: ${t.message}") }
            entry.addProperty("ok", !result.isError)
            entry.addProperty("ms", (System.nanoTime() - started) / 1_000_000)
            val texts = result.content.filter { it.get("type")?.asString == "text" }.joinToString("\n") { it.get("text").asString }
            entry.addProperty("result", texts)
            result.content.filter { it.get("type")?.asString == "image" }.forEach { images += it }
            if (result.isError) {
                failed = true
                report.add(entry)
                if (!continueOnError) break else continue
            }
            step.int("waitTicks")?.let { n -> TickScheduler.awaitTicks(n.coerceIn(1, 1200), n * 50L + 30_000); entry.addProperty("waitedTicks", n) }
            step.get("waitFor")?.takeIf { it.isJsonObject }?.asJsonObject?.let { cond ->
                val outcome = waitFor(cond)
                entry.add("waitFor", outcome)
                if (outcome.get("satisfied").asBoolean.not()) {
                    failed = true; entry.addProperty("ok", false)
                    report.add(entry)
                    if (!continueOnError) return finish(report, images, failed)
                    return@let
                }
            }
            report.add(entry)
        }
        return finish(report, images, failed)
    }

    private fun finish(report: JsonArray, images: List<JsonObject>, failed: Boolean): ToolResult {
        val summary = obj { "ok" to !failed; "steps" to report }
        val blocks = ArrayList<JsonObject>()
        blocks += ToolResult.textBlock(com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(summary))
        blocks += images
        return ToolResult(blocks, isError = failed)
    }

    /** Poll the condition every 2 ticks until satisfied or timeout. */
    private fun waitFor(cond: JsonObject): JsonObject {
        val timeout = (cond.int("timeoutTicks") ?: 600).coerceIn(1, 6000)
        val wantInWorld = cond.bool("inWorld")
        val wantScreen = cond.str("screen")
        val wantOverlay = cond.bool("overlayOpen")
        val start = TickScheduler.tick
        var last = ""
        while (TickScheduler.tick - start < timeout) {
            val state = RenderThread.run {
                val mc = Minecraft.getInstance()
                val screen = Compat.screen()?.javaClass?.simpleName
                val overlay = runCatching { dev.harrison.panellib.PanelLib.api().isOverlayOpen }.getOrDefault(false)
                Triple(mc.level != null && mc.player != null, screen, overlay)
            }
            last = "inWorld=${state.first} screen=${state.second ?: "none"} overlayOpen=${state.third}"
            val okWorld = wantInWorld == null || wantInWorld == state.first
            val okScreen = wantScreen == null || (if (wantScreen.equals("none", true)) state.second == null else state.second.equals(wantScreen, true))
            val okOverlay = wantOverlay == null || wantOverlay == state.third
            if (okWorld && okScreen && okOverlay) return obj { "satisfied" to true; "afterTicks" to (TickScheduler.tick - start); "state" to last }
            TickScheduler.awaitTicks(2)
        }
        return obj { "satisfied" to false; "timeoutTicks" to timeout; "state" to last }
    }
}
