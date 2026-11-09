package com.fundamentum.spear;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.compat.BetterCombat;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Spears on Minecraft 1.21.1: this version has no vanilla spears, so {@code #fundamentum:spears} starts empty and other
 * mods put their spears in it ({@code #fundamentum:enchantable/spear} follows it). Two attributes act on every spear in
 * the tag: {@code fundamentum:spear_reach} adds blocks to the player's entity reach while a spear is in the main hand,
 * and {@code fundamentum:spear_charge_damage} multiplies the hits dealt while a spear is held in use (a mod spear's own
 * charge). Armour and accessories raise them with ordinary attribute modifiers.
 * <p>
 * The data-driven spear throw and the thrown spear entity are only in the Minecraft 26 builds, which build on the vanilla
 * spears. With Better Combat loaded (see {@link BetterCombat}), Better Combat owns the left-click jab; the reach bonus
 * stays on the entity reach, which Better Combat reads, so it counts once either way.
 */
public final class Spears {
    public static final TagKey<Item> SPEARS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("fundamentum", "spears"));
    /** Blocks added to the reach of every spear in {@code #fundamentum:spears}. */
    public static final Holder<Attribute> SPEAR_REACH = StatAttributes.add("spear_reach", 0, -16, 16);
    /** Multiplier on the hits of a spear's held charge. */
    public static final Holder<Attribute> SPEAR_CHARGE_DAMAGE = StatAttributes.add("spear_charge_damage", 1, 0, 1024);

    /** The spear type as Better Combat sees it: Better Combat plays the left-click jab when loaded. */
    public static final BetterCombat.WeaponType BETTER_COMBAT = BetterCombat.register("spear", SPEARS,
        ResourceLocation.fromNamespaceAndPath("fundamentum", "spear"), BetterCombat.LeftClick.BETTER_COMBAT);
    private static final ResourceLocation REACH_MODIFIER = ResourceLocation.fromNamespaceAndPath("fundamentum", "spear_reach");

    public static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && stack.is(SPEARS);
    }

    /** True while {@code attacker} holds a spear in use (its charge). */
    public static boolean charging(LivingEntity attacker) {
        return attacker.isUsingItem() && isSpear(attacker.getUseItem());
    }

    private static void reachTick(PlayerTickEvent.Post e) {
        Player player = e.getEntity();
        if (!player.level().isClientSide() && player.tickCount % 5 == 0) {
            syncReach(player);
        }
    }

    /** Puts {@code fundamentum:spear_reach} on the player's entity reach while a spear is in the main hand. */
    public static void syncReach(Player player) {
        AttributeInstance reach = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (reach == null) {
            return;
        }
        double bonus = isSpear(player.getMainHandItem()) && player.getAttribute(SPEAR_REACH) != null
            ? player.getAttributeValue(SPEAR_REACH) : 0.0;
        AttributeModifier had = reach.getModifier(REACH_MODIFIER);
        if (had != null && had.amount() == bonus) {
            return;
        }
        reach.removeModifier(REACH_MODIFIER);
        if (bonus != 0.0) {
            reach.addTransientModifier(new AttributeModifier(REACH_MODIFIER, bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(Spears::reachTick);
        // The held charge's hits scale with spear_charge_damage.
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent e) -> {
            Entity attacker = e.getSource().getEntity();
            if (attacker instanceof LivingEntity living && e.getSource().getDirectEntity() == attacker && charging(living)
                && living.getAttribute(SPEAR_CHARGE_DAMAGE) != null) {
                e.setAmount(SpearMath.charge(e.getAmount(), living.getAttributeValue(SPEAR_CHARGE_DAMAGE)));
            }
        });
    }

    private Spears() {}
}
