package com.fundamentum.mana;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * The mana bridge. Mods register a {@link ManaSource} for the pool they own; anything else (a staff from another mod, a
 * HUD bar, a set bonus) reads and spends mana here without knowing which mod holds it. For each entity the source with
 * the highest {@link ManaSource#priority()} that {@link ManaSource#handles handles} it is used (ties: lowest id first).
 * With no source, the entity has no mana: {@link #get} and {@link #max} give 0 and {@link #spend} fails, except that
 * spending 0 always succeeds.
 *
 * <p>Fundamentum has no pool of its own and no compat for Iron's Spells or Ars Nouveau built in: those mods own their
 * mana, and the mod that wants them registers a source that calls their API.
 *
 * <pre>{@code
 * Mana.register(new ManaSource() { ... });            // in the owning mod's setup
 * if (Mana.spend(player, 25)) castSpell(player);      // anywhere
 * }</pre>
 */
public final class Mana {
    private static final List<ManaSource> SOURCES = new CopyOnWriteArrayList<>();
    private static final Comparator<ManaSource> ORDER = Comparator.comparingInt(ManaSource::priority).reversed()
        .thenComparing(s -> s.id().toString());

    private Mana() {
    }

    /** Adds a source, replacing any source with the same id. */
    public static void register(ManaSource source) {
        synchronized (SOURCES) {
            SOURCES.removeIf(s -> s.id().equals(source.id()));
            SOURCES.add(source);
            List<ManaSource> sorted = new ArrayList<>(SOURCES);
            sorted.sort(ORDER);
            SOURCES.clear();
            SOURCES.addAll(sorted);
        }
    }

    /** Removes the source with this id; returns whether there was one. */
    public static boolean unregister(ResourceLocation id) {
        synchronized (SOURCES) {
            return SOURCES.removeIf(s -> s.id().equals(id));
        }
    }

    /** Every registered source, highest priority first. */
    public static List<ManaSource> sources() {
        return List.copyOf(SOURCES);
    }

    /** The source used for this entity, if any. */
    public static Optional<ManaSource> source(LivingEntity entity) {
        for (ManaSource s : SOURCES) {
            if (s.handles(entity)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** One named source, if it is registered and handles this entity (to ask for a pool other than the preferred one). */
    public static Optional<ManaSource> source(LivingEntity entity, ResourceLocation id) {
        for (ManaSource s : SOURCES) {
            if (s.id().equals(id)) return s.handles(entity) ? Optional.of(s) : Optional.empty();
        }
        return Optional.empty();
    }

    /** Whether any source holds mana for this entity. */
    public static boolean has(LivingEntity entity) {
        return source(entity).isPresent();
    }

    public static double get(LivingEntity entity) {
        return source(entity).map(s -> s.get(entity)).orElse(0.0);
    }

    public static double max(LivingEntity entity) {
        return source(entity).map(s -> s.max(entity)).orElse(0.0);
    }

    /** {@link #get} over {@link #max}, 0 to 1 (0 with no pool), for bars. */
    public static double fraction(LivingEntity entity) {
        Optional<ManaSource> s = source(entity);
        if (s.isEmpty()) return 0.0;
        double max = s.get().max(entity);
        return max <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, s.get().get(entity) / max));
    }

    public static double regenPerTick(LivingEntity entity) {
        return source(entity).map(s -> s.regenPerTick(entity)).orElse(0.0);
    }

    /** Whether the entity has at least {@code amount}. */
    public static boolean canSpend(LivingEntity entity, double amount) {
        if (amount <= 0) return amount == 0;
        return source(entity).map(s -> s.get(entity) >= amount).orElse(false);
    }

    /** Takes {@code amount}, all or nothing. A negative amount is refused; 0 always succeeds. */
    public static boolean spend(LivingEntity entity, double amount) {
        if (amount <= 0) return amount == 0;
        Optional<ManaSource> s = source(entity);
        return s.isPresent() && s.get().get(entity) >= amount && s.get().spend(entity, amount);
    }

    /** Adds up to {@code amount}, stopping at the pool's max; returns how much was added (0 with no pool). */
    public static double restore(LivingEntity entity, double amount) {
        if (amount <= 0) return 0.0;
        return source(entity).map(s -> s.restore(entity, amount)).orElse(0.0);
    }
}
