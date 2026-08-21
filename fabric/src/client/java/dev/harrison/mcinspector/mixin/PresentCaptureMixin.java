package dev.harrison.mcinspector.mixin;

import dev.harrison.mcinspector.capture.FrameCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the moment right before {@code glfwSwapBuffers}: FBO 0 holds the complete presented frame,
 * including overlays drawn by other mods' present-time hooks (they inject at HEAD, which runs
 * earlier). Captures are read here so the screenshot tool returns what the player actually sees.
 *
 * &lt;26.2: RenderSystem.flipFrame(...) calls glfwSwapBuffers. 26.2: GlSurface.present().
 */
//? if >=26.2 {
/*@Mixin(com.mojang.blaze3d.opengl.GlSurface.class)
*///?} else {
@Mixin(com.mojang.blaze3d.systems.RenderSystem.class)
//?}
public class PresentCaptureMixin {
    //? if >=26.2 {
    /*@Inject(method = "present", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"), require = 1)
    private void mcinspector$beforeSwap(CallbackInfo ci) {
        FrameCapture.onBeforeSwap();
    }
    *///?} else {
    @Inject(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"), require = 1)
    private static void mcinspector$beforeSwap(CallbackInfo ci) {
        FrameCapture.onBeforeSwap();
    }
    //?}
}
