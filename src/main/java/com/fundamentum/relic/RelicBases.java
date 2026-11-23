package com.fundamentum.relic;

import com.fundamentum.port.FId;
import com.fundamentum.port.FReg;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.jetbrains.annotations.Nullable;

/**
 * The blocks relics stand on. A base is registered once, with the sizes it comes in (1 to 5 blocks across) and how it
 * looks at each size; it becomes one anchor block per size, {@code <ns>:<name>_<size>}, and one filler,
 * {@code <ns>:<name>_filler}, for the other cells of a wide stand. Which relic stands there is block entity data, so one
 * base serves every relic; a relic file picks its base with {@code "base"} (default {@code fundamentum:relic_stand}).
 * The bases have no items, recipes, loot tables or tags: players only ever hold the relic.
 */
public final class RelicBases {
    /** How a base looks at one size: a model layer the renderer draws (null: the anchor's own block model), where the figure's float starts (pixels), and the layer's scale. */
    public record Look(@Nullable String layer, @Nullable String texture, @Nullable String glow, float top, float scale) {
        public static Look block(float top) {
            return new Look(null, null, null, top, 1F);
        }
    }

    /** One registered base. */
    public static final class Base {
        private final FId id;
        private final Map<Integer, FReg.Ref<RelicBlock>> anchors = new TreeMap<>();
        private final FReg.Ref<RelicPartBlock> filler;
        private final IntFunction<Look> look;

        private Base(FId id, IntFunction<Look> look, FReg.Ref<RelicPartBlock> filler) {
            this.id = id;
            this.look = look;
            this.filler = filler;
        }

        public FId id() {
            return this.id;
        }

        /** The anchor block for {@code size}, or null when this base does not come in it. */
        public @Nullable RelicBlock anchor(int size) {
            FReg.Ref<RelicBlock> r = this.anchors.get(size);
            return r == null ? null : r.get();
        }

        public Collection<Integer> sizes() {
            return Collections.unmodifiableCollection(this.anchors.keySet());
        }

        public RelicPartBlock filler() {
            return this.filler.get();
        }

        public Look look(int size) {
            return this.look.apply(size);
        }

        Collection<FReg.Ref<RelicBlock>> anchorRefs() {
            return this.anchors.values();
        }
    }

    private static final Map<FId, Base> BASES = new LinkedHashMap<>();

    /** Fundamentum's own base: Ars Moriendi's gold pedestal with a diamond (1x1 and 3x3 models, 2, 4 and 5 scaled from them). */
    public static final String DEFAULT = RelicDef.DEFAULT_BASE;

    private RelicBases() {}

    /**
     * Registers a base: one anchor block per size ({@code factory} makes it from the properties and the size) and the
     * filler. Call it while the mod is constructed, before registration runs. A second base with the same id is refused.
     */
    public static synchronized Base register(FId id, BiFunction<BlockBehaviour.Properties, Integer, ? extends RelicBlock> factory, int[] sizes, IntFunction<Look> look) {
        if (BASES.containsKey(id)) {
            throw new IllegalArgumentException("relic base " + id + " is already registered");
        }
        FReg.Ref<RelicPartBlock> filler = FReg.block(id.withSuffix("_filler"), p -> new RelicPartBlock(p, id), props().noLootTable());
        Base base = new Base(id, look, filler);
        for (int n : sizes) {
            if (n < 1 || n > 5) {
                throw new IllegalArgumentException("relic base " + id + ": sizes are 1 to 5, not " + n);
            }
            int size = n;
            base.anchors.put(n, FReg.block(id.withSuffix("_" + n), p -> factory.apply(p, size), props().noLootTable()
                .lightLevel(s -> s.hasProperty(RelicBlock.LIGHT) ? s.getValue(RelicBlock.LIGHT) : 0)));
        }
        BASES.put(id, base);
        return base;
    }

    /** {@link #register} with the plain {@link RelicBlock}. */
    public static Base register(FId id, int[] sizes, IntFunction<Look> look) {
        return register(id, (p, n) -> new RelicBlock(p, id, n), sizes, look);
    }

    public static @Nullable Base get(FId id) {
        return BASES.get(id);
    }

    public static Collection<Base> all() {
        return Collections.unmodifiableCollection(BASES.values());
    }

    /** The anchor a relic is placed as: its base at its size, else Fundamentum's stand at its size; null when neither has it. */
    public static @Nullable RelicBlock anchorFor(RelicDef def) {
        FId id = FId.tryOf(def.base.id);
        Base b = id == null ? null : BASES.get(id);
        RelicBlock block = b == null ? null : b.anchor(def.span);
        if (block == null) {
            Base d = BASES.get(FId.of(DEFAULT));
            block = d == null ? null : d.anchor(def.span);
        }
        return block;
    }

    /** What a relic's base looks like: the anchor's own look, with what the relic file's {@code base} object overrides. */
    public static Look look(RelicBlock anchor, @Nullable RelicDef def) {
        Look own = anchor.look();
        if (def == null) {
            return own;
        }
        boolean layer = def.base.layer != null;
        float top = !Float.isNaN(def.base.top) ? def.base.top : layer && own.layer() == null ? 8.6F : own.top();
        return layer ? new Look(def.base.layer, def.base.texture, def.base.glow, top, 1F)
            : new Look(own.layer(), own.texture(), own.glow(), top, own.scale());
    }

    /** Gold, unmovable (a wide stand is never split apart), lit by its relic. */
    static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion()
            .pushReaction(PushReaction.BLOCK);
    }

    /** Fundamentum's stand: the 1x1 pedestal (doubled for 2x2) and the 3x3 one (scaled for 4x4 and 5x5). */
    static Base registerDefault() {
        return register(FId.of(DEFAULT), new int[] {1, 2, 3, 4, 5}, n -> {
            boolean big = n >= 3;
            float scale = big ? n / 3F : n;
            String t = "fundamentum:textures/entity/relic/" + (big ? "base_3x3" : "base_1x1");
            return new Look("fundamentum:relic_stand_" + (big ? "3x3" : "1x1"), t + ".png", t + "_glow.png", (big ? 30.75F : 10.25F) * scale, scale);
        });
    }
}
