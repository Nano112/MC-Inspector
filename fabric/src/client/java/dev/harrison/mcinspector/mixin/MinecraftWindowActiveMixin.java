package dev.harrison.mcinspector.mixin;

import dev.harrison.mcinspector.input.InputInjector;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the MCP host is driving the game, report the window as focused.
 * Mouse look, drag/hover handling and "pause on lost focus" are all gated on
 * {@link Minecraft#isWindowActive()}; an agent controls the instance from
 * another process, so the OS focus state must not change game behaviour.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftWindowActiveMixin {
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void mcinspector$forceActive(CallbackInfoReturnable<Boolean> cir) {
        if (InputInjector.forceWindowActive) cir.setReturnValue(true);
    }
}
