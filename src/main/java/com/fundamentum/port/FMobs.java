package com.fundamentum.port;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;

/**
 * Mob calls that differ by loader or version: a hook for mobs joining a server level (new or loaded from disk), whether
 * a mob runs on a brain, and a mob's own melee hit. Used by {@code com.fundamentum.mobgoal}.
 */
public final class FMobs {
    private FMobs() { }

    private static final List<Consumer<Mob>> JOINED = new CopyOnWriteArrayList<>();
    private static boolean started;

    public static void init(IEventBus modBus) {
        if (started) return;
        started = true;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (!e.getLevel().isClientSide() && e.getEntity() instanceof Mob mob) JOINED.forEach(a -> a.accept(mob));
        });
    }

    /** Runs for every mob that joins a server level, on the server thread. */
    public static void joined(Consumer<Mob> action) {
        JOINED.add(action);
    }

    /** Makes the server send the mob's new motion to clients this tick (after a goal pushes it). */
    public static void pushed(Mob mob) {
        mob.hurtMarked = true;
    }

    /** True when the mob's behaviour runs on a brain (villagers, piglins and the like) rather than goals. */
    public static boolean brainDriven(Mob mob) {
        return !mob.getBrain().getActiveActivities().isEmpty();
    }

    /** The mob's normal melee hit on {@code target} (weapon, enchantments, knockback). True when it hurt. */
    public static boolean hurtTarget(Mob mob, Entity target) {
        return mob.doHurtTarget(target);
    }
}
