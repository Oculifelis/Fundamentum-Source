package com.fundamentum.material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * One material set: set -> slot -> shape -> wear -> block, plus metadata (kind, family, source mod, weight, theme tags,
 * biome tags, dimension, average colour, suggested light and bars). Slots are free words; the usual ones are listed in
 * {@link MaterialSets#SLOT_FALLBACK}. Shapes are {@code base, stairs, slab, wall, fence, fence_gate} (plus door, trapdoor,
 * button, pressure_plate for wood). Wears are {@code cracked, mossy, chiseled, polished, smooth, cut}, "" being plain.
 */
public final class MaterialSet {
    public enum Kind { STONE, WOOD, PLASTER, METAL, OTHER;
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind parse(String s) {
            return valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    public static final List<String> SHAPES = List.of("base", "stairs", "slab", "wall", "fence", "fence_gate", "door", "trapdoor", "button", "pressure_plate");
    public static final List<String> WEARS = List.of("cracked", "mossy", "chiseled", "polished", "smooth", "cut");

    private final ResourceLocation id;
    private final Kind kind;
    private final String family;
    private final String source;
    private final float weight;
    private final Set<String> tags;
    private final Set<String> biomeTags;
    private final String dimension;
    private final int rgb;
    private final float[] lab;
    private final @Nullable Block light;
    private final @Nullable Block bars;
    private final boolean detected;
    /** slot -> shape -> wear -> block */
    private final Map<String, Map<String, Map<String, Block>>> slots;

    MaterialSet(Builder b, int rgb) {
        this.id = b.id;
        this.kind = b.kind;
        this.family = b.family != null ? b.family : b.id.toString();
        this.source = b.source != null ? b.source : b.id.getNamespace();
        this.weight = b.weight;
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(b.tags));
        this.biomeTags = Collections.unmodifiableSet(new LinkedHashSet<>(b.biomeTags));
        this.dimension = b.dimension;
        this.rgb = rgb;
        this.lab = MaterialColor.lab(rgb);
        this.light = b.light;
        this.bars = b.bars;
        this.detected = b.detected;
        Map<String, Map<String, Map<String, Block>>> s = new TreeMap<>();
        b.slots.forEach((slot, shapes) -> {
            Map<String, Map<String, Block>> sh = new TreeMap<>();
            shapes.forEach((shape, wears) -> {
                if (!wears.isEmpty()) sh.put(shape, Collections.unmodifiableMap(new TreeMap<>(wears)));
            });
            if (!sh.isEmpty()) s.put(slot, Collections.unmodifiableMap(sh));
        });
        this.slots = Collections.unmodifiableMap(s);
    }

    public ResourceLocation id() { return this.id; }
    public Kind kind() { return this.kind; }
    /** Sets of one family stand in for each other (a wear's own set names its parent's family). */
    public String family() { return this.family; }
    /** The mod the blocks come from. */
    public String source() { return this.source; }
    public float weight() { return this.weight; }
    /** Free theme words (colour words, "bricks", "castle", the kind, ...). */
    public Set<String> tags() { return this.tags; }
    /** Biome ids or #tags this set suits; empty = any biome. */
    public Set<String> biomeTags() { return this.biomeTags; }
    /** "overworld", or "nether", "end", "sky", "magic" for alien sets. */
    public String dimension() { return this.dimension; }
    /** True for sets that do not belong in overworld buildings (nether, end, sky, magic). */
    public boolean alien() { return !"overworld".equals(this.dimension); }
    /** Average colour, 0xRRGGBB. */
    public int rgb() { return this.rgb; }
    public String hex() { return String.format(Locale.ROOT, "#%06x", this.rgb); }
    /** CIE Lab (D65): L 0..100, a, b. */
    public float[] lab() { return this.lab.clone(); }
    /** Lab lightness, 0..100. */
    public float lightness() { return this.lab[0]; }
    public Optional<Block> light() { return Optional.ofNullable(this.light); }
    public Optional<Block> bars() { return Optional.ofNullable(this.bars); }
    /** True when found by the scan (not only from code or data). */
    public boolean detected() { return this.detected; }
    public Map<String, Map<String, Map<String, Block>>> slots() { return this.slots; }
    public boolean isVanilla() { return "minecraft".equals(this.id.getNamespace()); }

    /** The slot's own block for a shape and wear (a missing wear falls back to plain), strictly: no slot or shape fallback. */
    public Optional<BlockState> block(String slot, String shape, String wear) {
        Block b = raw(slot, shape, wear);
        return b == null ? Optional.empty() : Optional.of(b.defaultBlockState());
    }

    public Optional<BlockState> block(String slot, String shape) {
        return block(slot, shape, "");
    }

    /**
     * Like {@link #block} but missing slots follow {@link MaterialSets#SLOT_FALLBACK} (wall_accent -> wall -> main, ...),
     * and a missing shape other than wall or fence falls back to base; a missing wall, fence or fence gate stays empty
     * (the caller keeps vanilla).
     */
    public Optional<BlockState> resolve(String slot, String shape, String wear) {
        String s = slotOf(slot);
        if (s == null) return Optional.empty();
        Block b = raw(s, shape, wear);
        if (b == null && !shape.startsWith("wall") && !shape.startsWith("fence") && !"base".equals(shape) && SHAPES.contains(shape)
                && !"door".equals(shape) && !"trapdoor".equals(shape) && !"button".equals(shape) && !"pressure_plate".equals(shape)) {
            b = raw(s, "base", wear);
        }
        return b == null ? Optional.empty() : Optional.of(b.defaultBlockState());
    }

    /** The slot that answers for {@code slot}: itself if present, else its fallback chain; null when none. */
    public @Nullable String slotOf(String slot) {
        String s = slot;
        for (int guard = 0; s != null && guard < 16; guard++) {
            if (this.slots.containsKey(s)) return s;
            s = MaterialSets.SLOT_FALLBACK.get(s);
        }
        return null;
    }

    /** Usable: the slot (after fallback) has base, stairs and slab. */
    public boolean usable(String slot) {
        String s = slotOf(slot);
        if (s == null) return false;
        Map<String, Map<String, Block>> sh = this.slots.get(s);
        return sh.containsKey("base") && sh.containsKey("stairs") && sh.containsKey("slab");
    }

    /** Usable for every slot listed. */
    public boolean usable(List<String> slots) {
        for (String s : slots) if (!usable(s)) return false;
        return true;
    }

    private volatile @Nullable Map<Block, MaterialSets.Location> index;

    /** Where a block sits in this set (main slot and plain wear first), or null. */
    public @Nullable MaterialSets.Location locate(Block block) {
        Map<Block, MaterialSets.Location> idx = this.index;
        if (idx == null) {
            idx = new java.util.HashMap<>();
            for (int pass = 0; pass < 2; pass++) {
                for (var slot : this.slots.entrySet()) {
                    if ((pass == 0) != "main".equals(slot.getKey())) continue;
                    for (var shape : slot.getValue().entrySet()) {
                        for (var w : shape.getValue().entrySet()) {
                            idx.putIfAbsent(w.getValue(), new MaterialSets.Location(slot.getKey(), shape.getKey(), w.getKey()));
                        }
                    }
                }
            }
            this.index = idx;
        }
        return idx.get(block);
    }

    private @Nullable Block raw(String slot, String shape, String wear) {
        Map<String, Map<String, Block>> sh = this.slots.get(slot);
        if (sh == null) return null;
        Map<String, Block> w = sh.get(shape);
        if (w == null) return null;
        Block b = w.get(wear == null ? "" : wear);
        return b != null ? b : w.get("");
    }

    @Override
    public String toString() {
        return "MaterialSet[" + this.id + "]";
    }

    // ------------------------------------------------------------------------------------------------ builder
    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    /** A mutable set: the scan, code and datapacks all edit one of these before it is built. */
    public static final class Builder {
        final ResourceLocation id;
        Kind kind = Kind.STONE;
        @Nullable String family;
        @Nullable String source;
        float weight = 1f;
        final Set<String> tags = new LinkedHashSet<>();
        final Set<String> removedTags = new LinkedHashSet<>();
        final Set<String> biomeTags = new LinkedHashSet<>();
        String dimension = "overworld";
        @Nullable Integer color;
        @Nullable Block light;
        @Nullable Block bars;
        boolean detected;
        final Map<String, Map<String, Map<String, Block>>> slots = new LinkedHashMap<>();

        Builder(ResourceLocation id) {
            this.id = id;
        }

        public ResourceLocation id() { return this.id; }
        public Builder kind(Kind kind) { this.kind = kind; return this; }
        public Builder family(String family) { this.family = family; return this; }
        public Builder source(String modid) { this.source = modid; return this; }
        public Builder weight(float weight) { this.weight = weight; return this; }
        public Builder tag(String... tags) { Collections.addAll(this.tags, tags); return this; }
        public Builder removeTag(String tag) { this.tags.remove(tag); return this; }
        public Builder biomeTag(String... tags) { Collections.addAll(this.biomeTags, tags); return this; }
        public Builder dimension(String dimension) { this.dimension = dimension; return this; }
        /** Overrides the computed colour (0xRRGGBB). */
        public Builder color(int rgb) { this.color = rgb; return this; }
        public Builder light(Block light) { this.light = light; return this; }
        public Builder bars(Block bars) { this.bars = bars; return this; }

        public Builder block(String slot, String shape, String wear, Block block) {
            this.slots.computeIfAbsent(slot, k -> new LinkedHashMap<>()).computeIfAbsent(shape, k -> new LinkedHashMap<>()).put(wear, block);
            return this;
        }

        public Builder block(String slot, String shape, Block block) {
            return block(slot, shape, "", block);
        }

        /** Shorthand for the main slot ("role" in the simple API). */
        public Builder role(String shape, Block block) {
            return block("main", shape, "", block);
        }

        public Builder removeBlock(String slot, String shape, String wear) {
            var sh = this.slots.get(slot);
            if (sh != null && sh.get(shape) != null) sh.get(shape).remove(wear);
            return this;
        }

        public Builder removeShape(String slot, String shape) {
            var sh = this.slots.get(slot);
            if (sh != null) sh.remove(shape);
            return this;
        }

        public Builder removeSlot(String slot) {
            this.slots.remove(slot);
            return this;
        }

        public @Nullable Block get(String slot, String shape, String wear) {
            var sh = this.slots.get(slot);
            if (sh == null || sh.get(shape) == null) return null;
            return sh.get(shape).get(wear);
        }

        public MaterialSet build() {
            Block base = get("main", "base", "");
            if (base == null) {
                for (var sh : this.slots.values()) {
                    if (sh.containsKey("base") && sh.get("base").containsKey("")) {
                        base = sh.get("base").get("");
                        break;
                    }
                }
            }
            int rgb = this.color != null ? this.color : base == null ? 0x808080 : MaterialColor.of(base);
            return new MaterialSet(this, rgb);
        }
    }

    static String key(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b).toString();
    }
}
