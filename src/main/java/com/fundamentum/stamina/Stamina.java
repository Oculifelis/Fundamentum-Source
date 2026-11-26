package com.fundamentum.stamina;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.LivingEntity;

/**
 * The stamina bridge, built like {@code com.fundamentum.mana.Mana}. Mods register a {@link StaminaSource} for the pool
 * they own; a weapon, dodge, HUD bar or set bonus from any other mod reads and spends stamina here. For each entity the
 * source with the highest priority that handles it is used (ties: lowest id first). With no source the entity has no
 * stamina limit: {@link #spend} succeeds and {@link #get} gives 0, so actions never lock up for lack of a stamina mod.
 */
public final class Stamina {
    private static final List<StaminaSource> SOURCES = new CopyOnWriteArrayList<>();
    private static final Comparator<StaminaSource> ORDER = Comparator.comparingInt(StaminaSource::priority).reversed()
        .thenComparing(s -> s.id().toString());

    private Stamina() { }

    public static void register(StaminaSource source) {
        synchronized (SOURCES) {
            SOURCES.removeIf(s -> s.id().equals(source.id()));
            SOURCES.add(source);
            List<StaminaSource> sorted = new ArrayList<>(SOURCES);
            sorted.sort(ORDER);
            SOURCES.clear();
            SOURCES.addAll(sorted);
        }
    }

    public static boolean unregister(FId id) {
        synchronized (SOURCES) {
            return SOURCES.removeIf(s -> s.id().equals(id));
        }
    }

    public static List<StaminaSource> sources() {
        return List.copyOf(SOURCES);
    }

    public static Optional<StaminaSource> source(LivingEntity entity) {
        for (StaminaSource s : SOURCES) {
            if (s.handles(entity)) return Optional.of(s);
        }
        return Optional.empty();
    }

    public static boolean has(LivingEntity entity) {
        return source(entity).isPresent();
    }

    public static double get(LivingEntity entity) {
        return source(entity).map(s -> s.get(entity)).orElse(0.0);
    }

    public static double max(LivingEntity entity) {
        return source(entity).map(s -> s.max(entity)).orElse(0.0);
    }

    /** 0 to 1 for bars; 1 for an entity with no pool (nothing to show as spent). */
    public static double fraction(LivingEntity entity) {
        Optional<StaminaSource> s = source(entity);
        if (s.isEmpty()) return 1.0;
        double max = s.get().max(entity);
        return max <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, s.get().get(entity) / max));
    }

    public static double regenPerTick(LivingEntity entity) {
        return source(entity).map(s -> s.regenPerTick(entity)).orElse(0.0);
    }

    public static boolean canSpend(LivingEntity entity, double amount) {
        if (amount <= 0) return amount == 0;
        return source(entity).map(s -> s.get(entity) >= amount).orElse(true);
    }

    /**
     * Takes {@code amount}, all or nothing, posting {@code fundamentum:stamina_spent} (cancellable) first. With no source
     * it succeeds and takes nothing. A negative amount is refused.
     */
    public static boolean spend(LivingEntity entity, double amount) {
        if (amount < 0) return false;
        if (amount == 0) return true;
        Optional<StaminaSource> s = source(entity);
        if (s.isEmpty()) return true;
        double now = s.get().get(entity);
        if (now < amount) return false;
        if (FEvents.post(new Events.StaminaSpent(entity, amount, now - amount))) return false;
        return s.get().spend(entity, amount);
    }

    public static double restore(LivingEntity entity, double amount) {
        if (amount <= 0) return 0.0;
        return source(entity).map(s -> s.restore(entity, amount)).orElse(0.0);
    }
}
