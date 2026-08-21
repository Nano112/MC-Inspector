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
 * While the MCP host drives the game (config {@code lock_cursor=false}, the default), never lock or
 * hide the OS cursor: the agent's look input is injected at the MouseHandler level, and the human
 * sharing the machine must keep their mouse. We still flip the {@code mouseGrabbed} flag so MC
 * routes movement to the camera exactly as if the cursor were captured.
 */
@Mixin(MouseHandler.class)
public abstract class MouseGrabMixin {
    @Shadow private boolean mouseGrabbed;
    @Shadow private boolean ignoreFirstMove;
    @Shadow private double xpos;
    @Shadow private double ypos;

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void mcinspector$softGrab(CallbackInfo ci) {
        if (InputInjector.lockCursor) return;
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
