package dev.harrison.mcinspector.input

import com.mojang.blaze3d.platform.InputConstants
import dev.harrison.mcinspector.mixin.KeyboardHandlerAccessor
import dev.harrison.mcinspector.mixin.MouseHandlerAccessor
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/**
 * Injects input at the MC handler layer (the methods GLFW callbacks hit), so it
 * reaches the current Screen, KeyMappings and every other mod's input mixins.
 * All functions must run on the render thread.
 */
object InputInjector {
    /** When true, Minecraft.isWindowActive() reports true regardless of OS focus (see MinecraftWindowActiveMixin). */
    @JvmField @Volatile var forceWindowActive: Boolean = false
    /** Cursor-lock policy, see [InspectorConfig.cursorLock]: "always" | "never" | "auto". */
    @JvmField @Volatile var cursorLock: String = "auto"
    /** Epoch ms of the last injected input (auto mode keeps the OS cursor free for a few seconds after it). */
    @JvmField @Volatile var lastInjectedAt: Long = 0
    const val AUTO_WINDOW_MS = 8_000L

    /** Should MouseHandler.grabMouse really lock/hide the OS cursor right now? */
    @JvmStatic fun shouldLockCursor(): Boolean = when (cursorLock) {
        "always" -> true
        "never" -> false
        else -> System.currentTimeMillis() - lastInjectedAt > AUTO_WINDOW_MS
    }
    private fun touch() { lastInjectedAt = System.currentTimeMillis() }

    private val mc get() = Minecraft.getInstance()
    private val kb get() = mc.keyboardHandler as KeyboardHandlerAccessor
    private val mouse get() = mc.mouseHandler as MouseHandlerAccessor

    fun window(): Long {
        //? if >=1.21.9 {
        return mc.window.handle()
        //?} else {
        /*return mc.window.getWindow()
        *///?}
    }

    fun keyDown(key: Int, mods: Int = 0) = keyAction(key, KeyNames.PRESS, mods)
    fun keyUp(key: Int, mods: Int = 0) = keyAction(key, KeyNames.RELEASE, mods)

    private fun keyAction(key: Int, action: Int, mods: Int) {
        touch()
        //? if >=1.21.9 {
        kb.`mcinspector$keyPress`(window(), action, net.minecraft.client.input.KeyEvent(key, 0, mods))
        //?} else {
        /*kb.`mcinspector$keyPress`(window(), key, 0, action, mods)
        *///?}
    }

    fun char(codepoint: Int, mods: Int = 0) {
        touch()
        //? if >=26.1 {
        /*kb.`mcinspector$charTyped`(window(), net.minecraft.client.input.CharacterEvent(codepoint))
        *///?} elif >=1.21.9 {
        kb.`mcinspector$charTyped`(window(), net.minecraft.client.input.CharacterEvent(codepoint, mods))
        //?} else {
        /*kb.`mcinspector$charTyped`(window(), codepoint, mods)
        *///?}
    }

    fun mouseMove(x: Double, y: Double) { touch(); mouse.`mcinspector$onMove`(window(), x, y) }

    fun mouseDown(button: Int, mods: Int = 0) = mouseAction(button, KeyNames.PRESS, mods)
    fun mouseUp(button: Int, mods: Int = 0) = mouseAction(button, KeyNames.RELEASE, mods)

    private fun mouseAction(button: Int, action: Int, mods: Int) {
        touch()
        //? if >=1.21.9 {
        mouse.`mcinspector$onButton`(window(), net.minecraft.client.input.MouseButtonInfo(button, mods), action)
        //?} else {
        /*mouse.`mcinspector$onPress`(window(), button, action, mods)
        *///?}
    }

    fun scroll(dx: Double, dy: Double) { touch(); mouse.`mcinspector$onScroll`(window(), dx, dy) }

    /** Directly set the KeyMapping state so gameplay bindings (movement, sneak) engage while held. */
    fun keyMappingSet(key: Int, down: Boolean) {
        KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down)
    }

    /** Framebuffer pixels per logical (mouse) unit — 2.0 on Retina, 1.0 elsewhere. */
    fun pixelRatio(): Double {
        val fbw = IntArray(1); val fbh = IntArray(1)
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window(), fbw, fbh)   // real size even when panel-lib spoofs MC's
        return fbw[0].toDouble() / mc.window.screenWidth.toDouble()
    }

    /** GUI-scaled coordinate -> logical mouse coordinate. */
    fun guiToMouse(g: Double): Double = g * mc.window.screenWidth.toDouble() / mc.window.guiScaledWidth.toDouble()

    /** Names + down-state of every key mapping bound to [key] (diagnostics for agents). */
    fun mappingsFor(key: Int): com.google.gson.JsonArray = com.google.gson.JsonArray().apply {
        mc.options.keyMappings.filter { km ->
            //? if >=1.21.9 {
            km.matches(net.minecraft.client.input.KeyEvent(key, 0, 0))
            //?} else {
            /*km.matches(key, 0)
            *///?}
        }.forEach { km ->
            add(dev.harrison.mcinspector.mcp.obj { "name" to km.name; "down" to km.isDown })
        }
    }

    fun mouseX(): Double = mc.mouseHandler.xpos()
    fun mouseY(): Double = mc.mouseHandler.ypos()
}
