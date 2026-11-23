package com.fundamentum.mixin;

import com.fundamentum.music.client.JukeboxTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.item.JukeboxSong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link JukeboxTracker} from the jukebox song start (level event 1010) and stop (1011) that vanilla already
 * handles here; nothing else changes.
 */
@Mixin(LevelRenderer.class)
public abstract class JukeboxEventsMixin {
    @Inject(method = "playJukeboxSong", at = @At("TAIL"))
    private void fundamentum$started(Holder<JukeboxSong> song, BlockPos pos, CallbackInfo ci) {
        JukeboxTracker.started(pos, song);
    }

    @Inject(method = "stopJukeboxSong", at = @At("HEAD"))
    private void fundamentum$stopped(BlockPos pos, CallbackInfo ci) {
        JukeboxTracker.stopped(pos);
    }
}
