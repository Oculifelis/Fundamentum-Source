package com.fundamentum.mixin;

import com.fundamentum.biome.SlicedBiomeSource;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
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

/**
 * Biome slices without TerraBlender: once a world loads, the source's list can be swapped for a sliced copy built once.
 * The saved form (the codec reads the original field) is untouched, so level.dat never stores sliced biomes.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin implements SlicedBiomeSource {
    @Shadow @Final private Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters;
    @Unique private Climate.ParameterList<Holder<Biome>> fundamentum$sliced;

    @ModifyReturnValue(method = "parameters()Lnet/minecraft/world/level/biome/Climate$ParameterList;", at = @At("RETURN"))
    private Climate.ParameterList<Holder<Biome>> fundamentum$useSliced(Climate.ParameterList<Holder<Biome>> original) {
        Climate.ParameterList<Holder<Biome>> sliced = this.fundamentum$sliced;
        return sliced != null ? sliced : original;
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
