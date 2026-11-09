package com.fundamentum.spear;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.compat.BetterCombat;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import java.util.UUID;
import net.minecraftforge.common.ForgeMod;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.TickEvent;

/**
 * Spears on Minecraft 1.20.1: this version has no vanilla spears, so {@code #fundamentum:spears} starts empty and other
 * mods put their spears in it ({@code #fundamentum:enchantable/spear} follows it). Two attributes act on every spear in
 * the tag: {@code fundamentum:spear_reach} adds blocks to the player's entity reach (Forge's entity_reach) while a spear is in the main hand,
 * and {@code fundamentum:spear_charge_damage} multiplies the hits dealt while a spear is held in use (a mod spear's own
 * charge). Armour and accessories raise them with ordinary attribute modifiers.
 * <p>
 * The data-driven spear throw and the thrown spear entity are only in the Minecraft 26 builds, which build on the vanilla
 * spears. With Better Combat loaded (see {@link BetterCombat}), Better Combat owns the left-click jab; the reach bonus
 * stays on the entity reach, which Better Combat reads, so it counts once either way.
 */
public final class Spears {
    public static final TagKey<Item> SPEARS = TagKey.create(Registries.ITEM, new ResourceLocation("fundamentum", "spears"));
    /** Blocks added to the reach of every spear in {@code #fundamentum:spears}. */
    public static final RegistryObject<Attribute> SPEAR_REACH = StatAttributes.add("spear_reach", 0, -16, 16);
    /** Multiplier on the hits of a spear's held charge. */
    public static final RegistryObject<Attribute> SPEAR_CHARGE_DAMAGE = StatAttributes.add("spear_charge_damage", 1, 0, 1024);

    /** The spear type as Better Combat sees it: Better Combat plays the left-click jab when loaded. */
    public static final BetterCombat.WeaponType BETTER_COMBAT = BetterCombat.register("spear", SPEARS,
        new ResourceLocation("fundamentum", "spear"), BetterCombat.LeftClick.BETTER_COMBAT);
    private static final UUID REACH_MODIFIER = UUID.fromString("1f4a8c3e-6b2d-4e7f-a915-3c8d0b6e2f71");

    public static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && stack.is(SPEARS);
    }

    /** True while {@code attacker} holds a spear in use (its charge). */
    public static boolean charging(LivingEntity attacker) {
        return attacker.isUsingItem() && isSpear(attacker.getUseItem());
    }

    private static void reachTick(TickEvent.PlayerTickEvent e) {
        Player player = e.player;
        if (e.phase == TickEvent.Phase.END && !player.level().isClientSide() && player.tickCount % 5 == 0) {
            syncReach(player);
        }
    }

    /** Puts {@code fundamentum:spear_reach} on the player's entity reach (Forge's entity_reach) while a spear is in the main hand. */
    public static void syncReach(Player player) {
        AttributeInstance reach = player.getAttribute(ForgeMod.ENTITY_REACH.get());
        if (reach == null) {
            return;
        }
        double bonus = isSpear(player.getMainHandItem()) && player.getAttribute(SPEAR_REACH.get()) != null
            ? player.getAttributeValue(SPEAR_REACH.get()) : 0.0;
        AttributeModifier had = reach.getModifier(REACH_MODIFIER);
        if (had != null && had.getAmount() == bonus) {
            return;
        }
        reach.removeModifier(REACH_MODIFIER);
        if (bonus != 0.0) {
            reach.addTransientModifier(new AttributeModifier(REACH_MODIFIER, "fundamentum:spear_reach", bonus, AttributeModifier.Operation.ADDITION));
        }
    }

    public static void init(IEventBus modBus) {
        MinecraftForge.EVENT_BUS.addListener(Spears::reachTick);
        // The held charge's hits scale with spear_charge_damage.
        MinecraftForge.EVENT_BUS.addListener((LivingHurtEvent e) -> {
            Entity attacker = e.getSource().getEntity();
            if (attacker instanceof LivingEntity living && e.getSource().getDirectEntity() == attacker && charging(living)
                && living.getAttribute(SPEAR_CHARGE_DAMAGE.get()) != null) {
                e.setAmount(SpearMath.charge(e.getAmount(), living.getAttributeValue(SPEAR_CHARGE_DAMAGE.get())));
            }
        });
    }

    private Spears() {}
}
