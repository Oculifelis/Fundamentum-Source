package com.fundamentum.mixin;

import com.fundamentum.music.client.JukeboxTracker;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.RecordItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link JukeboxTracker} from the jukebox song start (level event 1010) and stop (1011) that vanilla already
 * handles here; nothing else changes. On 1.20.1 both reach Forge's {@code playStreamingMusic(SoundEvent, BlockPos,
 * RecordItem)} (a null sound stops). That overload is Forge's own, so its name is the same in dev and production.
 */
@Mixin(LevelRenderer.class)
public abstract class JukeboxEventsMixin {
    @Inject(method = "playStreamingMusic(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/RecordItem;)V",
        at = @At("HEAD"), remap = false)
    private void fundamentum$music(@Nullable SoundEvent sound, BlockPos pos, @Nullable RecordItem record, CallbackInfo ci) {
        JukeboxTracker.stopped(pos);
        if (sound != null && record != null) {
            JukeboxTracker.started(pos, record);
        }
    }
}
