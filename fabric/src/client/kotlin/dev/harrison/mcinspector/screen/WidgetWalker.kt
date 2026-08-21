package dev.harrison.mcinspector.screen

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.harrison.mcinspector.mcp.obj
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen

/** One GUI element, in GUI (scaled) coordinates. */
data class WidgetInfo(
    val index: Int, val type: String, val text: String,
    val x: Int, val y: Int, val width: Int, val height: Int,
    val active: Boolean, val focused: Boolean, val visible: Boolean, val path: String,
) {
    fun centerX() = x + width / 2.0
    fun centerY() = y + height / 2.0
    fun toJson(): JsonObject = obj {
        "index" to index; "type" to type; "text" to text
        "x" to x; "y" to y; "width" to width; "height" to height
        "active" to active; "focused" to focused; "visible" to visible; "path" to path
    }
}

object WidgetWalker {
    /** Depth-first flatten of the screen's event-listener tree. */
    fun walk(screen: Screen): List<WidgetInfo> {
        val out = ArrayList<WidgetInfo>()
        fun visit(node: GuiEventListener, path: String) {
            out += describe(node, out.size, path)
            if (node is ContainerEventHandler) {
                node.children().forEachIndexed { i, child -> visit(child, "$path/$i") }
            }
        }
        screen.children().forEachIndexed { i, child -> visit(child, "$i") }
        return out
    }

    private fun describe(node: GuiEventListener, index: Int, path: String): WidgetInfo {
        val rect = node.rectangle
        val text = when (node) {
            is AbstractWidget -> node.message.string
            else -> ""
        }
        val (active, visible) = when (node) {
            is AbstractWidget -> node.isActive to node.visible
            else -> true to true
        }
        return WidgetInfo(index, node.javaClass.simpleName, text, rect.left(), rect.top(), rect.width(), rect.height(), active, node.isFocused, visible, path)
    }

    /** Exact index, else exact text (case-insensitive), else first case-insensitive contains. */
    fun find(list: List<WidgetInfo>, index: Int?, text: String?): WidgetInfo? {
        if (index != null) return list.getOrNull(index)
        if (text == null) return null
        val t = text.trim()
        return list.firstOrNull { it.text.equals(t, ignoreCase = true) }
            ?: list.firstOrNull { it.text.contains(t, ignoreCase = true) }
    }

    fun toJson(list: List<WidgetInfo>): JsonArray = JsonArray().apply { list.forEach { add(it.toJson()) } }
}
