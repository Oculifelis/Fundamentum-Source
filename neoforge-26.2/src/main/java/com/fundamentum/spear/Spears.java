package com.fundamentum.spear;

import com.fundamentum.config.Timers;
import com.fundamentum.port.FLife;
import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.compat.BetterCombat;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

/**
 * Spears: Fundamentum has no spear of its own and builds on the vanilla spears. {@code #fundamentum:spears} holds the
 * vanilla spears ({@code #minecraft:spears}) and any mod's spear; {@code #fundamentum:enchantable/spear} follows it, and
 * spears in the tag also take the vanilla spear enchantments ({@code #minecraft:enchantable/lunge}). Two attributes act
 * on every spear in the tag: {@code fundamentum:spear_reach} adds blocks to the spear's reach (its jab and its charge),
 * and {@code fundamentum:spear_charge_damage} multiplies the hits of the held charge. Armour and accessories raise them
 * with ordinary attribute modifiers.
 * <p>
 * A spear can also be thrown, per item, from data in {@code data/<namespace>/fundamentum/spear_throws/<name>.json}, keyed by
 * "item" (default: the file id):
 * <pre>{"item": "minecraft:iron_spear", "damage": 7, "velocity": 2.5, "charge_ticks": 10, "sneak": true}</pre>
 * Drawing the spear back for "charge_ticks" and letting go throws it (while sneaking, when "sneak" is true, so the
 * vanilla charge still works when standing). The thrown spear deals "damage" (scaled by how far it was drawn), loses one
 * durability, and can be picked up again. Code can throw with {@link #throwSpear}.
 * <p>
 * With Better Combat loaded (see {@link BetterCombat}), Better Combat owns the left-click jab: its hitbox, timing and
 * animation come from weapon attributes ({@code {"parent": "fundamentum:spear"}} for a mod's spear; the vanilla spears
 * keep whatever Better Combat itself gives them). The jab's reach then comes from Better Combat's range, plus
 * {@code fundamentum:spear_reach} added to the player's entity reach while a spear is held, instead of from the spear's
 * own attack range. The charge and the throw are held right clicks and work the same either way; Fundamentum adds no
 * lunge of its own (the vanilla Lunge enchantment applies to spears in the tag).
 */
public final class Spears {
    public static final TagKey<Item> SPEARS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fundamentum", "spears"));
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
        .register(Identifier.fromNamespaceAndPath("fundamentum", "spear_throws"), "fundamentum/spear_throws", SpearThrow::fromJson)
        .keyField("item");

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, "fundamentum");
    public static final DeferredHolder<EntityType<?>, EntityType<ThrownSpear>> THROWN_SPEAR = ENTITIES.register("thrown_spear",
        id -> EntityType.Builder.<ThrownSpear>of(ThrownSpear::new, MobCategory.MISC).sized(0.5F, 0.5F)
            .clientTrackingRange(4).updateInterval(20).build(ResourceKey.create(Registries.ENTITY_TYPE, id)));

    /** The spear type as Better Combat sees it: Better Combat plays the left-click jab when loaded. */
    public static final BetterCombat.WeaponType BETTER_COMBAT = BetterCombat.register("spear", SPEARS,
        Identifier.fromNamespaceAndPath("fundamentum", "spear"), BetterCombat.LeftClick.BETTER_COMBAT);
    private static final Identifier REACH_MODIFIER = Identifier.fromNamespaceAndPath("fundamentum", "spear_reach");

    public static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && stack.is(SPEARS);
    }

    /** The throw for a spear, or null when it has none. */
    public static @Nullable SpearThrow throwOf(ItemStack stack) {
        return isSpear(stack) ? THROWS.get(BuiltInRegistries.ITEM.getKey(stack.getItem())) : null;
    }

    /** The reach of {@code stack} in {@code entity}'s hands after {@code fundamentum:spear_reach} (used by a mixin). */
    public static AttackRange reach(LivingEntity entity, ItemStack stack, AttackRange range) {
        if (!isSpear(stack) || entity.getAttribute(SPEAR_REACH) == null || BetterCombat.drivesLeftClick(stack)) {
            // With Better Combat the jab's reach is its range plus the entity reach modifier (see reachTick).
            return range;
        }
        double bonus = entity.getAttributeValue(SPEAR_REACH);
        if (bonus == 0.0) {
            return range;
        }
        return new AttackRange(range.minReach(), SpearMath.reach(range.maxReach(), range.minReach(), bonus), range.minCreativeReach(),
            SpearMath.reach(range.maxCreativeReach(), range.minCreativeReach(), bonus), range.hitboxMargin(), range.mobFactor());
    }

    /** True while {@code attacker} holds a spear's charge (the vanilla kinetic attack). */
    public static boolean charging(LivingEntity attacker) {
        ItemStack using = attacker.getUseItem();
        return attacker.isUsingItem() && isSpear(using) && using.has(DataComponents.KINETIC_WEAPON);
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

    /*
     * With Better Combat: keeps spear_reach on the player's entity reach while a spear is held, since Better Combat reads
     * that. Synced when the held items change, with a slow safety net (spears_recheck_seconds).
     */

    /** Puts {@code fundamentum:spear_reach} on the player's entity reach when Better Combat plays the held spear's jab. */
    public static void syncReach(Player player) {
        AttributeInstance reach = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (reach == null) {
            return;
        }
        double bonus = BetterCombat.drivesLeftClick(player.getMainHandItem()) && player.getAttribute(SPEAR_REACH) != null
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
        // Letting go of a drawn spear that has a throw throws it.
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
