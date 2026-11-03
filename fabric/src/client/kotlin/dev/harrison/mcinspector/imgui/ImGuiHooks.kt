package dev.harrison.mcinspector.imgui

import imgui.ImGui

/**
 * Called by [dev.harrison.mcinspector.mixin.ImGuiItemsMixin] around imgui-java's widget calls, on the render
 * thread inside an ImGui frame. Names windows by their native pointer (so no begin/end stack can go out of
 * balance) and records each widget's rect into [index]. Does nothing unless [index] is recording.
 */
object ImGuiHooks {
    @JvmField val index = ImGuiItemIndex()

    private val names = HashMap<Long, String>()
    private var parent = 0L
    private var headX = 0f
    private var headY = 0f
    private var headItemWidth = 0f
    private var originX = 0f
    private var originY = 0f

    private fun currentWindow(): Long = imgui.internal.ImGui.getCurrentWindowRead().ptr

    @JvmStatic fun newFrame() {
        index.beginFrame()
        if (names.size > 1024) names.clear()
        if (index.recording) {
            // With multi-viewports ImGui works in desktop coordinates; items are reported in window (mouse) units.
            val viewports = (ImGui.getIO().configFlags and imgui.flag.ImGuiConfigFlags.ViewportsEnable) != 0
            val x = IntArray(1); val y = IntArray(1)
            if (viewports) org.lwjgl.glfw.GLFW.glfwGetWindowPos(dev.harrison.mcinspector.input.InputInjector.window(), x, y)
            originX = x[0].toFloat(); originY = y[0].toFloat()
        }
    }

    @JvmStatic fun render() = index.endFrame()

    /** HEAD of a call that may begin a window: remember the parent window and where the item will sit. */
    @JvmStatic fun beforeWindow() {
        if (!index.recording) return
        parent = currentWindow()
        headX = ImGui.getCursorScreenPosX()
        headY = ImGui.getCursorScreenPosY()
        headItemWidth = ImGui.calcItemWidth()
    }

    /** RETURN of begin/beginChild/beginPopup*: name the window that is now current. */
    @JvmStatic fun window(kind: String, raw: String?, opened: Boolean) {
        if (!index.recording || !opened) return
        val parentName = names[parent]
        names[currentWindow()] = when (kind) {
            "window" -> ImGuiLabels.window(raw ?: "")
            "child" -> parentName ?: "?"
            else -> listOfNotNull(parentName, raw?.let(ImGuiLabels::window) ?: kind).joinToString(" > ")
        }
    }

    /** RETURN of a widget call. [open] is a begin* call's result. */
    @JvmStatic fun item(kind: String, raw: String, open: Boolean?) {
        if (!index.recording) return
        val current = currentWindow()
        if (open == true && (kind == "menu" || kind == "combo") && current != parent) {
            // The popup has begun, so ImGui's "last item" is the popup's title bar: use the cursor from HEAD.
            val parentName = names[parent] ?: "?"
            names[current] = "$parentName > ${ImGuiLabels.window(raw)}"
            val w = if (kind == "combo") headItemWidth else ImGui.calcTextSizeX(raw, true) + 2 * ImGui.getStyle().framePaddingX
            index.record(parentName, kind, raw, headX - originX, headY - originY, w, ImGui.getFrameHeight(), true)
            return
        }
        index.record(names[current] ?: "?", kind, raw, ImGui.getItemRectMinX() - originX, ImGui.getItemRectMinY() - originY,
            ImGui.getItemRectSizeX(), ImGui.getItemRectSizeY(), open)
    }
}
