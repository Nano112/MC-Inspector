package dev.harrison.mcinspector.tools

import dev.harrison.mcinspector.compat.Compat
import com.google.gson.JsonObject
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.schema
import net.minecraft.client.Minecraft

class StateTool : Tool {
    override val name = "get_state"
    override val description = "Snapshot of client state: current screen, player position/rotation/health, server, fps, window size, gui scale, whether the mouse is grabbed (in-world look mode)."
    override val inputSchema = schema()

    override fun call(args: JsonObject): ToolResult = ToolResult.json(RenderThread.run {
        val mc = Minecraft.getInstance()
        val win = mc.window
        val p = mc.player
        obj {
            "screen" to Compat.screen()?.javaClass?.simpleName
            "screenTitle" to Compat.screen()?.title?.string
            "inWorld" to (mc.level != null)
            "player" to p?.let {
                obj {
                    "x" to it.x; "y" to it.y; "z" to it.z
                    "yaw" to it.yRot; "pitch" to it.xRot
                    "health" to it.health; "food" to it.foodData.foodLevel
                    //? if >=1.21.11 {
                    "dimension" to it.level().dimension().identifier().toString()
                    //?} else {
                    /*"dimension" to it.level().dimension().location().toString()
                    *///?}
                    "gamemode" to mc.gameMode?.playerMode?.name
                    "name" to it.gameProfile.name
                }
            }
            "panellib" to runCatching {
                val api = dev.harrison.panellib.PanelLib.api()
                obj {
                    "overlayOpen" to api.isOverlayOpen; "focused" to api.isOverlayFocused
                    "openPanels" to com.google.gson.JsonArray().apply { api.mods.flatMap { it.panels }.filter { it.isOpen }.forEach { add(it.id) } }
                    "panels" to com.google.gson.JsonArray().apply { api.mods.flatMap { it.panels }.forEach { add(it.id) } }
                }
            }.getOrNull()
            "server" to mc.currentServer?.let { obj { "name" to it.name; "address" to it.ip } }
            "singleplayer" to mc.isLocalServer
            "fps" to mc.fps
            "tick" to TickScheduler.tick
            "window" to obj {
                "mouseWidth" to win.screenWidth; "mouseHeight" to win.screenHeight
                "framebufferWidth" to win.width; "framebufferHeight" to win.height; "guiScale" to win.guiScale
                "guiWidth" to win.guiScaledWidth; "guiHeight" to win.guiScaledHeight
                "focused" to mc.isWindowActive; "mouseGrabbed" to mc.mouseHandler.isMouseGrabbed
                "mouseX" to mc.mouseHandler.xpos(); "mouseY" to mc.mouseHandler.ypos()
            }
        }
    })
}
