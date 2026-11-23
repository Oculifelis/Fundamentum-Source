package com.fundamentum.whip;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Summoner-whip marks (Terraria's whip tags). A whip with a {@link WhipDefinition.SummonTag} marks what it hits for its
 * player; while the mark lasts, that player's minions focus the marked target and hit it harder. Fundamentum's own
 * minions ({@code com.fundamentum.summon.Summons}) do both. A mod's own minions call {@link #focus} for a target and
 * {@link #minionHit} on each hit. Marks are per player (two summoners can mark one mob), last a whip's tag only (a new
 * whip's mark replaces the old one) and live on the server only, never saved.
 */
public final class WhipTags {
    /** A live mark: whose, from which whip, its tag, and the server tick it runs out on. */
    public record Mark(UUID owner, Identifier whip, WhipDefinition.SummonTag tag, int until) {}

    /** target -> owner -> mark */
    private static final Map<UUID, Map<UUID, Mark>> MARKS = new ConcurrentHashMap<>();
    /** owner -> what they marked last */
    private static final Map<UUID, UUID> FOCUS = new ConcurrentHashMap<>();

    /** Marks {@code target} for {@code owner}'s minions (what a whip hit does). */
    public static void apply(ServerPlayer owner, Entity target, Identifier whip, WhipDefinition.SummonTag tag) {
        int until = owner.level().getServer().getTickCount() + tag.ticks();
        MARKS.computeIfAbsent(target.getUUID(), k -> new ConcurrentHashMap<>()).put(owner.getUUID(), new Mark(owner.getUUID(), whip, tag, until));
        FOCUS.put(owner.getUUID(), target.getUUID());
    }

    /** {@code owner}'s live mark on {@code target}, or null. */
    public static @Nullable Mark mark(Entity target, UUID owner) {
        Map<UUID, Mark> marks = MARKS.get(target.getUUID());
        Mark m = marks == null ? null : marks.get(owner);
        return m != null && target.level() instanceof ServerLevel level && level.getServer().getTickCount() <= m.until() ? m : null;
    }

    /** True while anyone's mark is on {@code target}. */
    public static boolean marked(Entity target) {
        Map<UUID, Mark> marks = MARKS.get(target.getUUID());
        return marks != null && marks.keySet().stream().anyMatch(o -> mark(target, o) != null);
    }

    /** What {@code owner}'s minions should go for: the last thing they marked, while it is alive, marked and near. */
    public static @Nullable LivingEntity focus(ServerPlayer owner) {
        UUID id = FOCUS.get(owner.getUUID());
        Entity e = id == null ? null : owner.level().getEntity(id);
        return e instanceof LivingEntity l && l.isAlive() && mark(l, owner.getUUID()) != null && l.distanceToSqr(owner) < 32 * 32 ? l : null;
    }

    /** A minion of {@code owner} hits {@code target} for {@code amount}: the mark's bonus and crit, or the amount as it was. */
    public static float minionHit(UUID owner, Entity target, float amount, RandomSource random) {
        Mark m = mark(target, owner);
        if (m == null) {
            return amount;
        }
        double hit = (amount + m.tag().flat()) * m.tag().multiplier();
        if (m.tag().critChance() > 0 && random.nextDouble() < m.tag().critChance()) {
            hit *= 2;
        }
        return (float) hit;
    }

    /** Takes every mark off a target. */
    public static void clear(Entity target) {
        MARKS.remove(target.getUUID());
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            MinecraftServer server = e.getServer();
            if (server.getTickCount() % 100 == 0) {
                int now = server.getTickCount();
                MARKS.values().forEach(marks -> marks.values().removeIf(m -> m.until() < now));
                MARKS.values().removeIf(Map::isEmpty);
                FOCUS.values().removeIf(t -> !MARKS.containsKey(t));
            }
        });
    }

    private WhipTags() {}
}
