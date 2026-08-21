package dev.harrison.mcinspector.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import org.slf4j.LoggerFactory

/**
 * MCP method dispatch over JSON-RPC bodies. Transport-agnostic: [handle] takes
 * the raw request body and returns the response body, or null when nothing
 * should be sent (notification / batch of notifications).
 */
class McpProtocol(
    private val registry: ToolRegistry,
    private val serverName: String,
    private val serverVersion: String,
) {
    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private val LOGGER = LoggerFactory.getLogger("mcinspector.mcp")
    }

    private val gson = Gson()

    fun handle(body: String): String? {
        val parsed: JsonElement = try {
            JsonParser.parseString(body)
        } catch (e: JsonSyntaxException) {
            return gson.toJson(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error: ${e.message}"))
        }
        return when {
            parsed.isJsonArray -> {
                val out = JsonArray()
                parsed.asJsonArray.forEach { el -> handleOne(el)?.let(out::add) }
                if (out.size() == 0) null else gson.toJson(out)
            }
            else -> handleOne(parsed)?.let(gson::toJson)
        }
    }

    private fun handleOne(el: JsonElement): JsonObject? {
        if (!el.isJsonObject) return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid request")
        val req = el.asJsonObject
        val id = req.get("id")
        val method = req.get("method")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "Missing method")
        val params = req.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val notification = JsonRpc.isNotification(req)

        val result: JsonElement = when (method) {
            "initialize" -> initialize()
            "ping" -> JsonObject()
            "tools/list" -> toolsList()
            "tools/call" -> toolsCall(params)
            else -> {
                if (method.startsWith("notifications/")) return null
                return if (notification) null else JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: $method")
            }
        }
        return if (notification) null else JsonRpc.result(id, result)
    }

    private fun initialize(): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", PROTOCOL_VERSION)
        add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
        add("serverInfo", JsonObject().apply { addProperty("name", serverName); addProperty("version", serverVersion) })
        addProperty("instructions", "Minecraft client controlled via MC-Inspector. Take a `screenshot` to see the game; " +
            "use `list_widgets`/`click_widget` for vanilla menus and `press_key`/`mouse_click` for raw input. " +
            "Game-touching tools run on the render thread; call `wait_ticks` to let the UI settle.")
    }

    private fun toolsList(): JsonObject = JsonObject().apply {
        add("tools", JsonArray().apply {
            registry.all().forEach { t ->
                add(JsonObject().apply {
                    addProperty("name", t.name)
                    addProperty("description", t.description)
                    add("inputSchema", t.inputSchema)
                })
            }
        })
    }

    private fun toolsCall(params: JsonObject): JsonObject {
        val name = params.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return ToolResult.error("tools/call: missing 'name'").toJson()
        val tool = registry.get(name) ?: return ToolResult.error("Unknown tool: $name").toJson()
        val args = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val started = System.nanoTime()
        McpStats.record(name)
        val result = try {
            tool.call(args)
        } catch (t: Throwable) {
            LOGGER.warn("[MCInspector] tool '{}' failed", name, t)
            ToolResult.error("${t::class.simpleName}: ${t.message}")
        }
        LOGGER.debug("[MCInspector] tool {} took {} ms", name, (System.nanoTime() - started) / 1_000_000)
        return result.toJson()
    }
}
