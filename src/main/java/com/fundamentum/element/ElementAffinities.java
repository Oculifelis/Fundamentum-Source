package com.fundamentum.element;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * Affinities: how much of an element's hits a creature shrugs off. The same shape in two places:
 * <ul>
 * <li>per creature type, the data map {@code fundamentum:element_affinity} on entity types
 * ({@code data/<ns>/data_maps/entity_type/element_affinity.json}, keys are entity types or {@code #tags});</li>
 * <li>per item, the data component {@code fundamentum:element_affinity}, counted while the item is worn or held (armour,
 * shields, trinkets; give it with {@code /give @s diamond_chestplate[fundamentum:element_affinity={resist:{...}}]} or
 * in a mod's item properties).</li>
 * </ul>
 * <pre>{"resist": {"#fundamentum:element/fire": 0.5, "minecraft:magic": -0.5}, "immune": ["minecraft:drown"]}</pre>
 * {@code resist} adds up over every matching entry: 0.5 takes half, -0.5 takes 1.5 times, 1 or more is immune;
 * {@code immune} cancels matching hits outright. Code adds more through {@link ElementEvents.Affinity}. This works with
 * the per-element {@code fundamentum:<e>_resistance} attributes (which still apply on their own) and stacks with them.
 */
public final class ElementAffinities {
    public record Affinity(Map<ElementRef, Float> resist, List<ElementRef> immune) {
        public static final Affinity NONE = new Affinity(Map.of(), List.of());
        public static final Codec<Affinity> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(ElementRef.CODEC, Codec.FLOAT).optionalFieldOf("resist", Map.of()).forGetter(Affinity::resist),
            ElementRef.LIST_CODEC.optionalFieldOf("immune", List.of()).forGetter(Affinity::immune)
        ).apply(i, Affinity::new));
        static final StreamCodec<io.netty.buffer.ByteBuf, Affinity> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

        /** This affinity's resistance to a hit (0 if no entry matches). */
        public float resistance(DamageSource source) {
            float total = 0;
            for (Map.Entry<ElementRef, Float> e : this.resist.entrySet()) {
                if (e.getKey().matches(source)) {
                    total += e.getValue();
                }
            }
            return total;
        }

        public boolean immune(DamageSource source) {
            return ElementRef.any(this.immune, source);
        }
    }

    /** A creature's total affinity to one hit: summed resistance, and whether anything makes it immune. */
    public record Result(float resistance, boolean immune) {
        public float multiplier() {
            return this.immune ? 0 : ElementMath.affinityMultiplier(this.resistance);
        }
    }

    public static final DataMapType<EntityType<?>, Affinity> ENTITY = DataMapType.builder(
        ResourceLocation.fromNamespaceAndPath("fundamentum", "element_affinity"), Registries.ENTITY_TYPE, Affinity.CODEC).build();

    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "fundamentum");

    public static final Supplier<DataComponentType<Affinity>> ITEM = COMPONENTS.registerComponentType("element_affinity",
        b -> b.persistent(Affinity.CODEC).networkSynchronized(Affinity.STREAM_CODEC));

    public static void init(IEventBus modBus) {
        COMPONENTS.register(modBus);
        modBus.addListener((RegisterDataMapTypesEvent event) -> event.register(ENTITY));
    }

    /** Every affinity that applies to a creature: its type's, then each worn or held item's. */
    public static List<Affinity> of(LivingEntity entity) {
        List<Affinity> out = new ArrayList<>();
        Affinity type = entity.getType().builtInRegistryHolder().getData(ENTITY);
        if (type != null) {
            out.add(type);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            Affinity item = stack.isEmpty() ? null : stack.get(ITEM.get());
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** A creature's affinity to a hit, after {@link ElementEvents.Affinity} listeners. */
    public static Result resolve(LivingEntity entity, DamageSource source) {
        float total = 0;
        boolean immune = false;
        for (Affinity affinity : of(entity)) {
            total += affinity.resistance(source);
            immune |= affinity.immune(source);
        }
        ElementEvents.Affinity event = NeoForge.EVENT_BUS.post(new ElementEvents.Affinity(entity, source, total, immune));
        return new Result(event.getResistance(), event.isImmune() || event.getResistance() >= 1);
    }

    private ElementAffinities() {}
}
