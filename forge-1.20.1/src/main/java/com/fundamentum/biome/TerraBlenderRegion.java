package com.fundamentum.biome;

import com.mojang.datafixers.util.Pair;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.api.RegionType;

/** Our TerraBlender region: the sliced climate list as it is. Only loaded when TerraBlender is installed. */
final class TerraBlenderRegion extends Region {
    private final List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> points;

    private TerraBlenderRegion(ResourceLocation name, boolean nether, int weight, List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> points) {
        super(name, nether ? RegionType.NETHER : RegionType.OVERWORLD, weight);
        this.points = List.copyOf(points);
    }

    static Object create(ResourceLocation name, boolean nether, int weight, List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> points) {
        return new TerraBlenderRegion(name, nether, weight, points);
    }

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        points.forEach(mapper);
    }
}
