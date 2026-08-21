package dev.harrison.mcinspector.tools

import dev.harrison.mcinspector.compat.Compat
import com.google.gson.JsonObject
import dev.harrison.mcinspector.input.InputInjector
import dev.harrison.mcinspector.input.KeyNames
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.int
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.require
import dev.harrison.mcinspector.mcp.schema
import dev.harrison.mcinspector.mcp.str
import dev.harrison.mcinspector.screen.WidgetInfo
import dev.harrison.mcinspector.screen.WidgetWalker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.options.controls.ControlsScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen

private val TARGET_PROPS = arrayOf(
    "index" to prop("integer", "Widget index from `list_widgets`."),
    "text" to prop("string", "Widget label to match (exact, case-insensitive; falls back to contains)."),
)

private fun currentScreenOrFail() = Compat.screen() ?: throw IllegalStateException("no screen is open (in-world HUD). Use open_screen or press_key escape first.")

private fun findWidget(args: JsonObject): Pair<WidgetInfo, List<WidgetInfo>> {
    val screen = currentScreenOrFail()
    val all = WidgetWalker.walk(screen)
    val w = WidgetWalker.find(all, args.int("index"), args.str("text"))
        ?: throw IllegalArgumentException("no widget matches index=${args.int("index")} text=${args.str("text")}. Widgets: " +
            all.filter { it.text.isNotBlank() }.joinToString { "#${it.index} '${it.text}'" })
    return w to all
}

class GetScreenTool : Tool {
    override val name = "get_screen"
    override val description = "Describe the currently open screen (menu), or null when in-world with no menu."
    override val inputSchema = schema()
    override fun call(args: JsonObject): ToolResult = ToolResult.json(RenderThread.run {
        val s = Compat.screen()
        if (s == null) com.google.gson.JsonNull.INSTANCE else obj {
            "className" to s.javaClass.name; "title" to s.title.string; "width" to s.width; "height" to s.height
            "widgets" to WidgetWalker.walk(s).size
        }
    })
}

class ListWidgetsTool : Tool {
    override val name = "list_widgets"
    override val description = "List every widget on the open screen with its label and GUI-scaled bounds. " +
        "Multiply by `guiToMouse` to get mouse coordinates for mouse_* tools. Prefer `click_widget` over computing coordinates yourself."
    override val inputSchema = schema()
    override fun call(args: JsonObject): ToolResult = ToolResult.json(RenderThread.run {
        val mc = Minecraft.getInstance()
        val s = currentScreenOrFail()
        obj { "screen" to s.javaClass.simpleName; "guiScale" to mc.window.guiScale; "guiToMouse" to InputInjector.guiToMouse(1.0); "widgets" to WidgetWalker.toJson(WidgetWalker.walk(s)) }
    })
}

class ClickWidgetTool : Tool {
    override val name = "click_widget"
    override val description = "Click a widget on the open screen by label text or index (from list_widgets). Moves the mouse to its centre and clicks through the real input path."
    override val inputSchema = schema(*TARGET_PROPS, "button" to prop("string", "left (default) / right / middle."))
    override fun call(args: JsonObject): ToolResult {
        val button = KeyNames.mouseButton(args.str("button"))
        val clicked = RenderThread.run {
            val (w, _) = findWidget(args)
            if (!w.visible) throw IllegalStateException("widget '${w.text}' is not visible")
            if (!w.active) throw IllegalStateException("widget '${w.text}' is inactive/disabled")
            InputInjector.mouseMove(InputInjector.guiToMouse(w.centerX()), InputInjector.guiToMouse(w.centerY()))
            InputInjector.mouseDown(button)
            TickScheduler.after(1) { InputInjector.mouseUp(button) }
            w
        }
        TickScheduler.awaitTicks(2)
        return ToolResult.json(obj { "clicked" to clicked.toJson(); "screenAfter" to RenderThread.run { Compat.screen()?.javaClass?.simpleName } })
    }
}

class SetTextFieldTool : Tool {
    override val name = "set_text_field"
    override val description = "Set the value of a text field (EditBox) on the open screen, selected by index or by its label/hint text; with no selector, the first text field is used."
    override val inputSchema = schema(*TARGET_PROPS, "value" to prop("string", "New text value."), required = listOf("value"))
    override fun call(args: JsonObject): ToolResult {
        val value = args.require("value")
        val info = RenderThread.run {
            val screen = currentScreenOrFail()
            val boxes = ArrayList<Pair<EditBox, Int>>()
            var idx = 0
            fun visit(n: GuiEventListener) { if (n is EditBox) boxes += n to idx; idx++; if (n is ContainerEventHandler) n.children().forEach(::visit) }
            screen.children().forEach(::visit)
            if (boxes.isEmpty()) throw IllegalStateException("no text fields on ${screen.javaClass.simpleName}")
            val wantIdx = args.int("index"); val wantText = args.str("text")
            val (box, i) = when {
                wantIdx != null -> boxes.firstOrNull { it.second == wantIdx } ?: throw IllegalArgumentException("index $wantIdx is not a text field")
                wantText != null -> boxes.firstOrNull { it.first.message.string.contains(wantText, true) } ?: boxes.first()
                else -> boxes.first()
            }
            screen.setFocused(box)
            box.isFocused = true
            box.value = value
            obj { "index" to i; "label" to box.message.string; "value" to box.value }
        }
        return ToolResult.json(info)
    }
}

class OpenScreenTool : Tool {
    override val name = "open_screen"
    override val description = "Open a vanilla screen directly: title, pause, options, controls, multiplayer, singleplayer, inventory (in-world), chat (in-world)."
    override val inputSchema = schema("id" to prop("string", "Screen id", enum = listOf("title", "pause", "options", "controls", "multiplayer", "singleplayer", "inventory", "chat")), required = listOf("id"))
    override fun call(args: JsonObject): ToolResult {
        val id = args.require("id")
        val opened = RenderThread.run {
            val mc = Minecraft.getInstance()
            val parent = Compat.screen()
            val screen = when (id) {
                "title" -> TitleScreen()
                "pause" -> PauseScreen(true)
                //? if >=26.1 {
                /*"options" -> OptionsScreen(parent ?: TitleScreen(), mc.options, mc.level != null)
                *///?} else {
                "options" -> OptionsScreen(parent ?: TitleScreen(), mc.options)
                //?}
                "controls" -> ControlsScreen(parent ?: TitleScreen(), mc.options)
                "multiplayer" -> JoinMultiplayerScreen(parent ?: TitleScreen())
                "singleplayer" -> SelectWorldScreen(parent ?: TitleScreen())
                "inventory" -> InventoryScreen(mc.player ?: throw IllegalStateException("not in a world"))
                "chat" -> if (mc.player == null) throw IllegalStateException("not in a world") else
                    //? if >=1.21.9 {
                    ChatScreen("", false)
                    //?} else {
                    /*ChatScreen("")
                    *///?}
                else -> throw IllegalArgumentException("unknown screen id '$id'")
            }
            Compat.setScreen(screen)
            screen.javaClass.simpleName
        }
        TickScheduler.awaitTicks(1)
        return ToolResult.json(obj { "opened" to opened })
    }
}

class CloseScreenTool : Tool {
    override val name = "close_screen"
    override val description = "Close the current screen (setScreen(null)). In-world this returns to gameplay and grabs the mouse."
    override val inputSchema = schema()
    override fun call(args: JsonObject): ToolResult {
        val before = RenderThread.run { val mc = Minecraft.getInstance(); val b = Compat.screen()?.javaClass?.simpleName; Compat.setScreen(null); b }
        TickScheduler.awaitTicks(1)
        return ToolResult.json(obj { "closed" to before; "screenAfter" to RenderThread.run { Compat.screen()?.javaClass?.simpleName } })
    }
}
