package com.fundamentum.compat.discount;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

/**
 * Per-aspect discounts: gear that makes one aspect cheaper (a ring that takes 3% off Ignis only). A mod with a vis cost
 * and a discount asks {@link #percentFor}; nothing here knows what that mod is, and aspects are plain ids.
 * <ul>
 * <li>per item, the data map {@code fundamentum:aspect_discount} on items
 * ({@code data/fundamentum/data_maps/item/aspect_discount.json} in any pack, keys are items or {@code #tags});</li>
 * <li>per stack, the data component {@code fundamentum:aspect_discount}, which wins over the data map for that stack.</li>
 * </ul>
 * <pre>{"aspects": {"thaumaturge:ignis": 3, "thaumaturge:perditio": 7}}</pre>
 * Values are percent and add up over everything worn (armour and Curios slots); they stack with any all-aspect discount
 * the other mod already has. The hook that applies this to Thaumaturge wand costs is
 * {@code com.fundamentum.compat.discount.mixin.WandCostMixin}, loaded only when Thaumaturge is.
 */
public final class AspectDiscounts {
    public record Discount(Map<Identifier, Float> aspects) {
        public static final Discount NONE = new Discount(Map.of());
        public static final Codec<Discount> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Identifier.CODEC, Codec.FLOAT).fieldOf("aspects").forGetter(Discount::aspects)
        ).apply(i, Discount::new));
        static final StreamCodec<io.netty.buffer.ByteBuf, Discount> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
    }

    public static final DataMapType<Item, Discount> ITEM_MAP = DataMapType.builder(
        Identifier.fromNamespaceAndPath("fundamentum", "aspect_discount"), Registries.ITEM, Discount.CODEC).build();

    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "fundamentum");

    public static final Supplier<DataComponentType<Discount>> STACK = COMPONENTS.registerComponentType("aspect_discount",
        b -> b.persistent(Discount.CODEC).networkSynchronized(Discount.STREAM_CODEC));

    private AspectDiscounts() {}

    public static void init(IEventBus modBus) {
        COMPONENTS.register(modBus);
        modBus.addListener((RegisterDataMapTypesEvent event) -> event.register(ITEM_MAP));
    }

    /** What one stack takes off one aspect, in percent (0 if it names none). */
    public static float percentOf(ItemStack stack, Identifier aspect) {
        if (stack.isEmpty()) return 0;
        Discount d = stack.get(STACK.get());
        if (d == null) d = stack.typeHolder().getData(ITEM_MAP);
        if (d == null) return 0;
        Float v = d.aspects().get(aspect);
        return v == null ? 0 : v;
    }

    /** What everything an entity wears takes off one aspect, in percent. Never negative. */
    public static float percentFor(LivingEntity entity, Identifier aspect) {
        if (entity == null) return 0;
        float total = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) total += percentOf(entity.getItemBySlot(slot), aspect);
        }
        ICuriosItemHandler curios = CuriosApi.getCuriosInventoryOrNull(entity);
        if (curios != null) {
            for (ICurioStacksHandler handler : curios.getCurios().values()) {
                var stacks = handler.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) total += percentOf(stacks.getStackInSlot(i), aspect);
            }
        }
        return Math.max(0, total);
    }
}
