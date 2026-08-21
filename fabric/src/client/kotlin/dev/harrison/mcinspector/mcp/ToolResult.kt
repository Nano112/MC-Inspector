package dev.harrison.mcinspector.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject

class ToolResult(val content: List<JsonObject>, val isError: Boolean = false) {
    fun toJson(): JsonObject = JsonObject().apply {
        add("content", com.google.gson.JsonArray().apply { content.forEach { add(it) } })
        addProperty("isError", isError)
    }

    companion object {
        private val pretty = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()

        fun textBlock(s: String): JsonObject = JsonObject().apply { addProperty("type", "text"); addProperty("text", s) }

        fun text(s: String): ToolResult = ToolResult(listOf(textBlock(s)))
        fun json(o: JsonElement): ToolResult = text(pretty.toJson(o))
        fun error(msg: String): ToolResult = ToolResult(listOf(textBlock(msg)), isError = true)
        fun image(pngBase64: String, extraJson: JsonElement? = null): ToolResult {
            val img = JsonObject().apply {
                addProperty("type", "image"); addProperty("data", pngBase64); addProperty("mimeType", "image/png")
            }
            val blocks = mutableListOf(img)
            if (extraJson != null) blocks += textBlock(pretty.toJson(extraJson))
            return ToolResult(blocks)
        }
    }
}

/** Small DSL for building result objects: `obj { "x" to 1; "name" to "a" }`. */
class JsonObjBuilder(val o: JsonObject = JsonObject()) {
    infix fun String.to(v: Any?) {
        when (v) {
            null -> o.add(this, com.google.gson.JsonNull.INSTANCE)
            is JsonElement -> o.add(this, v)
            is Number -> o.addProperty(this, v)
            is Boolean -> o.addProperty(this, v)
            is String -> o.addProperty(this, v)
            else -> o.addProperty(this, v.toString())
        }
    }
}
fun obj(build: JsonObjBuilder.() -> Unit): JsonObject = JsonObjBuilder().apply(build).o
