package com.fundamentum.mixin;

import com.fundamentum.climate.SeasonTint;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The season's grass and leaf tint on the biome colours the client draws with (see {@link SeasonTint}). */
@Mixin(Biome.class)
public abstract class BiomeTintMixin {
    @Inject(method = "getGrassColor", at = @At("RETURN"), cancellable = true)
    private void fundamentum$seasonGrass(double x, double z, CallbackInfoReturnable<Integer> cir) {
        int c = cir.getReturnValueI();
        int t = SeasonTint.grass(c);
        if (t != c) cir.setReturnValue(t);
    }

    @Inject(method = "getFoliageColor", at = @At("RETURN"), cancellable = true)
    private void fundamentum$seasonFoliage(CallbackInfoReturnable<Integer> cir) {
        int c = cir.getReturnValueI();
        int t = SeasonTint.foliage(c);
        if (t != c) cir.setReturnValue(t);
    }
}
