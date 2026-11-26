package com.fundamentum.poise;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.FConfig;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FReg;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Poise and stagger, shared by every combat mod. {@code fundamentum:poise} is how much poise damage a creature takes
 * before it staggers (0, the default, means hits never stagger it). {@code fundamentum:poise_damage} multiplies the poise
 * damage a creature's hits deal; a hit deals its final damage times that. Poise comes back in full once the creature goes
 * {@code recover_ticks} without a hit. A broken poise posts {@code fundamentum:staggered} (cancellable) and gives the
 * {@code fundamentum:staggered} effect, which slows, weakens and stops a mob's pathing. Other mods deal poise damage of
 * their own with {@link #damage}, and stagger with {@link #stagger} (the same call shields and parries use).
 */
public final class Poise {
    public static final FId STAGGERED_ID = FId.of("fundamentum", "staggered");
    public static final FReg.Ref<MobEffect> STAGGERED = FReg.effect(STAGGERED_ID, StaggeredEffect::new);

    private static final Map<LivingEntity, float[]> STATE = new WeakHashMap<>();
    private static ConfigValue<Integer> recoverTicks;
    private static ConfigValue<Integer> staggerTicks;
    private static boolean initialised;

    private Poise() { }

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FConfig c = FConfig.create(FId.of("fundamentum", "poise"));
        recoverTicks = c.integer("recover_ticks", 60, 1, 1200);
        staggerTicks = c.integer("stagger_ticks", 30, 1, 600);
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post.class, Poise::hurt);
        FLife.serverStopped(s -> STATE.clear());
    }

    /** The effect's holder, once registered. */
    public static Holder<MobEffect> effect() {
        return BuiltInRegistries.MOB_EFFECT.wrapAsHolder(STAGGERED.get());
    }

    /** Poise left before the entity staggers (its max when it has not been hit lately); 0 for an entity without poise. */
    public static double current(LivingEntity entity) {
        double max = entity.getAttributeValue(StatAttributes.POISE);
        if (max <= 0) return 0;
        float[] s = STATE.get(entity);
        if (s == null || entity.tickCount - (long) s[1] > recover()) return max;
        return Math.max(0, max - s[0]);
    }

    public static boolean staggered(LivingEntity entity) {
        return STAGGERED.bound() && entity.hasEffect(effect());
    }

    /**
     * Deals {@code amount} poise damage to {@code target} (already scaled by the attacker's poise_damage, if wanted).
     * Returns true when this broke its poise and it staggered.
     */
    public static boolean damage(LivingEntity target, Entity attacker, double amount) {
        if (amount <= 0 || target.level().isClientSide()) return false;
        double max = target.getAttributeValue(StatAttributes.POISE);
        if (max <= 0) return false;
        float[] s = STATE.computeIfAbsent(target, t -> new float[] {0, 0});
        if (target.tickCount - (long) s[1] > recover()) s[0] = 0;
        s[0] += (float) amount;
        s[1] = target.tickCount;
        if (s[0] < max) return false;
        s[0] = 0;
        return stagger(target, attacker, staggerTicks == null ? 30 : staggerTicks.get());
    }

    /**
     * Staggers {@code target} for {@code ticks} ({@code from} may be null): posts {@code fundamentum:staggered}
     * (cancellable), then gives the effect. Returns false when the event was cancelled.
     */
    public static boolean stagger(LivingEntity target, Entity from, int ticks) {
        if (ticks <= 0 || target.level().isClientSide()) return false;
        if (FEvents.post(new Events.Staggered(target, from, ticks))) return false;
        if (STAGGERED.bound()) target.addEffect(new MobEffectInstance(effect(), ticks, 0), from);
        if (target instanceof Mob mob) mob.getNavigation().stop();
        return true;
    }

    private static int recover() {
        return recoverTicks == null ? 60 : recoverTicks.get();
    }

    private static void hurt(LivingDamageEvent.Post event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || event.getInflictedDamage() <= 0) return;
        Entity attacker = event.getSource().getEntity();
        double mult = attacker instanceof LivingEntity l ? l.getAttributeValue(StatAttributes.POISE_DAMAGE) : 1.0;
        if (attacker instanceof Player p && event.getSource().getDirectEntity() == p && p.getAttackStrengthScale(0.5F) < 0.9F) {
            mult *= 0.5; // spammed swings break less poise
        }
        damage(target, attacker, event.getInflictedDamage() * mult);
    }
}
