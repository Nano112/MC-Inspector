package dev.harrison.mcinspector.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.harrison.mcinspector.imgui.ImGuiHooks
import dev.harrison.mcinspector.imgui.ImGuiItem
import dev.harrison.mcinspector.imgui.ImGuiItemIndex
import dev.harrison.mcinspector.input.InputInjector
import dev.harrison.mcinspector.input.KeyNames
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.bool
import dev.harrison.mcinspector.mcp.int
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.require
import dev.harrison.mcinspector.mcp.schema
import dev.harrison.mcinspector.mcp.str

/** Waits for ImGui frames (the overlay renders them, not game ticks) and reads what they recorded. */
internal object ImGuiFrames {
    private val index get() = ImGuiHooks.index

    /** Arm recording and return the items of a frame that started after this call. */
    fun fresh(): List<ImGuiItem> {
        index.arm()
        await(2)
        return index.items
    }

    /** Block until [frames] more ImGui frames have completed. */
    fun await(frames: Int, timeoutMs: Long = 3_000) {
        val target = index.frame + frames
        val deadline = System.currentTimeMillis() + timeoutMs
        while (index.frame < target) {
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("No ImGui frames are being drawn. Open the overlay first (panel-lib: open a panel or press its overlay key).")
            }
            Thread.sleep(2)
        }
    }

    fun toJson(i: ImGuiItem): JsonObject = obj {
        "window" to i.window; "label" to i.label; "rawLabel" to i.rawLabel; "kind" to i.kind
        "x" to Math.round(i.x); "y" to Math.round(i.y); "w" to Math.round(i.w); "h" to Math.round(i.h)
        if (i.open != null) "open" to i.open
    }

    fun notFound(items: List<ImGuiItem>, label: String, window: String?): ToolResult {
        val near = items.filter { window == null || it.window.contains(window, ignoreCase = true) }
            .map { "${it.window} / ${it.label.ifEmpty { it.rawLabel }}" }.distinct().take(40)
        return ToolResult.error("No ImGui item labelled '$label'${window?.let { " in a window matching '$it'" } ?: ""}. " +
            "Visible items: ${near.joinToString("; ").ifEmpty { "none" }}")
    }
}

private val LABEL_PROP = prop("string", "Item label as shown (case-insensitive; exact, then prefix, then substring), or its raw ImGui label such as '##search'.")
private val WINDOW_PROP = prop("string", "Only items in windows whose path contains this, e.g. 'Browse' or 'Browse > Communities'.")

class ImGuiItemsTool : Tool {
    override val name = "imgui_items"
    override val description = "List the Dear ImGui items (panel-lib overlay, any mod) drawn in the last frame: window path, label, kind " +
        "(button, selectable, menu, combo, openable, toggle, input) and rect in mouse coordinates. Use with imgui_click."
    override val inputSchema = schema("window" to WINDOW_PROP,
        "query" to prop("string", "Only items whose label contains this (case-insensitive)."),
        "limit" to prop("integer", "Maximum items to return (default 200)."))

    override fun call(args: JsonObject): ToolResult {
        val window = args.str("window"); val query = args.str("query")
        val items = ImGuiFrames.fresh().filter {
            (window == null || it.window.contains(window, ignoreCase = true)) &&
                (query == null || it.label.contains(query, ignoreCase = true) || it.rawLabel.contains(query))
        }
        val limit = (args.int("limit") ?: 200).coerceIn(1, 2000)
        return ToolResult.json(obj {
            "count" to items.size
            "items" to JsonArray().apply { items.take(limit).forEach { add(ImGuiFrames.toJson(it)) } }
        })
    }
}

class ImGuiClickTool : Tool {
    override val name = "imgui_click"
    override val description = "Find a Dear ImGui item by label and click it like a person: move there, let ImGui see the hover " +
        "for a few frames, then press and release in separate frames. hover=true only moves (menus that open on hover). " +
        "Returns the item it used."
    override val inputSchema = schema("label" to LABEL_PROP, "window" to WINDOW_PROP,
        "index" to prop("integer", "Which match to use when several share the label (default 0)."),
        "button" to prop("string", "left (default), right, middle."),
        "hover" to prop("boolean", "Only move the mouse onto the item (default false)."),
        "double" to prop("boolean", "Double-click (default false)."),
        required = listOf("label"))

    override fun call(args: JsonObject): ToolResult {
        val label = args.require("label"); val window = args.str("window")
        val items = ImGuiFrames.fresh()
        val item = ImGuiItemIndex.find(items, label, window, args.int("index"))
            ?: return ImGuiFrames.notFound(items, label, window)
        val button = KeyNames.mouseButton(args.str("button"))
        RenderThread.run { InputInjector.mouseMove(item.centerX.toDouble(), item.centerY.toDouble()) }
        // ImGui decides hover from the previous frame's mouse position: give it a few frames before pressing.
        ImGuiFrames.await(3)
        if (args.bool("hover") != true) {
            repeat(if (args.bool("double") == true) 2 else 1) {
                RenderThread.run { InputInjector.mouseDown(button) }
                ImGuiFrames.await(2)
                RenderThread.run { InputInjector.mouseUp(button) }
                ImGuiFrames.await(2)
            }
        }
        return ToolResult.json(obj {
            "clicked" to (args.bool("hover") != true)
            "item" to ImGuiFrames.toJson(item)
            "x" to item.centerX; "y" to item.centerY
        })
    }
}
