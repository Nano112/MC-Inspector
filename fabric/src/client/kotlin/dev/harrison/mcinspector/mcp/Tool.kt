package dev.harrison.mcinspector.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * One MCP tool. Implementations may throw from [call]; [McpProtocol] converts
 * exceptions into an `isError` result so the agent can read the message.
 *
 * Constructing a Tool must NOT touch Minecraft — the registry is also built in
 * unit tests. Touch the game only inside [call].
 */
interface Tool {
    val name: String
    val description: String
    /** JSON Schema for the `arguments` object. Use [schema]/[prop]. */
    val inputSchema: JsonObject
    fun call(args: JsonObject): ToolResult
}

fun prop(type: String, description: String, enum: List<String>? = null): JsonObject = JsonObject().apply {
    addProperty("type", type)
    addProperty("description", description)
    if (enum != null) add("enum", JsonArray().apply { enum.forEach { add(JsonPrimitive(it)) } })
}

fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply { props.forEach { (k, v) -> add(k, v) } })
    if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(JsonPrimitive(it)) } })
}

// ---- argument helpers -------------------------------------------------------

fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
fun JsonObject.long(key: String): Long? = get(key)?.takeUnless { it.isJsonNull }?.asLong
fun JsonObject.int(key: String): Int? = get(key)?.takeUnless { it.isJsonNull }?.asInt
fun JsonObject.dbl(key: String): Double? = get(key)?.takeUnless { it.isJsonNull }?.asDouble
fun JsonObject.bool(key: String): Boolean? = get(key)?.takeUnless { it.isJsonNull }?.asBoolean
fun JsonObject.strList(key: String): List<String> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
fun JsonObject.require(key: String): String = str(key) ?: throw IllegalArgumentException("missing required argument '$key'")
