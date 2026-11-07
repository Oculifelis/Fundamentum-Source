package com.fundamentum.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test clients open behind whatever Oculifelis is using (Oculifelis: "stop letting test clients take focus"), for any mod's dev
 * run with {@code -Dfundamentum.nofocus=true}. On 1.21.1 FML creates the window ({@code ImmediateWindowHandler}), so the
 * hints are set just before Minecraft asks it to; test runs also set {@code earlyWindowControl = false} in the run
 * folder's config/fml.toml (and pauseOnLostFocus:false in options.txt).
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J", remap = false))
    private void fundamentum$noFocus(CallbackInfo ci) {
        if (!Boolean.getBoolean("fundamentum.nofocus")) {
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
    }
}
