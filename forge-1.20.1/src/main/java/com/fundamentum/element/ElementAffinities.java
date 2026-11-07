package com.fundamentum.element;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import com.fundamentum.data.JsonDataMap;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;

/**
 * Affinities: how much of an element's hits a creature shrugs off. The same shape in two places:
 * <ul>
 * <li>per creature type, {@code fundamentum:element_affinity} on entity types
 * ({@code data/<ns>/data_maps/entity_type/element_affinity.json}, keys are entity types or {@code #tags}; 1.20.1 has no
 * data maps, so Fundamentum reads the same files itself, see {@link JsonDataMap});</li>
 * <li>per item, the item NBT tag {@code fundamentum:element_affinity} (1.20.1 has no data components), counted while the
 * item is worn or held (armour, shields, trinkets; give it with
 * {@code /give @s diamond_chestplate{"fundamentum:element_affinity":{resist:{"#fundamentum:element/fire":0.5f}}}} or
 * set it with {@link #set}).</li>
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

    public static final JsonDataMap<EntityType<?>, Affinity> ENTITY = new JsonDataMap<>(
        new ResourceLocation("fundamentum", "element_affinity"), Registries.ENTITY_TYPE, Affinity.CODEC);

    /** The item NBT key that holds an item's affinity (in place of 1.21's data component of the same name). */
    public static final String ITEM_TAG = "fundamentum:element_affinity";

    public static void init(IEventBus modBus) {
        ENTITY.register();
    }

    /** An item's own affinity, from its NBT, or null. */
    public static @org.jetbrains.annotations.Nullable Affinity item(ItemStack stack) {
        if (stack.isEmpty() || stack.getTag() == null || !stack.getTag().contains(ITEM_TAG)) {
            return null;
        }
        return Affinity.CODEC.parse(NbtOps.INSTANCE, stack.getTag().get(ITEM_TAG)).result().orElse(null);
    }

    /** Gives an item an affinity (or takes it away with null). */
    public static void set(ItemStack stack, @org.jetbrains.annotations.Nullable Affinity affinity) {
        if (affinity == null) {
            stack.removeTagKey(ITEM_TAG);
            return;
        }
        Tag tag = Affinity.CODEC.encodeStart(NbtOps.INSTANCE, affinity).result().orElse(null);
        if (tag != null) {
            stack.getOrCreateTag().put(ITEM_TAG, tag);
        }
    }

    /** Every affinity that applies to a creature: its type's, then each worn or held item's. */
    public static List<Affinity> of(LivingEntity entity) {
        List<Affinity> out = new ArrayList<>();
        Affinity type = ENTITY.get(entity.getType().builtInRegistryHolder());
        if (type != null) {
            out.add(type);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            Affinity item = item(stack);
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
        ElementEvents.Affinity event = new ElementEvents.Affinity(entity, source, total, immune);
        MinecraftForge.EVENT_BUS.post(event);
        return new Result(event.getResistance(), event.isImmune() || event.getResistance() >= 1);
    }

    private ElementAffinities() {}
}
