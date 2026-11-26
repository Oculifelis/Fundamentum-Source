package com.fundamentum.mixin;

import com.fundamentum.stat.Stats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link Stats} when a player's vanilla statistic changes, so vanilla_stat stats update without polling. */
@Mixin(ServerStatsCounter.class)
public abstract class StatCounterMixin {
    @Inject(method = "setValue", at = @At("TAIL"))
    private void fundamentum$changed(Player player, Stat<?> stat, int value, CallbackInfo ci) {
        if (player instanceof ServerPlayer p) Stats.vanillaStatChanged(p, stat);
    }
}
