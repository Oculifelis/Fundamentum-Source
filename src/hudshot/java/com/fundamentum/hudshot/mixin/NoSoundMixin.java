package com.fundamentum.hudshot.mixin;

import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test runs only (-Phudshot): the sound library never opens, so the client cannot make a sound. */
@Mixin(SoundEngine.class)
public class NoSoundMixin {
    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private void fundamentum$noSound(CallbackInfo ci) {
        ci.cancel();
    }
}
