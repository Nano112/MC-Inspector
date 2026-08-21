package dev.harrison.mcinspector.tools

import dev.harrison.mcinspector.compat.Compat
import com.google.gson.JsonObject
import dev.harrison.mcinspector.input.InputInjector
import dev.harrison.mcinspector.input.KeyNames
import dev.harrison.mcinspector.mcp.RenderThread
import dev.harrison.mcinspector.mcp.TickScheduler
import dev.harrison.mcinspector.mcp.Tool
import dev.harrison.mcinspector.mcp.ToolResult
import dev.harrison.mcinspector.mcp.bool
import dev.harrison.mcinspector.mcp.dbl
import dev.harrison.mcinspector.mcp.int
import dev.harrison.mcinspector.mcp.obj
import dev.harrison.mcinspector.mcp.prop
import dev.harrison.mcinspector.mcp.require
import dev.harrison.mcinspector.mcp.schema
import dev.harrison.mcinspector.mcp.str
import dev.harrison.mcinspector.mcp.strList
import net.minecraft.client.Minecraft

private fun resolveKey(args: JsonObject): Int {
    val name = args.require("key")
    return KeyNames.resolve(name) ?: throw IllegalArgumentException("unknown key '$name' (examples: e, escape, enter, f3, left_shift, up)")
}

private val KEY_PROP = prop("string", "Key name: letters/digits, escape, enter, space, tab, backspace, f1-f25, up/down/left/right, left_shift, left_control, left_alt, or key.keyboard.* ids.")
private val MODS_PROP = prop("array", "Modifier keys held: shift, ctrl, alt, super.").apply { add("items", prop("string", "modifier")) }

class PressKeyTool : Tool {
    override val name = "press_key"
    override val description = "Press and release a key (goes through the real input path, so it works in menus and in-world). " +
        "In-world, the matching key binding is also engaged for `holdTicks` ticks so movement/actions happen."
    override val inputSchema = schema("key" to KEY_PROP, "modifiers" to MODS_PROP,
        "holdTicks" to prop("integer", "Ticks to hold before release (default 1, 20 ticks = 1 s)."), required = listOf("key"))

    override fun call(args: JsonObject): ToolResult {
        val key = resolveKey(args)
        val mods = KeyNames.modifiers(args.strList("modifiers"))
        val hold = (args.int("holdTicks") ?: 1).coerceIn(1, 1200)
        RenderThread.run {
            val inWorld = Compat.screen() == null
            InputInjector.keyDown(key, mods)
            if (inWorld) InputInjector.keyMappingSet(key, true)
            TickScheduler.after(hold) {
                InputInjector.keyUp(key, mods)
                if (inWorld) InputInjector.keyMappingSet(key, false)
            }
        }
        // Let the release happen before returning so callers can screenshot immediately.
        TickScheduler.awaitTicks(hold + 1)
        return ToolResult.json(obj { "key" to args.require("key"); "code" to key; "heldTicks" to hold })
    }
}

class HoldKeyTool : Tool {
    override val name = "hold_key"
    override val description = "Hold a key down until `release_key` is called (use for walking: hold_key w, wait_ticks 40, release_key w)."
    override val inputSchema = schema("key" to KEY_PROP, "modifiers" to MODS_PROP, required = listOf("key"))
    override fun call(args: JsonObject): ToolResult {
        val key = resolveKey(args); val mods = KeyNames.modifiers(args.strList("modifiers"))
        val mappings = RenderThread.run { InputInjector.keyDown(key, mods); InputInjector.keyMappingSet(key, true); InputInjector.mappingsFor(key) }
        return ToolResult.json(obj { "held" to args.require("key"); "code" to key; "mappings" to mappings })
    }
}

class ReleaseKeyTool : Tool {
    override val name = "release_key"
    override val description = "Release a key previously held with `hold_key`."
    override val inputSchema = schema("key" to KEY_PROP, "modifiers" to MODS_PROP, required = listOf("key"))
    override fun call(args: JsonObject): ToolResult {
        val key = resolveKey(args); val mods = KeyNames.modifiers(args.strList("modifiers"))
        val mappings = RenderThread.run { InputInjector.keyUp(key, mods); InputInjector.keyMappingSet(key, false); InputInjector.mappingsFor(key) }
        return ToolResult.json(obj { "released" to args.require("key"); "code" to key; "mappings" to mappings })
    }
}

class TypeTextTool : Tool {
    override val name = "type_text"
    override val description = "Type text into the focused text field / chat (character events). '\\n' presses Enter, '\\t' presses Tab."
    override val inputSchema = schema("text" to prop("string", "Text to type."), required = listOf("text"))
    override fun call(args: JsonObject): ToolResult {
        val text = args.require("text")
        RenderThread.run {
            text.codePoints().forEach { cp ->
                when (cp) {
                    '\n'.code -> { InputInjector.keyDown(257); InputInjector.keyUp(257) }
                    '\t'.code -> { InputInjector.keyDown(258); InputInjector.keyUp(258) }
                    else -> InputInjector.char(cp)
                }
            }
        }
        return ToolResult.json(obj { "typed" to text.length })
    }
}

class MouseMoveTool : Tool {
    override val name = "mouse_move"
    override val description = "Move the mouse to mouse coordinates (logical window units; see `mouseScale` from screenshot, or `mouseWidth`/`mouseHeight` from get_state) or by a delta with relative=true. In-world with the mouse grabbed, a relative move turns the camera."
    override val inputSchema = schema("x" to prop("number", "Mouse X (or delta when relative)."), "y" to prop("number", "Mouse Y (or delta when relative)."),
        "relative" to prop("boolean", "Treat x/y as a delta from the current position (default false)."), required = listOf("x", "y"))
    override fun call(args: JsonObject): ToolResult {
        val x = args.dbl("x")!!; val y = args.dbl("y")!!; val rel = args.bool("relative") ?: false
        val (nx, ny) = RenderThread.run {
            val cx = InputInjector.mouseX(); val cy = InputInjector.mouseY()
            // After grabMouse(), MC ignores the first move event (cursor re-centre). Send a zero-delta
            // move first so a relative look delta is never swallowed.
            if (rel && Minecraft.getInstance().mouseHandler.isMouseGrabbed) InputInjector.mouseMove(cx, cy)
            val tx = if (rel) cx + x else x
            val ty = if (rel) cy + y else y
            InputInjector.mouseMove(tx, ty); tx to ty
        }
        return ToolResult.json(obj { "x" to nx; "y" to ny })
    }
}

class MouseClickTool : Tool {
    override val name = "mouse_click"
    override val description = "Click a mouse button, optionally moving to mouse coordinates (x, y) first (image pixel * mouseScale). Works on menus (buttons, lists) and in-world (attack/use)."
    override val inputSchema = schema("x" to prop("number", "Mouse X to move to before clicking."), "y" to prop("number", "Mouse Y."),
        "button" to prop("string", "left (default), right, middle."), "double" to prop("boolean", "Double-click (default false)."),
        "holdTicks" to prop("integer", "Ticks between press and release (default 1)."))
    override fun call(args: JsonObject): ToolResult {
        val button = KeyNames.mouseButton(args.str("button"))
        val dbl = args.bool("double") ?: false
        val hold = (args.int("holdTicks") ?: 1).coerceIn(1, 1200)
        val x = args.dbl("x"); val y = args.dbl("y")
        if (x != null && y != null) {
            // Move first and let a couple of frames render: overlays (ImGui) decide whether they want the
            // click from the PREVIOUS frame's hover state, exactly like a human moving then clicking.
            RenderThread.run { InputInjector.mouseMove(x, y) }
            TickScheduler.awaitTicks(2)
        }
        val pos = RenderThread.run {
            InputInjector.mouseDown(button)
            TickScheduler.after(hold) { InputInjector.mouseUp(button) }
            if (dbl) TickScheduler.after(hold + 2) { InputInjector.mouseDown(button); TickScheduler.after(hold) { InputInjector.mouseUp(button) } }
            InputInjector.mouseX() to InputInjector.mouseY()
        }
        TickScheduler.awaitTicks(if (dbl) hold * 2 + 3 else hold + 1)
        return ToolResult.json(obj { "x" to pos.first; "y" to pos.second; "button" to button; "double" to dbl })
    }
}

class MouseScrollTool : Tool {
    override val name = "mouse_scroll"
    override val description = "Scroll the mouse wheel (dy > 0 = up), optionally at mouse coordinates (x, y)."
    override val inputSchema = schema("dy" to prop("number", "Vertical scroll amount in wheel notches."), "dx" to prop("number", "Horizontal scroll (default 0)."),
        "x" to prop("number", "Mouse X to move to first."), "y" to prop("number", "Mouse Y."), required = listOf("dy"))
    override fun call(args: JsonObject): ToolResult {
        val dy = args.dbl("dy")!!; val dx = args.dbl("dx") ?: 0.0; val x = args.dbl("x"); val y = args.dbl("y")
        RenderThread.run { if (x != null && y != null) InputInjector.mouseMove(x, y); InputInjector.scroll(dx, dy) }
        TickScheduler.awaitTicks(1)
        return ToolResult.json(obj { "dx" to dx; "dy" to dy })
    }
}
