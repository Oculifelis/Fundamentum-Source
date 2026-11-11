package com.fundamentum.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test clients open behind whatever Oculifelis is using, for any mod's dev run with {@code -Dfundamentum.nofocus=true}.
 * Forge's early loading window takes focus before mods load, so test runs also set {@code earlyWindowControl = false} in
 * the run folder's config/fml.toml (and pauseOnLostFocus:false in options.txt).
 * <p>
 * The window is created hidden and unfocused (the hints go in right before Forge's
 * {@code ImmediateWindowHandler.setupMinecraftWindow}, which creates it; the constructor resets all hints before that, so
 * they cannot be set any earlier), every {@code setMode} places it at {@link #OFF} and windowed, and it is shown only once
 * it is there, at the end of the constructor. 1.20.1's GLFW has no position hints, so the window is moved after creation.
 * <p>
 * No refmap: targets name the official and SRG names with remap off. Every injector is optional, so a miss only loses
 * the no-focus behaviour.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    private static final int OFF = -4000;
    private static final String MONITOR = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V";

    private static boolean fundamentum$noFocus() {
        return Boolean.getBoolean("fundamentum.nofocus");
    }

    @Inject(method = "<init>", remap = false, require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private void fundamentum$noFocusHints(CallbackInfo ci) {
        if (!fundamentum$noFocus()) {
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }

    @ModifyArg(method = {"setMode", "m_85453_"}, remap = false, require = 0, at = @At(value = "INVOKE", target = MONITOR, remap = false), index = 1)
    private long fundamentum$noFullscreen(long monitor) {
        return fundamentum$noFocus() ? 0L : monitor;
    }

    @ModifyArg(method = {"setMode", "m_85453_"}, remap = false, require = 0, at = @At(value = "INVOKE", target = MONITOR, remap = false), index = 2)
    private int fundamentum$offX(int x) {
        return fundamentum$noFocus() ? OFF : x;
    }

    @ModifyArg(method = {"setMode", "m_85453_"}, remap = false, require = 0, at = @At(value = "INVOKE", target = MONITOR, remap = false), index = 3)
    private int fundamentum$offY(int y) {
        return fundamentum$noFocus() ? OFF : y;
    }

    @Inject(method = "<init>", remap = false, require = 0, at = @At("TAIL"))
    private void fundamentum$showOffScreen(CallbackInfo ci) {
        if (!fundamentum$noFocus()) {
            return;
        }
        long handle = ((Window) (Object) this).getWindow();
        GLFW.glfwSetWindowPos(handle, OFF, OFF);
        GLFW.glfwShowWindow(handle);
    }
}
