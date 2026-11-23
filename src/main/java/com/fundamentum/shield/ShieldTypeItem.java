package com.fundamentum.shield;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * A Fundamentum shield item: vanilla's shield (raise, banners, off hand, cooldown icon) whose stats come from
 * {@link Shields}. Build its properties with {@link #properties}; the stats in code or data are stamped on when it is held.
 */
public class ShieldTypeItem extends ShieldItem {
    public ShieldTypeItem(Item.Properties properties) {
        super(properties);
    }

    /** The vanilla shield's components (banners, off hand, block, break sound) on these properties; add a durability. */
    public static Item.Properties properties(Item.Properties p) {
        return p.stacksTo(1)
            .component(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY)
            .equippableUnswappable(EquipmentSlot.OFFHAND)
            .delayedComponent(DataComponents.BLOCKS_ATTACKS, context -> new BlocksAttacks(0.25F, 1.0F,
                List.of(new BlocksAttacks.DamageReduction(90.0F, Optional.empty(), 0.0F, 1.0F)),
                new BlocksAttacks.ItemDamageFunction(3.0F, 1.0F, 1.0F),
                Optional.of(context.getOrThrow(DamageTypeTags.BYPASSES_SHIELD)),
                Optional.of(SoundEvents.SHIELD_BLOCK), Optional.of(SoundEvents.SHIELD_BREAK)))
            .component(DataComponents.BREAK_SOUND, SoundEvents.SHIELD_BREAK);
    }

    /** A banner shield is "Red <name>" in vanilla; ours fall back to the plain name when no colour key is given. */
    @Override
    public Component getName(ItemStack stack) {
        DyeColor color = stack.get(DataComponents.BASE_COLOR);
        String key = getDescriptionId() + "." + (color == null ? "" : color.getName());
        return color != null && Language.getInstance().has(key) ? Component.translatable(key) : Component.translatable(getDescriptionId());
    }
}
