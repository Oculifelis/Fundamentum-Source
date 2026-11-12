package com.fundamentum.port;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

/**
 * Climate calls that differ by loader or version: a biome's id, its rainfall, whether a mod is loaded, and hooks
 * around crop growth. Used by {@code com.fundamentum.climate}.
 */
public final class FClimate {
    private FClimate() { }

    /** Decides one crop growth step: null leaves it to the game, false stops it, true makes it grow. */
    @FunctionalInterface
    public interface CropRule {
        Boolean allow(ServerLevel level, BlockPos pos, BlockState state);
    }

    /** Runs after a crop grew a step. */
    @FunctionalInterface
    public interface CropGrown {
        void grown(ServerLevel level, BlockPos pos, BlockState before, BlockState after);
    }

    private static final List<CropRule> RULES = new CopyOnWriteArrayList<>();
    private static final List<CropGrown> GROWN = new CopyOnWriteArrayList<>();

    public static void init(IEventBus modBus) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((CropGrowEvent.Pre e) -> {
            if (!(e.getLevel() instanceof ServerLevel level)) return;
            Boolean allow = preGrow(level, e.getPos(), e.getState());
            if (allow != null) e.setResult(allow ? CropGrowEvent.Pre.Result.GROW : CropGrowEvent.Pre.Result.DO_NOT_GROW);
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((CropGrowEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel level) postGrow(level, e.getPos(), e.getOriginalState(), e.getState());
        });
    }

    public static void cropRule(CropRule rule) {
        RULES.add(rule);
    }

    public static void cropGrown(CropGrown hook) {
        GROWN.add(hook);
    }

    /** What the crop rules say about one growth step; the first rule with an answer wins. Public for tests. */
    public static Boolean preGrow(ServerLevel level, BlockPos pos, BlockState state) {
        for (CropRule r : RULES) {
            Boolean b = r.allow(level, pos, state);
            if (b != null) return b;
        }
        return null;
    }

    /** Runs the after-growth hooks. Public for tests. */
    public static void postGrow(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        for (CropGrown g : GROWN) g.grown(level, pos, before, after);
    }

    /** The biome's id, or null for one that is not registered. */
    public static FId biomeId(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> FId.from(k.location())).orElse(null);
    }

    /** The level's dimension id. */
    public static FId dimensionId(Level level) {
        return FId.from(level.dimension().location());
    }

    /** The registered biome with that id, or null. */
    public static Holder<Biome> biome(Level level, FId id) {
        return level.registryAccess().lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME, id.vanilla()))
            .map(h -> (Holder<Biome>) h).orElse(null);
    }

    /** The biome's rainfall (downfall), after loader biome modifiers. */
    public static float downfall(Biome biome) {
        return biome.getModifiedClimateSettings().downfall();
    }

    public static boolean modLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }
}
