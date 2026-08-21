package dev.harrison.mcinspector.tools

import dev.harrison.mcinspector.config.InspectorConfig
import dev.harrison.mcinspector.log.LogRingBuffer
import dev.harrison.mcinspector.mcp.ToolRegistry

/** Single place where every tool is registered. Must be safe to call without a running game (tests). */
object Tools {
    fun registerAll(reg: ToolRegistry, cfg: InspectorConfig, logs: LogRingBuffer) {
        // observe
        reg.register(ScreenshotTool(cfg), StateTool(), LogsTool(logs), ModsTool())
        // raw input
        reg.register(PressKeyTool(), HoldKeyTool(), ReleaseKeyTool(), TypeTextTool(), MouseMoveTool(), MouseClickTool(), MouseScrollTool())
        // menus
        reg.register(GetScreenTool(), ListWidgetsTool(), ClickWidgetTool(), SetTextFieldTool(), OpenScreenTool(), CloseScreenTool())
        // world / server
        reg.register(CommandTool(), ConnectServerTool(), DisconnectTool(), ListServersTool(), WaitTool())
        // packets
        reg.register(PacketsListTool(), PacketsGetTool(), PacketsStatsTool(), PacketsClearTool(), PacketsCaptureTool())
        // composition
        reg.register(SequenceTool(reg))
    }
}
