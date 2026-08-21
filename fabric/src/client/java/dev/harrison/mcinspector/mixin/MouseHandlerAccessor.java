package dev.harrison.mcinspector.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    //? if >=1.21.9 {
    @Invoker("onButton")
    void mcinspector$onButton(long window, net.minecraft.client.input.MouseButtonInfo info, int action);
    //?} else {
    /*@Invoker("onPress")
    void mcinspector$onPress(long window, int button, int action, int mods);
    *///?}

    @Invoker("onMove")
    void mcinspector$onMove(long window, double x, double y);

    @Invoker("onScroll")
    void mcinspector$onScroll(long window, double dx, double dy);
}
