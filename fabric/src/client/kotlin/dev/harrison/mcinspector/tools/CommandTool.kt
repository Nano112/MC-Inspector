package dev.harrison.mcinspector.tools

import com.google.gson.JsonObject
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.require
import dev.harrison.mcinspector.mcp.schema
import net.minecraft.client.Minecraft

class CommandTool : Tool {
    override val name = "run_command"
    override val description = "Send a chat message or command to the server you're connected to. Text starting with '/' is sent as a command, otherwise as chat."
    override val inputSchema = schema("command" to prop("string", "e.g. \"/tp 0 100 0\" or \"hello\""), required = listOf("command"))
    override fun call(args: JsonObject): ToolResult {
        val raw = args.require("command").trim()
        val kind = RenderThread.run {
            val conn = Minecraft.getInstance().connection ?: throw IllegalStateException("not connected to a server/world")
            if (raw.startsWith("/")) { conn.sendCommand(raw.substring(1)); "command" } else { conn.sendChat(raw); "chat" }
        }
        return ToolResult.json(obj { "sent" to raw; "kind" to kind })
    }
}
