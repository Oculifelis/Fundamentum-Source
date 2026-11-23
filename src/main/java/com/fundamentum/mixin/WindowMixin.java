package com.fundamentum.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
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
 * 26.3 makes its window with SDL 3, not GLFW. The window is already created hidden (NeoForge shows it after the early
 * window hands over); before that, SDL is told not to activate a window when it is shown or raised. Every placement
 * ({@code setWindowSizeAndPosition}) goes to {@link #OFF}, fullscreen requests are dropped, and the end of the
 * constructor moves it off screen once more. Not tried in a client yet (see PORTING.md, "26.3 at 1.25.1").
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    private static final int OFF = -4000;

    @Shadow
    public abstract long handle();

    @Shadow
    private boolean fullscreenRequested;

    private static boolean fundamentum$noFocus() {
        return Boolean.getBoolean("fundamentum.nofocus");
    }

    @Inject(method = "createWindow", at = @At("HEAD"))
    private void fundamentum$noFocusHints(CallbackInfoReturnable<Long> cir) {
        if (!fundamentum$noFocus()) {
            return;
        }
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_RAISED, "0");
    }

    @Inject(method = "setMode", at = @At("HEAD"))
    private void fundamentum$noFullscreen(CallbackInfo ci) {
        if (fundamentum$noFocus()) {
            this.fullscreenRequested = false;
        }
    }

    @ModifyArg(method = "setWindowSizeAndPosition", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_SetWindowPosition(JII)Z", remap = false), index = 1)
    private int fundamentum$offX(int x) {
        return fundamentum$noFocus() ? OFF : x;
    }

    @ModifyArg(method = "setWindowSizeAndPosition", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_SetWindowPosition(JII)Z", remap = false), index = 2)
    private int fundamentum$offY(int y) {
        return fundamentum$noFocus() ? OFF : y;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fundamentum$offScreen(CallbackInfo ci) {
        if (!fundamentum$noFocus()) {
            return;
        }
        SDLVideo.SDL_SetWindowPosition(handle(), OFF, OFF);
    }
}
