package com.fundamentum.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test clients open behind whatever Oculifelis is using (Oculifelis: "stop letting test clients take focus"), for any mod's dev
 * run with {@code -Dfundamentum.nofocus=true}. NeoForge's early loading window takes focus before mods load, so test
 * runs also set {@code earlyWindowControl = false} in the run folder's config/fml.toml (and pauseOnLostFocus:false in
 * options.txt). From Ultima Ratio's WindowMixin.
 * <p>
 * The window is also kept off screen: it is created hidden, every {@code setMode} (the constructor's first one re-centres
 * the window on the primary monitor, and later fullscreen toggles) places it at {@link #OFF} and windowed, and it is shown
 * only once it is there, at the end of the constructor.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    private static final int OFF = -4000;

    @Shadow
    public abstract long handle();

    private static boolean fundamentum$noFocus() {
        return Boolean.getBoolean("fundamentum.nofocus");
    }

    @Inject(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
    private static void fundamentum$noFocusHints(CallbackInfoReturnable<Long> cir) {
        if (!fundamentum$noFocus()) {
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_POSITION_X, OFF);
        GLFW.glfwWindowHint(GLFW.GLFW_POSITION_Y, OFF);
    }

    @ModifyArg(method = "setMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V", remap = false), index = 1)
    private long fundamentum$noFullscreen(long monitor) {
        return fundamentum$noFocus() ? 0L : monitor;
    }

    @ModifyArg(method = "setMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V", remap = false), index = 2)
    private int fundamentum$offX(int x) {
        return fundamentum$noFocus() ? OFF : x;
    }

    @ModifyArg(method = "setMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V", remap = false), index = 3)
    private int fundamentum$offY(int y) {
        return fundamentum$noFocus() ? OFF : y;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fundamentum$showOffScreen(CallbackInfo ci) {
        if (!fundamentum$noFocus()) {
            return;
        }
        GLFW.glfwSetWindowPos(handle(), OFF, OFF);
        GLFW.glfwShowWindow(handle());
    }
}
