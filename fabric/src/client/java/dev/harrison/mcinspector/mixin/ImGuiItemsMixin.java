package dev.harrison.mcinspector.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.harrison.mcinspector.imgui.ImGuiHooks;
import imgui.ImGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records the ImGui items every mod draws (labels + rects per frame) so imgui_items / imgui_click can address
 * them by label. imgui-java is a library loaded by Knot, so its static wrappers can be hooked like game code.
 * `name*` selects every overload of `name`; each listed overload takes its label as the first String.
 */
@Mixin(value = ImGui.class, remap = false)
public abstract class ImGuiItemsMixin {
    @Inject(method = "newFrame", at = @At("HEAD"))
    private static void mcinspector$newFrame(CallbackInfo ci) { ImGuiHooks.newFrame(); }

    @Inject(method = "render", at = @At("HEAD"))
    private static void mcinspector$render(CallbackInfo ci) { ImGuiHooks.render(); }

    // ---- windows ------------------------------------------------------------------------------------------

    @Inject(method = {"begin*", "beginChild*", "beginPopup*", "beginPopupModal*", "beginPopupContextItem*",
        "beginPopupContextWindow*", "beginPopupContextVoid*", "beginMainMenuBar*", "beginMenu*", "beginCombo*"},
        at = @At("HEAD"))
    private static void mcinspector$beforeWindow(CallbackInfoReturnable<Boolean> cir) { ImGuiHooks.beforeWindow(); }

    @Inject(method = "begin*", at = @At("RETURN"))
    private static void mcinspector$begin(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.window("window", label, true);
    }

    @Inject(method = "beginChild*", at = @At("RETURN"))
    private static void mcinspector$beginChild(CallbackInfoReturnable<Boolean> cir) { ImGuiHooks.window("child", null, true); }

    @Inject(method = {"beginPopup*", "beginPopupModal*"}, at = @At("RETURN"))
    private static void mcinspector$beginPopup(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.window("popup", label, cir.getReturnValueZ());
    }

    @Inject(method = {"beginPopupContextItem*", "beginPopupContextWindow*", "beginPopupContextVoid*"}, at = @At("RETURN"))
    private static void mcinspector$beginContext(CallbackInfoReturnable<Boolean> cir) { ImGuiHooks.window("context", null, cir.getReturnValueZ()); }

    @Inject(method = "beginMainMenuBar*", at = @At("RETURN"))
    private static void mcinspector$beginMainMenuBar(CallbackInfoReturnable<Boolean> cir) { ImGuiHooks.window("window", "##MainMenuBar", cir.getReturnValueZ()); }

    // ---- items --------------------------------------------------------------------------------------------

    @Inject(method = "beginMenu*", at = @At("RETURN"))
    private static void mcinspector$menu(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.item("menu", label, cir.getReturnValueZ());
    }

    @Inject(method = "beginCombo*", at = @At("RETURN"))
    private static void mcinspector$combo(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.item("combo", label, cir.getReturnValueZ());
    }

    @Inject(method = {"beginTabItem*", "treeNode*", "collapsingHeader*"}, at = @At("RETURN"))
    private static void mcinspector$openable(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.item("openable", label, cir.getReturnValueZ());
    }

    @Inject(method = {"button*", "smallButton*", "invisibleButton*", "arrowButton*", "colorButton*", "imageButton*", "tabItemButton*"},
        at = @At("RETURN"))
    private static void mcinspector$button(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.item("button", label, null);
    }

    @Inject(method = {"selectable*", "menuItem*"}, at = @At("RETURN"))
    private static void mcinspector$selectable(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.item("selectable", label, null);
    }

    @Inject(method = {"checkbox*", "radioButton*"}, at = @At("RETURN"))
    private static void mcinspector$toggle(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.item("toggle", label, null);
    }

    @Inject(method = {"inputText*", "inputTextWithHint*", "inputTextMultiline*", "inputInt*", "inputFloat*",
        "sliderFloat*", "sliderInt*", "dragFloat*", "dragInt*"}, at = @At("RETURN"))
    private static void mcinspector$input(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true, ordinal = 0) String label) {
        ImGuiHooks.item("input", label, null);
    }
}
