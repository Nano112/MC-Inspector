package dev.harrison.mcinspector.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/** JSON-RPC 2.0 envelope helpers. */
object JsonRpc {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    fun result(id: JsonElement?, result: JsonElement): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: JsonNull.INSTANCE)
        add("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: JsonNull.INSTANCE)
        add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
    }

    /** A request without an `id` is a notification: no response is sent. */
    fun isNotification(req: JsonObject): Boolean = !req.has("id") || req.get("id").isJsonNull
}
