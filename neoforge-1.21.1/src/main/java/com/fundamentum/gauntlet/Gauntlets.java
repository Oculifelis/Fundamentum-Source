package com.fundamentum.gauntlet;

import com.fundamentum.compat.BetterCombat;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.damage.DamageClasses;
import com.fundamentum.pose.Poses;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Gauntlets: a melee weapon type that punches in combos. Each landed, charged punch (attack strength at least 80%)
 * moves the combo one step; a pause longer than the combo window starts it over, and the last punch of the combo is the
 * finisher, which deals extra damage and can add an effect, extra knockback and a code hook. A gauntlet sets the
 * player's attack speed to its punch rate while held. With "dual_wield" and a gauntlet in each hand, the punches
 * alternate hands, and a finisher thrown by the off hand uses the off-hand gauntlet's finisher.
 * <p>
 * Gauntlets go in {@code #fundamentum:gauntlets}. A gauntlet also in a damage class item tag, say
 * {@code #fundamentum:class/mage} for a magic gauntlet, punches as that class instead of melee. Stats are data, in
 * {@code data/<namespace>/fundamentum/gauntlets/<name>.json}, keyed by "item" (default: the file id):
 * <pre>{"item": "mymod:iron_knuckles", "combo_length": 3, "combo_window": 20, "punch_rate": 2.5, "dual_wield": false,
 *  "finisher": {"damage_multiplier": 2.0, "effect": "minecraft:slowness", "effect_duration": 60, "effect_amplifier": 0,
 *  "knockback": 0.8}}</pre>
 * All fields are optional. "combo_window" is in ticks; "punch_rate" is punches per second (0 keeps the item's own
 * attack speed). Code registers with {@code Gauntlets.register(item, GauntletDefinition)}, which can carry a
 * {@link FinisherHook}; a data file for the same item overrides the stats and keeps the hook. Each punch sends the pose
 * cue {@code gauntlet_combo_<n>} and the finisher {@code gauntlet_finisher} (see Poses).
 * <p>
 * With Better Combat loaded (see {@link BetterCombat}), Better Combat owns the left click: the combo, its timing, the
 * off-hand punches and the animations come from the {@code fundamentum:gauntlet} weapon attributes (an item file
 * {@code data/<namespace>/weapon_attributes/<item>.json} with {@code {"parent": "fundamentum:gauntlet"}}, or its own
 * attacks list), and this class only keeps the punch rate and the damage class. The finisher is then the last attack
 * of the preset (its "damage_multiplier"); the finisher's effect, knockback and hook apply only without Better Combat.
 */
public final class Gauntlets {
    public static final TagKey<Item> GAUNTLETS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("fundamentum", "gauntlets"));

    /** Runs when a finisher lands, after its damage. */
    @FunctionalInterface
    public interface FinisherHook {
        void finish(Player puncher, LivingEntity target, ItemStack gauntlet, GauntletDefinition def);
    }

    /** What the last punch of a combo adds. */
    public record Finisher(double damageMultiplier, @Nullable ResourceLocation effect, int effectDuration, int effectAmplifier, double knockback) {
        public static final Finisher DEFAULT = new Finisher(1.5, null, 0, 0, 0.5);

        static Finisher fromJson(JsonObject json) {
            return new Finisher(json.has("damage_multiplier") ? json.get("damage_multiplier").getAsDouble() : DEFAULT.damageMultiplier,
                json.has("effect") ? ResourceLocation.parse(json.get("effect").getAsString()) : null,
                json.has("effect_duration") ? json.get("effect_duration").getAsInt() : 60,
                json.has("effect_amplifier") ? json.get("effect_amplifier").getAsInt() : 0,
                json.has("knockback") ? json.get("knockback").getAsDouble() : DEFAULT.knockback);
        }
    }

    /** One gauntlet's definition. */
    public record GauntletDefinition(int comboLength, int comboWindow, double punchRate, Finisher finisher, boolean dualWield,
                                     @Nullable FinisherHook hook) {
        public GauntletDefinition(int comboLength, int comboWindow, double punchRate, Finisher finisher, boolean dualWield) {
            this(comboLength, comboWindow, punchRate, finisher, dualWield, null);
        }

        public GauntletDefinition withHook(@Nullable FinisherHook h) {
            return new GauntletDefinition(comboLength, comboWindow, punchRate, finisher, dualWield, h);
        }

        static GauntletDefinition fromJson(JsonObject json) {
            return new GauntletDefinition(json.has("combo_length") ? Math.max(1, json.get("combo_length").getAsInt()) : 3,
                json.has("combo_window") ? Math.max(1, json.get("combo_window").getAsInt()) : 20,
                json.has("punch_rate") ? Math.max(0, json.get("punch_rate").getAsDouble()) : 0,
                json.has("finisher") ? Finisher.fromJson(json.getAsJsonObject("finisher")) : Finisher.DEFAULT,
                json.has("dual_wield") && json.get("dual_wield").getAsBoolean(), null);
        }
    }

    public static final DataTable<GauntletDefinition> GAUNTLET_DATA = DataTables
        .register(ResourceLocation.fromNamespaceAndPath("fundamentum", "gauntlets"), "fundamentum/gauntlets", GauntletDefinition::fromJson)
        .keyField("item");

    private static final Map<Item, GauntletDefinition> CODE = new ConcurrentHashMap<>();
    private static final Map<UUID, Puncher> PUNCHERS = new ConcurrentHashMap<>();
    private static final ResourceLocation PUNCH_RATE = ResourceLocation.fromNamespaceAndPath("fundamentum", "gauntlet_punch_rate");

    /** One player's combo and the punch now landing. */
    private static final class Puncher {
        final ComboCounter combo = new ComboCounter();
        @Nullable Item item;
        @Nullable LivingEntity target;
        boolean finisher;
        InteractionHand hand = InteractionHand.MAIN_HAND;
    }

    public static void register(Item item, GauntletDefinition def) {
        CODE.put(item, def);
    }

    /** The definition for a gauntlet, or null. */
    public static @Nullable GauntletDefinition def(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        GauntletDefinition code = CODE.get(stack.getItem());
        GauntletDefinition data = GAUNTLET_DATA.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (data == null) {
            return code != null ? code : stack.is(GAUNTLETS) ? new GauntletDefinition(3, 20, 0, Finisher.DEFAULT, false) : null;
        }
        return code != null && data.hook() == null ? data.withHook(code.hook()) : data;
    }

    /** The step a player's combo stands at (0 before a punch). */
    public static int step(Player player) {
        Puncher p = PUNCHERS.get(player.getUUID());
        return p == null ? 0 : p.combo.step();
    }

    /** The gauntlet type as Better Combat sees it: Better Combat plays the left click when loaded. */
    public static final BetterCombat.WeaponType BETTER_COMBAT = BetterCombat.register("gauntlet", GAUNTLETS,
        ResourceLocation.fromNamespaceAndPath("fundamentum", "gauntlet"), BetterCombat.LeftClick.BETTER_COMBAT);

    // ------------------------------------------------------------------------------------------------ punches
    private static void attack(AttackEntityEvent e) {
        Player player = e.getEntity();
        if (player.level().isClientSide() || !(e.getTarget() instanceof LivingEntity target)) {
            return;
        }
        ItemStack main = player.getMainHandItem();
        GauntletDefinition def = def(main);
        Puncher p = PUNCHERS.computeIfAbsent(player.getUUID(), k -> new Puncher());
        if (def == null || BetterCombat.drivesLeftClick(BETTER_COMBAT)) {
            // Not a gauntlet, or Better Combat plays the combo from the weapon attributes: count nothing here.
            p.combo.reset();
            p.target = null;
            return;
        }
        if (p.item != main.getItem() || player.getAttackStrengthScale(0.5F) < 0.8F) {
            p.combo.reset();
            p.item = main.getItem();
            p.target = null;
            if (player.getAttackStrengthScale(0.5F) < 0.8F) {
                return;
            }
        }
        int step = p.combo.punch(player.level().getGameTime(), def.comboWindow(), def.comboLength());
        boolean offHand = def.dualWield() && def(player.getOffhandItem()) != null && step % 2 == 0;
        p.hand = offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        p.target = target;
        p.finisher = p.combo.finisher(def.comboLength());
        if (offHand) {
            player.swing(InteractionHand.OFF_HAND, true);
        }
        Poses.cue(player, p.finisher ? "gauntlet_finisher" : "gauntlet_combo_" + step);
    }

    private static @Nullable Puncher landing(DamageSource source, LivingEntity victim) {
        if (!(source.getEntity() instanceof Player player) || !source.isDirect()) {
            return null;
        }
        Puncher p = PUNCHERS.get(player.getUUID());
        return p != null && p.target == victim ? p : null;
    }

    private static ItemStack gauntlet(Player player, Puncher p) {
        return p.hand == InteractionHand.OFF_HAND ? player.getOffhandItem() : player.getMainHandItem();
    }

    private static void incoming(LivingIncomingDamageEvent e) {
        if (!(e.getSource().getEntity() instanceof Player player) || !e.getSource().isDirect()) {
            return;
        }
        // Any direct punch gets the damage class (also Better Combat's); only our own combo has a finisher to add.
        Puncher p = landing(e.getSource(), e.getEntity());
        ItemStack stack = p != null ? gauntlet(player, p) : player.getMainHandItem();
        GauntletDefinition def = def(stack);
        if (def == null) {
            return;
        }
        float amount = e.getAmount();
        // A gauntlet in a damage class item tag punches as that class: swap the damage type's class stats for its.
        double tagged = 1;
        boolean any = false;
        for (DamageClasses.DamageClass c : DamageClasses.all()) {
            if (stack.is(c.weapons()) && c != DamageClasses.MELEE) {
                tagged *= player.getAttributeValue(c.damage());
                any = true;
            }
        }
        if (any) {
            double current = DamageClasses.multiplier(player, e.getSource());
            amount = (float) (amount * tagged / (current == 0 ? 1 : current));
        }
        if (p != null && p.finisher) {
            amount = (float) (amount * def.finisher().damageMultiplier());
        }
        e.setAmount(amount);
    }

    private static void landed(LivingDamageEvent.Post e) {
        Puncher p = landing(e.getSource(), e.getEntity());
        if (p == null) {
            return;
        }
        Player player = (Player) e.getSource().getEntity();
        boolean finisher = p.finisher;
        p.target = null;
        p.finisher = false;
        if (!finisher) {
            return;
        }
        ItemStack stack = gauntlet(player, p);
        GauntletDefinition def = def(stack);
        if (def == null) {
            return;
        }
        LivingEntity target = e.getEntity();
        Finisher f = def.finisher();
        if (f.effect() != null && target.isAlive()) {
            Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getHolder(f.effect()).orElse(null);
            if (effect != null) {
                target.addEffect(new MobEffectInstance(effect, Math.max(1, f.effectDuration()), Math.max(0, f.effectAmplifier())), player);
            }
        }
        if (f.knockback() > 0) {
            target.knockback(f.knockback(), Math.sin(player.getYRot() * Math.PI / 180.0), -Math.cos(player.getYRot() * Math.PI / 180.0));
        }
        if (def.hook() != null) {
            def.hook().finish(player, target, stack, def);
        }
    }

    /** Keeps the attack speed at the held gauntlet's punch rate. */
    private static void tick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || player.tickCount % 5 != 0) {
            return;
        }
        AttributeInstance speed = player.getAttribute(Attributes.ATTACK_SPEED);
        if (speed == null) {
            return;
        }
        GauntletDefinition def = def(player.getMainHandItem());
        boolean had = speed.getModifier(PUNCH_RATE) != null;
        double before = had ? speed.getModifier(PUNCH_RATE).amount() : 0;
        if (had) {
            speed.removeModifier(PUNCH_RATE);
        }
        if (def != null && def.punchRate() > 0) {
            double delta = def.punchRate() - speed.getValue();
            if (Math.abs(delta) > 1.0E-4) {
                speed.addTransientModifier(new AttributeModifier(PUNCH_RATE, delta, AttributeModifier.Operation.ADD_VALUE));
            }
        } else if (had && before != 0) {
            Puncher p = PUNCHERS.get(player.getUUID());
            if (p != null) {
                p.combo.reset();
            }
        }
    }

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(Gauntlets::attack);
        NeoForge.EVENT_BUS.addListener(Gauntlets::incoming);
        NeoForge.EVENT_BUS.addListener(Gauntlets::landed);
        NeoForge.EVENT_BUS.addListener(Gauntlets::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> PUNCHERS.remove(e.getEntity().getUUID()));
    }

    private Gauntlets() {}
}
