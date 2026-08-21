package dev.harrison.mcinspector.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpProtocolTest {
    private val reg = ToolRegistry().apply {
        register(object : Tool {
            override val name = "echo"; override val description = "echoes"
            override val inputSchema = schema("msg" to prop("string", "message"), required = listOf("msg"))
            override fun call(args: JsonObject) = ToolResult.text(args["msg"].asString)
        })
        register(object : Tool {
            override val name = "boom"; override val description = "throws"
            override val inputSchema = schema()
            override fun call(args: JsonObject): ToolResult = throw IllegalStateException("kaboom")
        })
    }
    private val p = McpProtocol(reg, "test", "0")

    private fun rpc(method: String, params: String = "{}", id: Int? = 1): JsonObject {
        val idPart = if (id != null) "\"id\":$id," else ""
        return JsonParser.parseString(p.handle("""{"jsonrpc":"2.0",$idPart"method":"$method","params":$params}""")!!).asJsonObject
    }

    @Test fun initialize() {
        val r = rpc("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        val res = r["result"].asJsonObject
        assertEquals("2025-06-18", res["protocolVersion"].asString)
        assertTrue(res["capabilities"].asJsonObject.has("tools"))
        assertEquals(1, r["id"].asInt)
    }

    @Test fun notificationReturnsNull() {
        assertNull(p.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test fun toolsList() {
        val tools = rpc("tools/list")["result"].asJsonObject["tools"].asJsonArray
        assertEquals(2, tools.size())
        assertEquals("echo", tools[0].asJsonObject["name"].asString)
        assertEquals("object", tools[0].asJsonObject["inputSchema"].asJsonObject["type"].asString)
    }

    @Test fun toolsCall() {
        val r = rpc("tools/call", """{"name":"echo","arguments":{"msg":"hi"}}""")["result"].asJsonObject
        assertEquals("hi", r["content"].asJsonArray[0].asJsonObject["text"].asString)
        assertEquals(false, r["isError"].asBoolean)
    }

    @Test fun toolErrorIsResultNotRpcError() {
        val r = rpc("tools/call", """{"name":"boom","arguments":{}}""")["result"].asJsonObject
        assertTrue(r["isError"].asBoolean)
        assertTrue(r["content"].asJsonArray[0].asJsonObject["text"].asString.contains("kaboom"))
    }

    @Test fun unknownToolIsErrorResult() {
        assertTrue(rpc("tools/call", """{"name":"nope"}""")["result"].asJsonObject["isError"].asBoolean)
    }

    @Test fun unknownMethod() {
        assertEquals(-32601, rpc("nope")["error"].asJsonObject["code"].asInt)
    }

    @Test fun parseError() {
        val r = JsonParser.parseString(p.handle("{not json")!!).asJsonObject
        assertEquals(-32700, r["error"].asJsonObject["code"].asInt)
    }

    @Test fun batch() {
        val out = JsonParser.parseString(
            p.handle("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":2,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/x"}]""")!!
        ).asJsonArray
        assertEquals(2, out.size())
    }

    @Test fun batchOfOnlyNotificationsIsNull() {
        assertNull(p.handle("""[{"jsonrpc":"2.0","method":"notifications/initialized"}]"""))
    }
}
