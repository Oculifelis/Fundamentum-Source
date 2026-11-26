package com.fundamentum.compat.discount;

import com.fundamentum.data.JsonDataMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

/**
 * Per-aspect discounts: gear that makes one aspect cheaper (a ring that takes 3% off Ignis only). A mod with a vis cost
 * and a discount asks {@link #percentFor}; nothing here knows what that mod is, and aspects are plain ids.
 * <ul>
 * <li>per item, {@code fundamentum:aspect_discount} on items ({@code data/fundamentum/data_maps/item/aspect_discount.json} in any pack, keys
 * are items or {@code #tags}; 1.20.1 has no data maps, so Fundamentum reads the same files itself, see {@link JsonDataMap});</li>
 * <li>per stack, the item NBT tag {@code fundamentum:aspect_discount} (1.20.1 has no data components), which wins over the
 * file for that stack; set it with {@link #set}.</li>
 * </ul>
 * <pre>{"aspects": {"thaumaturge:ignis": 3, "thaumaturge:perditio": 7}}</pre>
 * Values are percent and add up over everything worn (armour and Curios slots). Thaumaturge has no 1.20.1 build, so no
 * cost hook ships here; a 1.20.1 magic mod reads {@link #percentFor} itself.
 */
public final class AspectDiscounts {
    public record Discount(Map<ResourceLocation, Float> aspects) {
        public static final Discount NONE = new Discount(Map.of());
        public static final Codec<Discount> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.FLOAT).fieldOf("aspects").forGetter(Discount::aspects)
        ).apply(i, Discount::new));
    }

    public static final JsonDataMap<Item, Discount> ITEM_MAP = new JsonDataMap<>(
        new ResourceLocation("fundamentum", "aspect_discount"), Registries.ITEM, Discount.CODEC);

    /** The item NBT key that holds a stack's own discount (in place of 1.21's data component of the same name). */
    public static final String STACK_TAG = "fundamentum:aspect_discount";

    private AspectDiscounts() {}

    public static void init(IEventBus modBus) {
        ITEM_MAP.register();
    }

    /** A stack's own discount, from its NBT, or null. */
    public static @org.jetbrains.annotations.Nullable Discount stack(ItemStack stack) {
        if (stack.isEmpty() || stack.getTag() == null || !stack.getTag().contains(STACK_TAG)) return null;
        return Discount.CODEC.parse(NbtOps.INSTANCE, stack.getTag().get(STACK_TAG)).result().orElse(null);
    }

    /** Gives a stack its own discount (or takes it away with null). */
    public static void set(ItemStack stack, @org.jetbrains.annotations.Nullable Discount discount) {
        if (discount == null) {
            stack.removeTagKey(STACK_TAG);
            return;
        }
        Tag tag = Discount.CODEC.encodeStart(NbtOps.INSTANCE, discount).result().orElse(null);
        if (tag != null) stack.getOrCreateTag().put(STACK_TAG, tag);
    }

    /** What one stack takes off one aspect, in percent (0 if it names none). */
    public static float percentOf(ItemStack stack, ResourceLocation aspect) {
        if (stack.isEmpty()) return 0;
        Discount d = stack(stack);
        if (d == null) d = ITEM_MAP.get(stack.getItem().builtInRegistryHolder());
        if (d == null) return 0;
        Float v = d.aspects().get(aspect);
        return v == null ? 0 : v;
    }

    /** What everything an entity wears takes off one aspect, in percent. Never negative. */
    public static float percentFor(LivingEntity entity, ResourceLocation aspect) {
        if (entity == null) return 0;
        float total = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ARMOR) total += percentOf(entity.getItemBySlot(slot), aspect);
        }
        ICuriosItemHandler curios = CuriosApi.getCuriosInventory(entity).resolve().orElse(null);
        if (curios != null) {
            for (ICurioStacksHandler handler : curios.getCurios().values()) {
                var stacks = handler.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) total += percentOf(stacks.getStackInSlot(i), aspect);
            }
        }
        return Math.max(0, total);
    }
}
