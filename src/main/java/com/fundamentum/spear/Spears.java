package com.fundamentum.spear;

import com.fundamentum.config.Timers;
import com.fundamentum.port.FLife;
import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.compat.BetterCombat;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * Spears on Minecraft 1.21.1: this version has no vanilla spears, so {@code #fundamentum:spears} starts empty and other
 * mods put their spears in it ({@code #fundamentum:enchantable/spear} follows it). Two attributes act on every spear in
 * the tag: {@code fundamentum:spear_reach} adds blocks to the player's entity reach while a spear is in the main hand,
 * and {@code fundamentum:spear_charge_damage} multiplies the hits dealt while a spear is held in use (a mod spear's own
 * charge). Armour and accessories raise them with ordinary attribute modifiers.
 * <p>
 * A spear can also be thrown, per item, from data in {@code data/<namespace>/fundamentum/spear_throws/<name>.json}, keyed by
 * "item" (default: the file id):
 * <pre>{"item": "mymod:iron_spear", "damage": 7, "velocity": 2.5, "charge_ticks": 10, "sneak": true}</pre>
 * Drawing the spear back (holding use; the spear item needs a use duration) for "charge_ticks" and letting go throws it
 * as {@code fundamentum:thrown_spear} (while sneaking, when "sneak" is true, so the spear's own use still works when
 * standing). The thrown spear deals "damage" (scaled by how far it was drawn), loses one durability, and can be picked
 * up again. Code can throw with {@link #throwSpear}.
 * <p>
 * With Better Combat loaded (see {@link BetterCombat}), Better Combat owns the left-click jab; the reach bonus
 * stays on the entity reach, which Better Combat reads, so it counts once either way.
 */
public final class Spears {
    public static final TagKey<Item> SPEARS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("fundamentum", "spears"));
    /** Blocks added to the reach of every spear in {@code #fundamentum:spears}. */
    public static final Holder<Attribute> SPEAR_REACH = StatAttributes.add("spear_reach", 0, -16, 16);
    /** Multiplier on the hits of a spear's held charge. */
    public static final Holder<Attribute> SPEAR_CHARGE_DAMAGE = StatAttributes.add("spear_charge_damage", 1, 0, 1024);

    /** One spear's throw. */
    public record SpearThrow(double damage, double velocity, int chargeTicks, boolean sneak) {
        static SpearThrow fromJson(JsonObject json) {
            return new SpearThrow(json.has("damage") ? json.get("damage").getAsDouble() : 8.0,
                json.has("velocity") ? Math.max(0.1, json.get("velocity").getAsDouble()) : 2.5,
                json.has("charge_ticks") ? Math.max(0, json.get("charge_ticks").getAsInt()) : 10,
                !json.has("sneak") || json.get("sneak").getAsBoolean());
        }
    }

    public static final DataTable<SpearThrow> THROWS = DataTables
        .register(ResourceLocation.fromNamespaceAndPath("fundamentum", "spear_throws"), "fundamentum/spear_throws", SpearThrow::fromJson)
        .keyField("item");

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, "fundamentum");
    public static final DeferredHolder<EntityType<?>, EntityType<ThrownSpear>> THROWN_SPEAR = ENTITIES.register("thrown_spear",
        id -> EntityType.Builder.<ThrownSpear>of(ThrownSpear::new, MobCategory.MISC).sized(0.5F, 0.5F)
            .clientTrackingRange(4).updateInterval(20).build(id.toString()));

    /** The spear type as Better Combat sees it: Better Combat plays the left-click jab when loaded. */
    public static final BetterCombat.WeaponType BETTER_COMBAT = BetterCombat.register("spear", SPEARS,
        ResourceLocation.fromNamespaceAndPath("fundamentum", "spear"), BetterCombat.LeftClick.BETTER_COMBAT);
    private static final ResourceLocation REACH_MODIFIER = ResourceLocation.fromNamespaceAndPath("fundamentum", "spear_reach");

    public static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && stack.is(SPEARS);
    }

    /** The throw for a spear, or null when it has none. */
    public static @Nullable SpearThrow throwOf(ItemStack stack) {
        return isSpear(stack) ? THROWS.get(BuiltInRegistries.ITEM.getKey(stack.getItem())) : null;
    }

    /** True while {@code attacker} holds a spear in use (its charge). */
    public static boolean charging(LivingEntity attacker) {
        return attacker.isUsingItem() && isSpear(attacker.getUseItem());
    }

    /**
     * Throws one of {@code stack} from {@code thrower}, drawn back {@code heldTicks}; returns the thrown spear. Outside
     * creative mode the spear leaves the stack and loses one durability.
     */
    public static @Nullable ThrownSpear throwSpear(Player thrower, ItemStack stack, SpearThrow def, int heldTicks) {
        if (!(thrower.level() instanceof ServerLevel level) || stack.isEmpty()) {
            return null;
        }
        float speed = SpearMath.throwSpeed(heldTicks, def.chargeTicks(), def.velocity());
        boolean creative = thrower.getAbilities().instabuild;
        ItemStack thrown = creative ? stack.copyWithCount(1) : stack.split(1);
        if (!creative && thrown.isDamageableItem()) {
            thrown.setDamageValue(Math.min(thrown.getMaxDamage() - 1, thrown.getDamageValue() + 1));
        }
        ThrownSpear spear = new ThrownSpear(level, thrower, thrown);
        spear.setSpearDamage(def.damage() * Math.min(1.0, speed / def.velocity()));
        spear.shootFromRotation(thrower, thrower.getXRot(), thrower.getYRot(), 0.0F, speed, 1.0F);
        if (creative) {
            spear.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        }
        level.addFreshEntity(spear);
        level.playSound(null, thrower, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
        return spear;
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
        ENTITIES.register(modBus);
        // reach follows the held spear: set when the hands change, with a slow safety net
        FLife.handsChanged(Spears::syncReach);
        Timers.perPlayer("spears_recheck_seconds", 30, Spears::syncReach);
        // The held charge's hits scale with spear_charge_damage.
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent e) -> {
            Entity attacker = e.getSource().getEntity();
            if (attacker instanceof LivingEntity living && e.getSource().getDirectEntity() == attacker && charging(living)
                && living.getAttribute(SPEAR_CHARGE_DAMAGE) != null) {
                e.setAmount(SpearMath.charge(e.getAmount(), living.getAttributeValue(SPEAR_CHARGE_DAMAGE)));
            }
        });
        // Letting go of a drawn spear that has a throw throws it (cancelling the stop skips the item's own release).
        NeoForge.EVENT_BUS.addListener((LivingEntityUseItemEvent.Stop e) -> {
            if (!(e.getEntity() instanceof Player player) || player.level().isClientSide()) {
                return;
            }
            ItemStack stack = e.getItem();
            SpearThrow def = throwOf(stack);
            int held = player.getTicksUsingItem();
            if (def == null || def.sneak() && !player.isShiftKeyDown() || held < def.chargeTicks()) {
                return;
            }
            if (throwSpear(player, stack, def, held) != null) {
                e.setCanceled(true);
            }
        });
    }

    private Spears() {}
}
