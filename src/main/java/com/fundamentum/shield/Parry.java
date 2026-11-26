package com.fundamentum.shield;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FLife;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * A parry window for any weapon, not only shields. A mod opens a window when its parry move starts ({@link #open});
 * the first hit with an attacker inside it is cancelled, a melee attacker is knocked back and staggered, and a projectile
 * flies back. It posts {@code fundamentum:shield_blocked} with {@code parried} true and the weapon in place of the shield,
 * the same event a shield parry posts, so one listener sees every perfect block.
 */
public final class Parry {
    /** One open window. */
    public record Window(ItemStack weapon, long until, double knockback, int staggerTicks, boolean reflect) { }

    private static final Map<LivingEntity, Window> OPEN = new WeakHashMap<>();
    private static boolean initialised;

    private Parry() { }

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, Parry::incoming);
        FLife.serverStopped(s -> OPEN.clear());
    }

    /** Opens a parry window of {@code ticks} for {@code defender} (server side), replacing any open one. */
    public static void open(LivingEntity defender, ItemStack weapon, int ticks, double knockback, int staggerTicks, boolean reflect) {
        if (defender.level().isClientSide() || ticks <= 0) return;
        OPEN.put(defender, new Window(weapon.copy(), defender.level().getGameTime() + ticks, knockback, staggerTicks, reflect));
    }

    /** A window with the usual values: 0.6 knockback, 30 ticks of stagger, projectiles reflected. */
    public static void open(LivingEntity defender, ItemStack weapon, int ticks) {
        open(defender, weapon, ticks, 0.6, 30, true);
    }

    public static boolean isOpen(LivingEntity defender) {
        Window w = OPEN.get(defender);
        return w != null && defender.level().getGameTime() <= w.until();
    }

    public static void close(LivingEntity defender) {
        OPEN.remove(defender);
    }

    /**
     * Tries to parry {@code source} hitting {@code defender} now. Returns true when it was parried (the caller cancels the
     * hit). Called by the listener below; a mod with its own damage path may call it too.
     */
    public static boolean tryParry(LivingEntity defender, DamageSource source, float amount) {
        if (!isOpen(defender) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || source.getEntity() == null) return false;
        Window w = OPEN.remove(defender);
        FEvents.post(new Events.ShieldBlocked(defender, w.weapon(), amount, true));
        if (defender.level() instanceof ServerLevel level) {
            level.playSound(null, defender.getX(), defender.getY(), defender.getZ(), SoundEvents.ANVIL_LAND,
                defender.getSoundSource(), 0.5F, 1.7F);
        }
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile p) {
            if (w.reflect()) {
                Entity shooter = source.getEntity();
                Vec3 to = shooter != null && shooter != defender ? shooter.getEyePosition().subtract(p.position()) : defender.getLookAngle();
                p.setDeltaMovement(to.normalize().scale(Math.max(0.5, p.getDeltaMovement().length())));
                p.setOwner(defender);
                p.syncVelocity = true;
            }
        } else if (direct instanceof LivingEntity attacker) {
            Shields.stagger(attacker, defender, w.knockback(), w.staggerTicks());
        }
        return true;
    }

    private static void incoming(LivingIncomingDamageEvent event) {
        if (tryParry(event.getEntity(), event.getSource(), event.getAmount())) event.setCanceled(true);
    }
}
