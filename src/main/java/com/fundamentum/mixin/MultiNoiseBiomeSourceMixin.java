package com.fundamentum.mixin;

import com.fundamentum.biome.SlicedBiomeSource;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Biome slices without TerraBlender: once a world loads, the source's list can be swapped for a sliced copy built once.
 * The saved form (the codec reads the original field) is untouched, so level.dat never stores sliced biomes. Named twice
 * (dev name and SRG name, unremapped) so Fundamentum needs no refmap.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin implements SlicedBiomeSource {
    @Shadow(remap = false, aliases = "f_48435_") @Final
    private Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters;
    @Unique private Climate.ParameterList<Holder<Biome>> fundamentum$sliced;

    @Inject(method = {"parameters()Lnet/minecraft/world/level/biome/Climate$ParameterList;",
        "m_274409_()Lnet/minecraft/world/level/biome/Climate$ParameterList;"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$useSliced(CallbackInfoReturnable<Climate.ParameterList<Holder<Biome>>> cir) {
        Climate.ParameterList<Holder<Biome>> sliced = this.fundamentum$sliced;
        if (sliced != null) cir.setReturnValue(sliced);
    }

    @Override
    public Climate.ParameterList<Holder<Biome>> fundamentum$original() {
        return this.parameters.map(direct -> direct, preset -> preset.value().parameters());
    }

    @Override
    public Climate.ParameterList<Holder<Biome>> fundamentum$sliced() {
        return this.fundamentum$sliced;
    }

    @Override
    public void fundamentum$setSliced(Climate.ParameterList<Holder<Biome>> list) {
        this.fundamentum$sliced = list;
    }
}
