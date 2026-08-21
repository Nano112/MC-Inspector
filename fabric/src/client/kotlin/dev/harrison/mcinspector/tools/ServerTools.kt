package dev.harrison.mcinspector.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.require
import dev.harrison.mcinspector.mcp.schema
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component

class ConnectServerTool : Tool {
    override val name = "connect_server"
    override val description = "Connect to a multiplayer server by address (host[:port]). Returns immediately; poll get_state until inWorld is true."
    override val inputSchema = schema("address" to prop("string", "e.g. localhost:25565"), required = listOf("address"))
    override fun call(args: JsonObject): ToolResult {
        val address = args.require("address")
        RenderThread.run {
            val mc = Minecraft.getInstance()
            if (mc.level != null) throw IllegalStateException("already in a world; call disconnect first")
            val addr = ServerAddress.parseString(address)
            val data = ServerData(address, address, ServerData.Type.OTHER)
            ConnectScreen.startConnecting(TitleScreen(), mc, addr, data, false, null)
        }
        return ToolResult.json(obj { "connecting" to address })
    }
}

class DisconnectTool : Tool {
    override val name = "disconnect"
    override val description = "Leave the current server or singleplayer world and return to the title screen."
    override val inputSchema = schema()
    override fun call(args: JsonObject): ToolResult {
        val was = RenderThread.run {
            val mc = Minecraft.getInstance()
            val had = mc.level != null
            if (had) {
                mc.level?.disconnect(Component.literal("MCInspector disconnect"))
                mc.disconnectWithProgressScreen()
            }
            mc.setScreen(TitleScreen())
            had
        }
        TickScheduler.awaitTicks(2)
        return ToolResult.json(obj { "wasInWorld" to was })
    }
}

class ListServersTool : Tool {
    override val name = "list_servers"
    override val description = "List the saved multiplayer server entries."
    override val inputSchema = schema()
    override fun call(args: JsonObject): ToolResult = ToolResult.json(RenderThread.run {
        val list = ServerList(Minecraft.getInstance()).apply { load() }
        JsonArray().apply { (0 until list.size()).forEach { i -> val s = list.get(i); add(obj { "name" to s.name; "address" to s.ip }) } }
    })
}
