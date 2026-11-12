package com.fundamentum.hudshot.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test runs only (-Phudshot): the off-screen client never captures the cursor. */
@Mixin(MouseHandler.class)
public class NoGrabMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$noGrab(CallbackInfo ci) {
        ci.cancel();
    }
}
