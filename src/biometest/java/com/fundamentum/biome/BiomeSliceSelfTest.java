package com.fundamentum.biome;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * The biome slice test, only in {@code gradlew runServer -Pbiometest} (own injector) or {@code -Pbiometest=tb} (with
 * TerraBlender on the run classpath), each in a fresh world under build/. It checks the slice report and path, finds each
 * test biome with the game's own biome search, generates the chunk there and reads the biome back, checks the surface
 * rule, times 1M climate lookups with and without the slices, writes biometest-result.txt and stops the server. Log
 * lines start with BIOMETEST.
 */
@Mod.EventBusSubscriber(modid = "fundamentum")
public final class BiomeSliceSelfTest {
    private BiomeSliceSelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        LOG.info("BIOMETEST {}", line);
    }

    private static void note(String what) {
        LINES.add("NOTE " + what);
        LOG.info("BIOMETEST NOTE {}", what);
    }

    private static ResourceKey<Biome> key(String path) {
        return ResourceKey.create(Registries.BIOME, new ResourceLocation("fundamentum_test", path));
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        String mode = System.getProperty("fundamentum.biometest");
        if (mode == null) return;
        MinecraftServer server = event.getServer();
        boolean tb = "tb".equals(mode);
        try {
            run(server, tb);
        } catch (Throwable t) {
            check(false, "test threw " + t);
            LOG.error("BIOMETEST threw", t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("BIOMETEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("biometest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("BIOMETEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static void run(MinecraftServer server, boolean tb) {
        check(TerraBlenderBridge.present() == tb, "TerraBlender loaded: " + TerraBlenderBridge.present() + " (wanted " + tb + ")");
        for (BiomeSlices.Status s : BiomeSlices.status()) note("slice " + s.id() + " active=" + s.active() + " " + s.mode() + ": " + s.detail());
        for (BiomeSlices.DimensionReport d : BiomeSlices.dimensions()) note("dimension " + d.dimension() + ": " + d.path() + ", " + d.pointsBefore() + " -> " + d.pointsAfter());
        for (String r : BiomeSlices.surfaceRules()) note("surface rule " + r);
        for (String id : List.of("shared_plains", "box_biome", "cave_biome", "nether_slice")) {
            check(BiomeSlices.status().stream().anyMatch(s -> s.id().getPath().equals(id) && s.active()), "slice " + id + " is active");
        }
        check(BiomeSlices.status().stream().anyMatch(s -> s.id().getPath().equals("needs_missing_mod") && !s.active()
            && s.detail().contains("no_such_mod_here")), "slice needs_missing_mod is skipped for its missing mod");
        String want = tb ? "TerraBlender region" : "own injector";
        for (String dim : List.of("minecraft:overworld", "minecraft:the_nether")) {
            check(BiomeSlices.dimensions().stream().anyMatch(d -> d.dimension().equals(dim) && d.path().startsWith(want)), dim + " took the path " + want);
        }

        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(Level.NETHER);
        BlockPos plains = find(overworld, key("shared_plains"), 64);
        find(overworld, key("box_biome"), 64);
        find(overworld, key("cave_biome"), 16);
        find(nether, key("nether_slice"), 64);
        if (plains != null) moss(overworld, plains);

        benchmark(server, overworld, tb);
    }

    private static BlockPos find(ServerLevel level, ResourceKey<Biome> biome, int verticalStep) {
        long t0 = System.nanoTime();
        Pair<BlockPos, Holder<Biome>> hit = level.findClosestBiome3d(h -> h.is(biome), BlockPos.ZERO.atY(level.getSeaLevel()), 12800, 32, verticalStep);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (hit == null) {
            check(false, biome.location() + " found by the biome search in " + level.dimension().location());
            return null;
        }
        BlockPos pos = hit.getFirst();
        ChunkAccess chunk = level.getChunk(pos);
        Holder<Biome> placed = chunk.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
        check(placed.is(biome), biome.location() + " generates at " + pos.toShortString() + " in " + level.dimension().location()
            + " (search " + ms + " ms, chunk biome " + placed.unwrapKey().map(k -> k.location().toString()).orElse("?") + ")");
        return pos;
    }

    private static void moss(ServerLevel level, BlockPos near) {
        ChunkAccess chunk = level.getChunk(near);
        int biomeColumns = 0, mossColumns = 0;
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int y = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz);
                BlockPos top = new BlockPos(x0 + dx, y, z0 + dz);
                if (!level.getBiome(top).is(key("shared_plains"))) continue;
                biomeColumns++;
                for (int down = 0; down < 4; down++) {
                    if (level.getBlockState(top.below(down)).is(Blocks.MOSS_BLOCK)) {
                        mossColumns++;
                        break;
                    }
                }
            }
        }
        check(biomeColumns > 0 && mossColumns > 0, "surface rule: moss on " + mossColumns + " of " + biomeColumns
            + " shared_plains columns in chunk " + chunk.getPos());
    }

    private static void benchmark(MinecraftServer server, ServerLevel level, boolean tb) {
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        var presetHolder = server.registryAccess().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getOrThrow(ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD.id()));
        MultiNoiseBiomeSource vanillaSource = MultiNoiseBiomeSource.createFromPreset(presetHolder);
        Climate.ParameterList<Holder<Biome>> vanilla = presetHolder.value().parameters();
        Climate.ParameterList<Holder<Biome>> sliced = source instanceof SlicedBiomeSource s ? s.fundamentum$sliced() : null;

        int n = 50_000; // the whole benchmark runs in one server tick and must stay well under the 60 s watchdog
        int[][] quarts = new int[n][3];
        Climate.TargetPoint[] targets = new Climate.TargetPoint[n];
        SplittableRandom random = new SplittableRandom(1234);
        for (int i = 0; i < n; i++) {
            quarts[i][0] = random.nextInt(-5000, 5000);
            quarts[i][1] = random.nextInt(-16, 80);
            quarts[i][2] = random.nextInt(-5000, 5000);
            targets[i] = sampler.sample(quarts[i][0], quarts[i][1], quarts[i][2]);
        }
        note("benchmark: " + n + " sample points x 4 passes = " + 4 * n + " lookups each, after one warm-up pass; vanilla list "
            + vanilla.values().size() + " points" + (sliced != null ? ", sliced list " + sliced.values().size() + " points" : ""));
        for (int round = 0; round < 2; round++) {
            String suffix = round == 0 ? " (warm-up round)" : "";
            note(String.format("findValue vanilla list: %.1f ns/lookup%s", findValue(vanilla, targets), suffix));
            if (sliced != null) note(String.format("findValue sliced list: %.1f ns/lookup%s", findValue(sliced, targets), suffix));
            note(String.format("getNoiseBiome full sample, vanilla source: %.1f ns/call%s", noiseBiome(vanillaSource, sampler, quarts), suffix));
            note(String.format("getNoiseBiome full sample, this world's source (%s): %.1f ns/call%s",
                tb ? "TerraBlender + our region" : "sliced", noiseBiome(source, sampler, quarts), suffix));
        }
    }

    private static int sink;

    private static double findValue(Climate.ParameterList<Holder<Biome>> list, Climate.TargetPoint[] targets) {
        int h = 0;
        for (Climate.TargetPoint t : targets) h += System.identityHashCode(list.findValue(t));
        long t0 = System.nanoTime();
        for (int pass = 0; pass < 4; pass++) for (Climate.TargetPoint t : targets) h += System.identityHashCode(list.findValue(t));
        long ns = System.nanoTime() - t0;
        sink += h;
        return ns / (4.0 * targets.length);
    }

    private static double noiseBiome(BiomeSource source, Climate.Sampler sampler, int[][] quarts) {
        int h = 0;
        for (int[] q : quarts) h += System.identityHashCode(source.getNoiseBiome(q[0], q[1], q[2], sampler));
        long t0 = System.nanoTime();
        for (int pass = 0; pass < 4; pass++) for (int[] q : quarts) h += System.identityHashCode(source.getNoiseBiome(q[0], q[1], q[2], sampler));
        long ns = System.nanoTime() - t0;
        sink += h;
        return ns / (4.0 * quarts.length);
    }
}
