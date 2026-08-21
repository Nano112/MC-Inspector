package dev.harrison.mcinspector.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private GLFW-callback targets so injected input follows the exact
 * same path as real keyboard input (screens, key mappings, other mods' mixins).
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {
    //? if >=1.21.9 {
    @Invoker("keyPress")
    void mcinspector$keyPress(long window, int action, net.minecraft.client.input.KeyEvent event);

    @Invoker("charTyped")
    void mcinspector$charTyped(long window, net.minecraft.client.input.CharacterEvent event);
    //?} else {
    /*@Invoker("keyPress")
    void mcinspector$keyPress(long window, int key, int scancode, int action, int mods);

    @Invoker("charTyped")
    void mcinspector$charTyped(long window, int codepoint, int mods);
    *///?}
}
