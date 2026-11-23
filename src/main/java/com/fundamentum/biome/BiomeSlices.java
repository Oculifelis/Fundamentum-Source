package com.fundamentum.biome;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;

/**
 * Biome slices: data files that give a biome part of the world, by splitting a host biome's climate cells or by a
 * climate box, in the overworld or the Nether (format: README, "Biome slices"). Built once as each world loads (just
 * before its levels are made), then generation uses the merged list exactly as vanilla uses its own.
 * <ul>
 * <li>With TerraBlender: each dimension's sliced list becomes one TerraBlender region ({@link TerraBlenderBridge}).</li>
 * <li>Without: a multi-noise dimension's list is swapped for the sliced copy (mixin/MultiNoiseBiomeSourceMixin).</li>
 * <li>A dimension whose biome source is not multi-noise (Orbis Novus's terrain source, for one) logs one line and is
 * skipped.</li>
 * </ul>
 * Mods can switch a slice that names a {@code toggle} with {@link #registerToggle}; {@code fundamentum-biomes.toml} can
 * turn slices off. {@code /fundamentum biomes slices} shows what was placed and how.
 */
public final class BiomeSlices {
    private BiomeSlices() { }

    private static final Logger LOG = LogUtils.getLogger();
    public static final String SLICE_PATH = "fundamentum/biome_slices";
    public static final String SURFACE_PATH = "fundamentum/surface_rules";
    private static final Map<ResourceLocation, BooleanSupplier> TOGGLES = new ConcurrentHashMap<>();

    /** How one slice fared at the last world load. */
    public record Status(ResourceLocation id, String biome, String dimension, String mode, boolean active, String detail) { }

    /** How one dimension was handled: the path taken and the point count before and after. */
    public record DimensionReport(String dimension, String path, int pointsBefore, int pointsAfter) { }

    private static final List<Status> STATUS = new ArrayList<>();
    private static final List<DimensionReport> DIMENSIONS = new ArrayList<>();
    private static final List<String> SURFACE = new ArrayList<>();
    /** "overworld:ns" / "nether:ns" keys we gave TerraBlender surface rules under, removed again on the next load. */
    private static final Set<String> TB_SURFACE_NAMESPACES = new java.util.HashSet<>();

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BiomeSlices::aboutToStart);
        BiomeSlicesCommand.init();
    }

    /** A switch for slices whose file names this toggle id; checked at each world load. Unregistered toggles count as on. */
    public static void registerToggle(ResourceLocation toggle, BooleanSupplier on) {
        TOGGLES.put(toggle, on);
    }

    public static synchronized List<Status> status() {
        return List.copyOf(STATUS);
    }

    public static synchronized List<DimensionReport> dimensions() {
        return List.copyOf(DIMENSIONS);
    }

    public static synchronized List<String> surfaceRules() {
        return List.copyOf(SURFACE);
    }

    private static synchronized void aboutToStart(ServerAboutToStartEvent event) {
        STATUS.clear();
        DIMENSIONS.clear();
        SURFACE.clear();
        MinecraftServer server = event.getServer();
        boolean tb = TerraBlenderBridge.present();
        try {
            apply(server, tb);
        } catch (RuntimeException e) {
            LOG.error("Biome slices: failed, the world generates without them", e);
        }
        try {
            surfaceRules(server, tb);
        } catch (RuntimeException e) {
            LOG.error("Biome slices: surface rules failed, the world generates without them", e);
        }
    }

    private static Map<ResourceLocation, JsonObject> read(ResourceManager manager, String path) {
        FileToIdConverter lister = FileToIdConverter.json(path);
        Map<ResourceLocation, JsonObject> files = new TreeMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : new TreeMap<>(lister.listMatchingResources(manager)).entrySet()) {
            ResourceLocation id = lister.fileToId(entry.getKey());
            try (Reader reader = entry.getValue().openAsReader()) {
                files.put(id, JsonParser.parseReader(reader).getAsJsonObject());
            } catch (Exception e) {
                LOG.warn("Biome slices: {} could not be read ({}), skipped", id, e.toString());
            }
        }
        return files;
    }

    private static String skipReason(SliceSpec spec, ResourceLocation id) {
        if (!spec.enabled()) return "enabled: false";
        for (String mod : spec.requires()) if (!ModList.get().isLoaded(mod)) return "needs mod " + mod;
        if (BiomeSlicesConfig.DISABLED.get().contains(id.toString())) return "off in fundamentum-biomes.toml";
        if (spec.toggle() != null) {
            BooleanSupplier toggle = TOGGLES.get(new ResourceLocation(spec.toggle()));
            if (toggle != null && !toggle.getAsBoolean()) return "toggle " + spec.toggle() + " is off";
        }
        return null;
    }

    private static void apply(MinecraftServer server, boolean tb) {
        Map<ResourceLocation, JsonObject> files = read(server.getResourceManager(), SLICE_PATH);
        Registry<Biome> biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
        Map<String, List<SliceSpec>> byDimension = new LinkedHashMap<>();
        Map<SliceSpec, Holder<Biome>> holders = new LinkedHashMap<>();
        boolean enabled = BiomeSlicesConfig.ENABLED.get();
        for (Map.Entry<ResourceLocation, JsonObject> file : files.entrySet()) {
            ResourceLocation id = file.getKey();
            SliceSpec spec;
            try {
                spec = SliceSpec.parse(id.toString(), file.getValue());
            } catch (RuntimeException e) {
                LOG.warn("Biome slices: {} is not a valid slice ({}), skipped", id, e.getMessage());
                STATUS.add(new Status(id, "?", "?", "?", false, "invalid: " + e.getMessage()));
                continue;
            }
            String skip = enabled ? skipReason(spec, id) : "biome slices are off in fundamentum-biomes.toml";
            Holder<Biome> holder = null;
            if (skip == null) {
                holder = biomes.getHolder(ResourceKey.create(Registries.BIOME, new ResourceLocation(spec.biome()))).orElse(null);
                if (holder == null) skip = "unknown biome " + spec.biome();
            }
            if (skip != null) {
                LOG.info("Biome slices: {} skipped: {}", id, skip);
                STATUS.add(new Status(id, spec.biome(), spec.dimension(), spec.describe(), false, skip));
                continue;
            }
            holders.put(spec, holder);
            byDimension.computeIfAbsent(spec.dimension(), k -> new ArrayList<>()).add(spec);
        }
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        if (tb) {
            for (boolean nether : new boolean[] {false, true}) {
                try {
                    TerraBlenderBridge.remove(nether);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.warn("Biome slices: TerraBlender's region API did not answer ({})", e.toString());
                }
            }
        }
        for (Map.Entry<String, List<SliceSpec>> dim : byDimension.entrySet()) {
            String dimension = dim.getKey();
            List<SliceSpec> specs = dim.getValue();
            LevelStem stem = stems.get(ResourceKey.create(Registries.LEVEL_STEM, new ResourceLocation(dimension)));
            if (stem == null) {
                skipAll(specs, "no dimension " + dimension + " in this world");
                continue;
            }
            ChunkGenerator generator = stem.generator();
            BiomeSource source = generator.getBiomeSource();
            if (!(source instanceof MultiNoiseBiomeSource)) {
                String why = "biome source " + source.getClass().getName() + " takes no slices";
                LOG.info("Biome slices: {} uses {}; its {} slice(s) are skipped", dimension, source.getClass().getName(), specs.size());
                skipAll(specs, why);
                DIMENSIONS.add(new DimensionReport(dimension, "skipped (" + source.getClass().getSimpleName() + ")", 0, 0));
                continue;
            }
            boolean nether = SliceSpec.NETHER.equals(dimension);
            boolean tbPath = tb && (nether || SliceSpec.OVERWORLD.equals(dimension));
            Climate.ParameterList<Holder<Biome>> base = ((SlicedBiomeSource) source).fundamentum$original();
            if (tbPath) {
                // TerraBlender keeps the dimension's own list as its first region; ours is the vanilla preset, sliced
                ResourceKey<MultiNoiseBiomeSourceParameterList> preset = ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                    (nether ? MultiNoiseBiomeSourceParameterList.Preset.NETHER : MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD).id());
                var presetHolder = server.registryAccess().registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getHolder(preset);
                if (presetHolder.isPresent()) base = presetHolder.get().value().parameters();
            }
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> merged = slice(base.values(), specs, holders, dimension,
                tbPath ? "TerraBlender region " + (nether ? TerraBlenderBridge.NETHER_REGION : TerraBlenderBridge.OVERWORLD_REGION) : "own injector");
            if (merged == null) {
                DIMENSIONS.add(new DimensionReport(dimension, "nothing placed", base.values().size(), base.values().size()));
                continue;
            }
            Climate.ParameterList<Holder<Biome>> list = new Climate.ParameterList<>(merged);
            String path;
            if (tbPath) {
                try {
                    TerraBlenderBridge.register(nether, list, BiomeSlicesConfig.TERRABLENDER_WEIGHT.get());
                    path = "TerraBlender region " + (nether ? TerraBlenderBridge.NETHER_REGION : TerraBlenderBridge.OVERWORLD_REGION);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.warn("Biome slices: TerraBlender would not take the {} region ({}); using the own injector", dimension, e.toString());
                    install(generator, source, list);
                    path = "own injector (TerraBlender refused)";
                }
            } else {
                install(generator, source, list);
                path = "own injector";
            }
            DIMENSIONS.add(new DimensionReport(dimension, path, base.values().size(), merged.size()));
            LOG.info("Biome slices: {} via {}, {} -> {} climate points", dimension, path, base.values().size(), merged.size());
        }
    }

    private static void install(ChunkGenerator generator, BiomeSource source, Climate.ParameterList<Holder<Biome>> list) {
        ((SlicedBiomeSource) source).fundamentum$setSliced(list);
        // the multi-noise source's possible biomes are its list's biomes, as its own collectPossibleBiomes reads them
        ((PossibleBiomesReset) source).fundamentum$resetPossibleBiomes(() -> list.values().stream().map(Pair::getSecond));
        ((FeaturesPerStepReset) generator).fundamentum$refreshFeaturesPerStep();
    }

    private static void skipAll(List<SliceSpec> specs, String why) {
        for (SliceSpec spec : specs) {
            STATUS.add(new Status(new ResourceLocation(spec.id()), spec.biome(), spec.dimension(), spec.describe(), false, why));
        }
    }

    private static Predicate<Holder<Biome>> hosts(List<String> with, Holder<Biome> self) {
        List<Predicate<Holder<Biome>>> tests = new ArrayList<>();
        for (String w : with) {
            if (w.startsWith("#")) {
                TagKey<Biome> tag = TagKey.create(Registries.BIOME, new ResourceLocation(w.substring(1)));
                tests.add(h -> h.is(tag));
            } else {
                ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, new ResourceLocation(w));
                tests.add(h -> h.is(key));
            }
        }
        return h -> {
            if (h.equals(self)) return false;
            for (Predicate<Holder<Biome>> t : tests) if (t.test(h)) return true;
            return false;
        };
    }

    /**
     * Applies the slices in id order to one dimension's list. Null when none placed anything (the list is then left as
     * it is). Records each slice's status.
     */
    static List<Pair<Climate.ParameterPoint, Holder<Biome>>> slice(List<Pair<Climate.ParameterPoint, Holder<Biome>>> base,
                                                                   List<SliceSpec> specs, Map<SliceSpec, Holder<Biome>> holders,
                                                                   String dimension, String path) {
        List<SliceMath.Entry<Holder<Biome>>> entries = new ArrayList<>(base.size());
        for (var pair : base) entries.add(new SliceMath.Entry<>(box(pair.getFirst()), pair.getFirst().offset(), pair.getSecond()));
        boolean any = false;
        for (SliceSpec spec : specs) {
            Holder<Biome> biome = holders.get(spec);
            List<SliceMath.Entry<Holder<Biome>>> out = new ArrayList<>(entries.size() + 16);
            int n;
            if (!spec.shareWith().isEmpty()) {
                n = SliceMath.split(entries, out, hosts(spec.shareWith(), biome), spec.climate(), false, spec.axis(), spec.weight(),
                    spec.side(), spec.band(), spec.shift(), biome);
            } else if (spec.placement() == SliceSpec.Placement.SPLIT) {
                n = SliceMath.split(entries, out, h -> !h.equals(biome), spec.climate(), !spec.climateDepth(), spec.axis(),
                    spec.weight(), spec.side(), spec.band(), spec.shift(), biome);
            } else {
                SliceMath.add(entries, out, spec.climate(), spec.offset(), biome);
                n = -1;
            }
            ResourceLocation id = new ResourceLocation(spec.id());
            if (n == 0) {
                LOG.info("Biome slices: {} matched no climate cell in {}, nothing placed", id, dimension);
                STATUS.add(new Status(id, spec.biome(), dimension, spec.describe(), false, "matched no climate cell"));
                continue;
            }
            entries = out;
            any = true;
            STATUS.add(new Status(id, spec.biome(), dimension, spec.describe(), true,
                path + (n > 0 ? ", " + n + " host cell(s) split" : ", box added")));
        }
        if (!any) return null;
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> merged = new ArrayList<>(entries.size());
        for (var e : entries) merged.add(Pair.of(point(e.box(), e.offset()), e.value()));
        return merged;
    }

    static long[] box(Climate.ParameterPoint p) {
        return new long[] {
            p.temperature().min(), p.temperature().max(), p.humidity().min(), p.humidity().max(),
            p.continentalness().min(), p.continentalness().max(), p.erosion().min(), p.erosion().max(),
            p.depth().min(), p.depth().max(), p.weirdness().min(), p.weirdness().max()};
    }

    static Climate.ParameterPoint point(long[] b, long offset) {
        return new Climate.ParameterPoint(new Climate.Parameter(b[0], b[1]), new Climate.Parameter(b[2], b[3]),
            new Climate.Parameter(b[4], b[5]), new Climate.Parameter(b[6], b[7]), new Climate.Parameter(b[8], b[9]),
            new Climate.Parameter(b[10], b[11]), offset);
    }

    // ---- fundamentum:surface_rules ----

    private static void surfaceRules(MinecraftServer server, boolean tb) {
        Map<ResourceLocation, JsonObject> files = read(server.getResourceManager(), SURFACE_PATH);
        if (tb) {
            // drop what we gave TerraBlender on the last load
            for (String key : TB_SURFACE_NAMESPACES) {
                try {
                    TerraBlenderBridge.surfaceRules(key.startsWith("nether:"), key.substring(key.indexOf(':') + 1), null);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.warn("Biome slices: TerraBlender's surface rule API did not answer ({})", e.toString());
                }
            }
            TB_SURFACE_NAMESPACES.clear();
        }
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        // dimension -> namespace -> rules, in file order
        Map<String, Map<String, List<SurfaceRules.RuleSource>>> rules = new TreeMap<>();
        for (Map.Entry<ResourceLocation, JsonObject> file : files.entrySet()) {
            ResourceLocation id = file.getKey();
            JsonObject json = file.getValue();
            String dimension = json.has("dimension") ? json.get("dimension").getAsString() : SliceSpec.OVERWORLD;
            String missing = null;
            if (json.has("requires")) {
                for (var r : json.getAsJsonArray("requires")) if (!ModList.get().isLoaded(r.getAsString())) missing = r.getAsString();
            }
            if (missing != null) {
                SURFACE.add(id + ": skipped, needs mod " + missing);
                continue;
            }
            var result = SurfaceRules.RuleSource.CODEC.parse(ops, json.get("rule"));
            if (result.error().isPresent() || result.result().isEmpty()) {
                String why = result.error().map(e -> e.message()).orElse("no \"rule\"");
                LOG.warn("Biome slices: surface rule {} is not valid ({}), skipped", id, why);
                SURFACE.add(id + ": invalid, " + why);
                continue;
            }
            rules.computeIfAbsent(dimension, k -> new TreeMap<>()).computeIfAbsent(id.getNamespace(), k -> new ArrayList<>())
                .add(result.result().get());
            SURFACE.add(id + ": " + dimension);
        }
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        for (Map.Entry<String, Map<String, List<SurfaceRules.RuleSource>>> dim : rules.entrySet()) {
            String dimension = dim.getKey();
            boolean nether = SliceSpec.NETHER.equals(dimension);
            if (tb && (nether || SliceSpec.OVERWORLD.equals(dimension))) {
                // TerraBlender picks rules by the namespace of the biome being surfaced, so the rules go under the namespace
                // of every active slice biome in this dimension (and of the rule files), unless another mod already holds it
                List<SurfaceRules.RuleSource> all = new ArrayList<>();
                dim.getValue().values().forEach(all::addAll);
                Set<String> namespaces = new TreeSet<>(dim.getValue().keySet());
                for (Status st : STATUS) {
                    if (st.active() && dimension.equals(st.dimension())) namespaces.add(new ResourceLocation(st.biome()).getNamespace());
                }
                SurfaceRules.RuleSource rule = sequence(all);
                for (String ns : namespaces) {
                    try {
                        if (TerraBlenderBridge.hasSurfaceRules(nether, ns)) {
                            LOG.info("Biome slices: TerraBlender already has surface rules for namespace {} in {}, ours are not added there", ns, dimension);
                            SURFACE.add(dimension + ": namespace " + ns + " skipped, TerraBlender rules of another mod");
                            continue;
                        }
                        TerraBlenderBridge.surfaceRules(nether, ns, rule);
                        TB_SURFACE_NAMESPACES.add((nether ? "nether:" : "overworld:") + ns);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        LOG.warn("Biome slices: TerraBlender would not take the surface rules for {} ({})", dimension, e.toString());
                    }
                }
                LOG.info("Biome slices: surface rules for {} via TerraBlender", dimension);
                continue;
            }
            LevelStem stem = stems.get(ResourceKey.create(Registries.LEVEL_STEM, new ResourceLocation(dimension)));
            if (stem == null || !(stem.generator() instanceof NoiseBasedChunkGenerator noise)) {
                LOG.info("Biome slices: surface rules for {} skipped, {}", dimension,
                    stem == null ? "no such dimension" : "its generator " + stem.generator().getClass().getName() + " has no surface rules");
                continue;
            }
            List<SurfaceRules.RuleSource> all = new ArrayList<>();
            dim.getValue().values().forEach(all::addAll);
            ((SlicedSurfaceRules) (Object) noise.generatorSettings().value()).fundamentum$prependSurfaceRule(sequence(all));
            LOG.info("Biome slices: {} surface rule file(s) put before {}'s own", all.size(), dimension);
        }
    }

    private static SurfaceRules.RuleSource sequence(List<SurfaceRules.RuleSource> rules) {
        return rules.size() == 1 ? rules.get(0) : SurfaceRules.sequence(rules.toArray(SurfaceRules.RuleSource[]::new));
    }
}
