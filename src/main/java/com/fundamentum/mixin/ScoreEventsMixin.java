package com.fundamentum.mixin;

import com.fundamentum.stat.Stats;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.ScoreHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link Stats} when a score changes, so scoreboard stats update without polling. */
@Mixin(ServerScoreboard.class)
public abstract class ScoreEventsMixin {
    @Inject(method = "onScoreChanged", at = @At("TAIL"))
    private void fundamentum$changed(ScoreHolder holder, Objective objective, Score score, CallbackInfo ci) {
        Stats.scoreChanged(holder.getScoreboardName());
    }
}
