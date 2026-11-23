package com.fundamentum.material;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * {@code fundamentum:material_swap}: swaps a template's blocks between material sets while it is placed.
 *
 * <pre>{"processor_type": "fundamentum:material_swap",
 *  "from": "minecraft:stone_bricks",                        // the set the template is built in
 *  "to": [{"set": "minecraft:deepslate_bricks", "weight": 3}, {"set": "minecraft:stone_bricks", "weight": 1}],
 *  "palette": "mymod:castle", "role": "wall",               // or draw the candidates from a palette role
 *  "sets": [{"requires": "rechiseled", "swap": {"minecraft:stone_brick": "rechiseled:stone_bricks_pillar"}, "weight": 1}],
 *  "mode": "random", "mix": 0.25, "whole": true, "gate": "castle_block_swaps"}</pre>
 *
 * Each block of the {@code from} set goes to the same slot, shape and wear of the drawn set (missing slots and wears
 * fall back as in {@link MaterialSet#resolve}; when nothing fits, the block stays). {@code sets} takes raw block maps in
 * the Orbis Novus {@code block_swap} format: a key matches a block id exactly or followed by a shape suffix (s, _stairs,
 * _slab, _wall, _fence, _fence_gate); a set counts when its {@code requires} mod is loaded and every requires check
 * passes. Raw sets come first, then {@code to}, then the palette. {@code mode} "first" uses the first usable candidate;
 * "random" draws by weight once per structure, seeded by the world seed and the structure start chunk, and {@code mix}
 * blends a second draw in on that share of positions; the draw is the same as Orbis Novus's block_swap, so a swapped
 * processor rebuilds worlds identically. {@code whole}: a raw set counts only if every block it names exists; a material
 * set only if it is usable for every slot the {@code from} set is usable for. {@code gate} names a switch registered with
 * {@link #registerGate}; off, the processor does nothing. Block properties carry over by name.
 */
public class MaterialSwapProcessor extends StructureProcessor {
    public record RawSet(String requires, Map<String, String> swap, float weight) {
        public static final Codec<RawSet> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("requires").forGetter(RawSet::requires),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("swap").forGetter(RawSet::swap),
            Codec.FLOAT.optionalFieldOf("weight", 1.0f).forGetter(RawSet::weight)
        ).apply(i, RawSet::new));
    }

    public record Target(String set, float weight) {
        public static final Codec<Target> FULL = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("set").forGetter(Target::set),
            Codec.FLOAT.optionalFieldOf("weight", 1.0f).forGetter(Target::weight)
        ).apply(i, Target::new));
        public static final Codec<Target> CODEC = Codec.either(FULL, Codec.STRING).xmap(e -> e.map(t -> t, s -> new Target(s, 1f)), t -> com.mojang.datafixers.util.Either.left(t));
    }

    public static final MapCodec<MaterialSwapProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        Codec.STRING.optionalFieldOf("from", "").forGetter(p -> p.from),
        Target.CODEC.listOf().optionalFieldOf("to", List.of()).forGetter(p -> p.to),
        Codec.STRING.optionalFieldOf("palette", "").forGetter(p -> p.palette),
        Codec.STRING.optionalFieldOf("role", "main").forGetter(p -> p.role),
        RawSet.CODEC.listOf().optionalFieldOf("sets", List.of()).forGetter(p -> p.sets),
        Codec.STRING.optionalFieldOf("gate", "").forGetter(p -> p.gate),
        Codec.BOOL.optionalFieldOf("whole", false).forGetter(p -> p.whole),
        Codec.STRING.optionalFieldOf("mode", "first").forGetter(p -> p.mode),
        Codec.FLOAT.optionalFieldOf("mix", 0f).forGetter(p -> p.mix)
    ).apply(i, MaterialSwapProcessor::new));

    public static final DeferredRegister<StructureProcessorType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, "fundamentum");
    public static final java.util.function.Supplier<StructureProcessorType<MaterialSwapProcessor>> TYPE =
        TYPES.register("material_swap", () -> () -> CODEC.codec());

    private static final Map<String, BooleanSupplier> GATES = new ConcurrentHashMap<>();
    private static final List<Predicate<String>> REQUIRES_CHECKS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static void init(IEventBus modBus) {
        TYPES.register(modBus);
    }

    /** A named switch processors can name in {@code gate} (for example a mod's config option). */
    public static void registerGate(String name, BooleanSupplier on) {
        GATES.put(name, on);
    }

    /** A check on raw sets' {@code requires} (true = allowed), such as a mod's per-mod config switch. */
    public static void registerRequiresCheck(Predicate<String> allowed) {
        REQUIRES_CHECKS.add(allowed);
    }

    private static final List<String> SUFFIXES = List.of("", "s", "_stairs", "_slab", "_wall", "_fence", "_fence_gate");

    private final String from;
    private final List<Target> to;
    private final String palette;
    private final String role;
    private final List<RawSet> sets;
    private final String gate;
    private final boolean whole;
    private final String mode;
    private final float mix;

    private record Active(@Nullable Map<String, String> swap, List<String> keys, float weight, boolean exact) { }

    private static final Active NONE = new Active(null, List.of(), 0f, true);
    private volatile @Nullable List<Active> usable;
    private volatile long builtFor = -1;
    private volatile long salt;
    private final Map<Long, Long> seeds = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> e) {
            return size() > 256;
        }
    };

    public MaterialSwapProcessor(String from, List<Target> to, String palette, String role, List<RawSet> sets, String gate,
            boolean whole, String mode, float mix) {
        this.from = from;
        this.to = to;
        this.palette = palette;
        this.role = role;
        this.sets = sets;
        this.gate = gate;
        this.whole = whole;
        this.mode = mode;
        this.mix = mix;
    }

    /** Swaps one set for another, always. */
    public static MaterialSwapProcessor between(ResourceLocation from, ResourceLocation to) {
        return new MaterialSwapProcessor(from.toString(), List.of(new Target(to.toString(), 1f)), "", "main", List.of(), "", false, "first", 0f);
    }

    private List<Active> usable() {
        long gen = MaterialSets.generation();
        List<Active> u = this.usable;
        if (u != null && this.builtFor == gen) return u;
        u = new ArrayList<>();
        BooleanSupplier g = this.gate.isEmpty() ? null : GATES.get(this.gate);
        if (this.gate.isEmpty() || g != null && g.getAsBoolean()) {
            for (RawSet s : this.sets) {
                if (!ModList.get().isLoaded(s.requires())) continue;
                boolean ok = true;
                for (Predicate<String> c : REQUIRES_CHECKS) if (!c.test(s.requires())) { ok = false; break; }
                if (!ok || this.whole && !s.swap().values().stream().allMatch(MaterialSwapProcessor::exists)) continue;
                List<String> keys = new ArrayList<>(s.swap().keySet());
                keys.sort(Comparator.comparingInt(String::length).reversed());
                u.add(new Active(s.swap(), keys, Math.max(0f, s.weight()), false));
                if (!"random".equals(this.mode)) break;
            }
            Optional<MaterialSet> src = this.from.isEmpty() ? Optional.empty() : MaterialSets.get(new ResourceLocation(this.from));
            if (src.isPresent() && (u.isEmpty() || "random".equals(this.mode))) {
                List<Target> targets = new ArrayList<>(this.to);
                if (!this.palette.isEmpty()) {
                    Palettes.get(new ResourceLocation(this.palette)).ifPresent(p -> {
                        for (Palettes.Weighted w : p.roles().getOrDefault(this.role, List.of())) targets.add(new Target(w.set().toString(), w.weight()));
                    });
                }
                for (Target t : targets) {
                    Optional<MaterialSet> dst = MaterialSets.get(new ResourceLocation(t.set()));
                    if (dst.isEmpty() || this.whole && !covers(src.get(), dst.get())) continue;
                    u.add(new Active(map(src.get(), dst.get()), List.of(), Math.max(0f, t.weight()), true));
                    if (!"random".equals(this.mode)) break;
                }
            }
        }
        // the draw's salt: the first candidate's own keys, as in block_swap
        java.util.Set<String> first = !this.sets.isEmpty() ? this.sets.get(0).swap().keySet() : u.isEmpty() || u.get(0).swap() == null ? java.util.Set.of() : u.get(0).swap().keySet();
        this.salt = String.join(",", new TreeSet<>(first)).hashCode();
        this.usable = u;
        this.builtFor = gen;
        return u;
    }

    private static boolean covers(MaterialSet src, MaterialSet dst) {
        for (String slot : src.slots().keySet()) if (src.usable(slot) && !dst.usable(slot)) return false;
        return true;
    }

    /** Every block of {@code src} to the matching block of {@code dst}. */
    static Map<String, String> map(MaterialSet src, MaterialSet dst) {
        Map<String, String> m = new LinkedHashMap<>();
        src.slots().forEach((slot, shapes) -> shapes.forEach((shape, wears) -> wears.forEach((wear, block) -> {
            String key = MaterialSet.key(block);
            if (m.containsKey(key)) return;
            dst.resolve(slot, shape, wear).ifPresent(st -> m.put(key, MaterialSet.key(st.getBlock())));
        })));
        return m;
    }

    private Active active(LevelReader level, BlockPos offset, BlockPos pos) {
        List<Active> u = usable();
        if (u.isEmpty()) return NONE;
        if (u.size() == 1 || !"random".equals(this.mode)) return u.get(0);
        long sd = seed(level, offset, pos);
        if (this.mix > 0f && (MaterialSets.mix(sd ^ pos.asLong()) >>> 40) < (long) (this.mix * (1L << 24))) {
            return draw(u, sd ^ this.salt ^ 0x5DEECE66DL);
        }
        return draw(u, sd ^ this.salt);
    }

    private static Active draw(List<Active> u, long seed) {
        float total = 0f;
        for (Active a : u) total += a.weight();
        if (total <= 0f) return u.get(0);
        float r = new Random(seed).nextFloat() * total;
        for (Active a : u) {
            r -= a.weight();
            if (r < 0f) return a;
        }
        return u.get(u.size() - 1);
    }

    private long seed(LevelReader level, BlockPos offset, BlockPos pos) {
        long chunk = ((long) (pos.getZ() >> 4) << 32) | ((pos.getX() >> 4) & 0xffffffffL);
        Long known;
        synchronized (this.seeds) {
            known = this.seeds.get(chunk);
        }
        if (known != null) return known;
        long world = level instanceof WorldGenLevel w ? w.getSeed() : 0L;
        int ox = offset.getX() >> 4, oz = offset.getZ() >> 4;
        long start = ((long) oz << 32) | (ox & 0xffffffffL);
        try {
            ChunkAccess c = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.STRUCTURE_REFERENCES, false);
            if (c != null) {
                long best = Long.MAX_VALUE;
                for (var refs : c.getAllReferences().values()) {
                    for (long ref : refs) {
                        long dx = (int) ref - ox, dz = (int) (ref >> 32) - oz;
                        if (dx * dx + dz * dz < best) {
                            best = dx * dx + dz * dz;
                            start = ref;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // outside the generating region: the piece's own chunk stands in
        }
        long s = MaterialSets.mix(world ^ MaterialSets.mix(start));
        synchronized (this.seeds) {
            this.seeds.put(chunk, s);
        }
        return s;
    }

    private static @Nullable String target(Active a, String id) {
        if (a.swap() == null) return null;
        String to = a.swap().get(id);
        if (to != null || a.exact()) return to;
        for (String k : a.keys()) {
            if (id.startsWith(k) && SUFFIXES.contains(id.substring(k.length()))) return a.swap().get(k) + id.substring(k.length());
        }
        return null;
    }

    /** The swapped state for one block at one position (what placement uses), or the state itself. */
    public BlockState swap(LevelReader level, BlockPos offset, BlockPos pos, BlockState state) {
        String t = target(active(level, offset, pos), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (t == null) return state;
        ResourceLocation id = ResourceLocation.tryParse(t);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return state;
        Block b = BuiltInRegistries.BLOCK.get(id);
        BlockState st = b.defaultBlockState();
        for (Property<?> p : state.getProperties()) st = copy(state, st, p);
        return st;
    }

    @Override
    public @Nullable StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos,
            StructureTemplate.StructureBlockInfo original, StructureTemplate.StructureBlockInfo current, StructurePlaceSettings settings) {
        if (current == null) return null;
        BlockState st = swap(level, offset, pos, current.state());
        return st == current.state() ? current : new StructureTemplate.StructureBlockInfo(current.pos(), st, current.nbt());
    }

    private static boolean exists(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.BLOCK.containsKey(rl);
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> p) {
        Property<?> q = to.getBlock().getStateDefinition().getProperty(p.getName());
        if (q == null) return to;
        return q.getValue(p.getName(from.getValue(p))).map(v -> setRaw(to, q, v)).orElse(to);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setRaw(BlockState st, Property q, Object v) {
        return st.setValue(q, (Comparable) v);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE.get();
    }
}
