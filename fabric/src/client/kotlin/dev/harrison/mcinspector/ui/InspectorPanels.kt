package dev.harrison.mcinspector.ui

import dev.harrison.mcinspector.McInspectorClient
import dev.harrison.mcinspector.mcp.McpStats
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.panellib.api.PanelLibApi
import dev.harrison.panellib.api.PanelLibEntrypoint
import dev.harrison.panellib.theme.Icons
import dev.harrison.panellib.widgets.Widgets
import imgui.ImGui
import imgui.flag.ImGuiTableColumnFlags
import org.lwjgl.glfw.GLFW

/** MC-Inspector's contribution to the shared panel-lib overlay. */
object InspectorPanels : PanelLibEntrypoint {
    @Volatile var demoClicks = 0
        private set
    private var markCounter = 0

    /** Action-bar message (the small text above the hotbar). */
    private fun toast(msg: String) {
        val mc = net.minecraft.client.Minecraft.getInstance()
        mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("[MC-Inspector] $msg"), false)
        McInspectorClient.LOGGER.info("[MCInspector] {}", msg)
    }

    override fun init(api: PanelLibApi) {
        val mod = api.registerMod("mcinspector", "Inspector", Icons.BUG)
        val status = mod.panel("mcp", "MCP Host", Icons.ROBOT) { renderStatus() }
        val packets = mod.panel("packets", "Packets", Icons.NETWORK) { PacketsPanel.render() }
        mod.menuItem("Copy MCP URL", Icons.COPY) { ImGui.setClipboardText("http://127.0.0.1:${McpStats.boundPort}/mcp") }
        mod.menuItem("Clear packet buffer", Icons.TRASH) { dev.harrison.mcinspector.packets.PacketCapture.store.clear() }
        mod.keybind("mcp_panel", GLFW.GLFW_KEY_I) { status.toggle() }
        mod.keybind("packets_panel", GLFW.GLFW_KEY_O) { packets.toggle() }

        // Capture control keys — usable mid-game without opening the overlay; feedback on the action bar.
        val store = dev.harrison.mcinspector.packets.PacketCapture.store
        mod.keybind("capture_toggle", GLFW.GLFW_KEY_F8) {
            store.paused = !store.paused
            toast(if (store.paused) "Packet capture stopped (${store.size()} buffered)" else "Packet capture started")
        }
        mod.keybind("capture_mark", GLFW.GLFW_KEY_F9) {
            val m = store.mark("mark ${++markCounter}", dev.harrison.mcinspector.mcp.TickScheduler.tick)
            toast("Marker #${m.id} \"${m.type.removePrefix("marker:")}\"")
        }
        mod.keybind("capture_clear", GLFW.GLFW_KEY_UNKNOWN) { store.clear(); toast("Packet buffer cleared") }
        mod.keybind("capture_export", GLFW.GLFW_KEY_UNKNOWN) {
            val dir = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve("packet-exports")
            val fmt = dev.harrison.mcinspector.packets.PacketExport.Format.JSONL
            val r = dev.harrison.mcinspector.packets.PacketExport.export(store.query(dev.harrison.mcinspector.packets.PacketQuery(limit = Int.MAX_VALUE, newestFirst = false)), fmt, dev.harrison.mcinspector.packets.PacketExport.defaultPath(dir, fmt))
            toast("Exported ${r.records} packets → ${r.path.fileName}")
        }
    }

    private fun renderStatus() {
        val port = McpStats.boundPort
        if (port > 0) Widgets.dot("Listening on 127.0.0.1:$port", Widgets.Tone.SUCCESS)
        else Widgets.dot("MCP server not running", Widgets.Tone.DANGER)
        ImGui.sameLine()
        Widgets.badge("MCP", Widgets.Tone.INFO)

        ImGui.spacing()
        val up = (System.currentTimeMillis() - McpStats.startedAt) / 1000
        Widgets.kvRow("Uptime", "%d:%02d:%02d".format(up / 3600, up / 60 % 60, up % 60))
        Widgets.kvRow("Tool calls", McpStats.totalCalls.toString())
        Widgets.kvRow("Last tool", McpStats.lastTool)
        Widgets.kvRow("Client tick", TickScheduler.tick.toString())

        Widgets.sectionHeader("Per tool")
        val rows = McpStats.perTool()
        if (rows.isEmpty()) Widgets.muted("No calls yet — connect an agent and call a tool.")
        else Widgets.table("##pertool", 2, height = 180f) {
            ImGui.tableSetupColumn("tool", ImGuiTableColumnFlags.WidthStretch)
            ImGui.tableSetupColumn("calls", ImGuiTableColumnFlags.WidthFixed, 70f)
            ImGui.tableHeadersRow()
            for ((k, v) in rows) { ImGui.tableNextRow(); ImGui.tableNextColumn(); ImGui.text(k); ImGui.tableNextColumn(); ImGui.text(v.toString()) }
        }

        ImGui.spacing()
        if (Widgets.primaryButton("${Icons.BOLT}  Ping log")) {
            demoClicks++
            McInspectorClient.LOGGER.info("[MCInspector] panel button clicked ({} total)", demoClicks)
        }
        ImGui.sameLine()
        if (Widgets.secondaryButton("Copy URL")) ImGui.setClipboardText("http://127.0.0.1:$port/mcp")
        if (demoClicks > 0) { ImGui.sameLine(); Widgets.faint("clicked $demoClicks×") }
    }
}
