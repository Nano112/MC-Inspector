package dev.harrison.mcinspector.mixin;

import dev.harrison.mcinspector.compat.Compat;
import dev.harrison.mcinspector.input.InputInjector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cursor-lock policy (config {@code cursor_lock}: always | never | auto). In "auto" (default) the OS
 * cursor is locked like vanilla, EXCEPT for a few seconds after the MCP host injected input — then the
 * grab is "soft": {@code mouseGrabbed} is set so the camera follows injected look deltas, but the human's
 * cursor stays free. A click from the panel-lib overlay (game focus) always grabs for real.
 */
@Mixin(MouseHandler.class)
public abstract class MouseGrabMixin {
    @Shadow private boolean mouseGrabbed;
    @Shadow private boolean ignoreFirstMove;
    @Shadow private double xpos;
    @Shadow private double ypos;

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void mcinspector$softGrab(CallbackInfo ci) {
        if (InputInjector.shouldLockCursor()) return;
        // A human clicking the game area inside the panel-lib overlay wants a REAL grab (hidden, locked cursor).
        if (dev.harrison.panellib.framework.Overlay.isGameFocus()) return;
        if (!this.mouseGrabbed) {
            Minecraft mc = Minecraft.getInstance();
            this.mouseGrabbed = true;
            this.xpos = mc.getWindow().getScreenWidth() / 2.0;
            this.ypos = mc.getWindow().getScreenHeight() / 2.0;
            Compat.INSTANCE.setScreen(null);
            this.ignoreFirstMove = true;
        }
        ci.cancel();
    }
}
