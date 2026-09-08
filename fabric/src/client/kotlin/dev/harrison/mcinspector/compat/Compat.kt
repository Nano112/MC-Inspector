package dev.harrison.mcinspector.compat

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/** Version shims. Keep every Stonecutter split that is used from more than one place here. */
object Compat {
    private val mc get() = Minecraft.getInstance()

    /** 26.2 split the in-world HUD from Gui's screen/overlay management. */
    fun overlayMessage(message: net.minecraft.network.chat.Component) {
        //? if >=26.2 {
        /*mc.gui.hud.setOverlayMessage(message, false)
        *///?} else {
        mc.gui.setOverlayMessage(message, false)
        //?}
    }

    /** The open screen (menu) or null. 26.2 moved screen management from Minecraft onto Gui. */
    fun screen(): Screen? {
        //? if >=26.2 {
        /*return mc.gui.screen()
        *///?} else {
        return mc.screen
        //?}
    }

    /** The main framebuffer. 26.2 moved it onto GameRenderer. */
    fun mainRenderTarget(): com.mojang.blaze3d.pipeline.RenderTarget {
        //? if >=26.2 {
        /*return mc.gameRenderer.mainRenderTarget()
        *///?} else {
        return mc.mainRenderTarget
        //?}
    }

    fun setScreen(screen: Screen?) {
        //? if >=26.2 {
        /*mc.gui.setScreen(screen)
        *///?} else {
        mc.setScreen(screen)
        //?}
    }
}
