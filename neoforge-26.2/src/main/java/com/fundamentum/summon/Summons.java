package com.fundamentum.summon;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.data.Unknown;
import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.slots.Slots;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.whip.WhipTags;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Summons, Terraria style: each item in the {@code summon} Curios slot calls its minions, which follow the player, fight
 * whatever hurts them or whatever they hit (and hostile creatures near them), and never hurt the player or each other.
 * More summon slots come from Curios' own slot modifiers on gear (an attribute modifier on {@code curios:summon}), so a
 * summoner build is armour and accessories that add slots, plus the {@code fundamentum:summon_damage} attribute, which
 * scales every hit a player's minions land. Which creature an item calls is data, in
 * {@code data/<namespace>/fundamentum/summons/<name>.json}, keyed by its "item" field (default: the file id):
 * <pre>{"item": "mymod:bone_staff", "entity": "minecraft:wolf", "count": 1, "health": 20, "damage": 4, "speed": 0.3,
 *  "respawn": 100, "leash": 24, "fire_immune": false}</pre>
 * Only "entity" is required. "count" minions per item; stats set the creature's attributes where it has them; a
 * minion that dies comes back "respawn" ticks later (5 s by default); one further than "leash" blocks from its player
 * is brought back to them. Minions drop nothing, are never saved and are dismissed when the item leaves the slot, or
 * when the player dies, logs out or changes dimension (they come back with the player). Any creature works; tamable
 * ones are tamed to the player, and creatures with no attack of their own get a melee one.
 */
public final class Summons {
    /** One summon item's definition. Stats left null keep the creature's own values. */
    public record Def(Identifier entity, int count, @Nullable Double health, @Nullable Double damage, @Nullable Double speed, int respawn,
                      double leash, boolean fireImmune) {
        static Def fromJson(JsonObject json) {
            return new Def(Identifier.parse(json.get("entity").getAsString()), Math.max(1, integer(json, "count", 1)), number(json, "health"),
                number(json, "damage"), number(json, "speed"), Math.max(0, integer(json, "respawn", 100)),
                json.has("leash") ? Math.max(4.0, json.get("leash").getAsDouble()) : 24.0, json.has("fire_immune") && json.get("fire_immune").getAsBoolean());
        }

        private static @Nullable Double number(JsonObject json, String field) {
            return json.has(field) ? json.get(field).getAsDouble() : null;
        }

        private static int integer(JsonObject json, String field, int fallback) {
            return json.has(field) ? json.get(field).getAsInt() : fallback;
        }
    }

    public static final DataTable<Def> SUMMONS = DataTables
        .register(Identifier.fromNamespaceAndPath("fundamentum", "summons"), "fundamentum/summons", Def::fromJson)
        .keyField("item");

    /** Persistent-data key on a minion: its player's UUID. */
    private static final String OWNER = "fundamentum:summon_owner";
    /** Each player's minions, by the item that called them, and when a fallen one may come back. */
    private static final Map<UUID, Party> PARTIES = new ConcurrentHashMap<>();

    private static final class Party {
        final Map<Item, List<Mob>> minions = new HashMap<>();
        final Map<Item, Long> nextSpawn = new HashMap<>();
    }

    /** The definition for a summon item, or null when it is not one. */
    public static @Nullable Def def(ItemStack stack) {
        return stack.isEmpty() ? null : SUMMONS.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** A player's minions now. */
    public static List<Mob> minions(Player player) {
        Party party = PARTIES.get(player.getUUID());
        List<Mob> all = new ArrayList<>();
        if (party != null) {
            party.minions.values().forEach(all::addAll);
        }
        return all;
    }

    /** True for a creature Fundamentum summoned as a minion. */
    public static boolean isMinion(Entity entity) {
        return entity.getPersistentData().contains(OWNER);
    }

    /** A minion's player, or null (not a minion, or the player is not on this server). */
    public static @Nullable ServerPlayer owner(Entity entity) {
        if (!isMinion(entity) || !(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        try {
            return level.getServer().getPlayerList().getPlayer(UUID.fromString(entity.getPersistentData().getStringOr(OWNER, "")));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when two entities fight on the same side: a player and their minions, or minions of one player. */
    public static boolean allies(Entity a, Entity b) {
        UUID ua = side(a), ub = side(b);
        return ua != null && ua.equals(ub);
    }

    private static @Nullable UUID side(Entity e) {
        if (e instanceof Player p) {
            return p.getUUID();
        }
        try {
            return isMinion(e) ? UUID.fromString(e.getPersistentData().getStringOr(OWNER, "")) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Dismisses all of a player's minions (they come back on the next check if the items are still worn). */
    public static void dismiss(Player player) {
        Party party = PARTIES.remove(player.getUUID());
        if (party != null) {
            party.minions.values().forEach(list -> list.forEach(Summons::vanish));
        }
    }

    // ------------------------------------------------------------------------------------------------ keeping the party
    /** Every half second: call missing minions, send away ones whose item left the slot, bring back strays, pick targets. */
    private static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 10 != 0) {
            return;
        }
        if (!player.isAlive() || player.isSpectator()) {
            dismiss(player);
            return;
        }
        Map<Item, Integer> wanted = new HashMap<>();
        Map<Item, Def> defs = new HashMap<>();
        for (ItemStack stack : Slots.allEquipped(player, Slots.SUMMON)) {
            Def def = def(stack);
            if (def != null) {
                wanted.merge(stack.getItem(), def.count(), Integer::sum);
                defs.put(stack.getItem(), def);
            }
        }
        Party party = PARTIES.computeIfAbsent(player.getUUID(), k -> new Party());
        long now = player.level().getGameTime();
        for (var it = party.minions.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            int keep = wanted.getOrDefault(entry.getKey(), 0);
            List<Mob> list = entry.getValue();
            for (var m = list.iterator(); m.hasNext(); ) {
                Mob mob = m.next();
                if (!mob.isAlive() || mob.isRemoved() || mob.level() != player.level()) {
                    m.remove();
                    if (!mob.isRemoved()) {
                        mob.discard();
                    }
                    party.nextSpawn.putIfAbsent(entry.getKey(), now + (defs.containsKey(entry.getKey()) ? defs.get(entry.getKey()).respawn() : 0));
                }
            }
            while (list.size() > keep) {
                vanish(list.remove(list.size() - 1));
            }
            if (list.isEmpty() && keep == 0) {
                it.remove();
            }
        }
        wanted.forEach((item, count) -> {
            List<Mob> list = party.minions.computeIfAbsent(item, k -> new ArrayList<>());
            Long next = party.nextSpawn.get(item);
            if (list.size() < count && (next == null || now >= next)) {
                party.nextSpawn.remove(item);
                while (list.size() < count) {
                    Mob mob = spawn(player, defs.get(item));
                    if (mob == null) {
                        break;
                    }
                    list.add(mob);
                }
            }
        });
        LivingEntity target = target(player);
        party.minions.forEach((item, list) -> list.forEach(mob -> keep(player, mob, defs.get(item), target)));
    }

    private static @Nullable Mob spawn(ServerPlayer player, Def def) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(def.entity()).orElse(null);
        ServerLevel level = player.level();
        Entity entity = type == null ? null : type.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (type == null) {
            Unknown.warn("entity", def.entity(), "summon");
        }
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return null;
        }
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        mob.snapTo(player.getX() + Math.cos(angle) * 1.5, player.getY(), player.getZ() + Math.sin(angle) * 1.5, player.getYRot(), 0.0F);
        mob.getPersistentData().putString(OWNER, player.getUUID().toString());
        set(mob, Attributes.MAX_HEALTH, def.health());
        set(mob, Attributes.ATTACK_DAMAGE, def.damage());
        set(mob, Attributes.MOVEMENT_SPEED, def.speed());
        mob.setHealth(mob.getMaxHealth());
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
        if (def.fireImmune()) {
            mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        }
        if (mob instanceof TamableAnimal tamable) {
            tamable.tame(player);
        }
        // Minions pick their targets from their player's fights (keep), not from their own instincts.
        mob.targetSelector.removeAllGoals(g -> true);
        if (mob instanceof PathfinderMob pathfinder && pathfinder.getAttribute(Attributes.ATTACK_DAMAGE) != null && !attacks(mob)) {
            mob.goalSelector.addGoal(1, new MeleeAttackGoal(pathfinder, 1.2, true));
        }
        if (FEvents.post(new Events.SummonCreated(mob, player))) {
            mob.discard();
            return null;
        }
        SPAWNING.set(true);
        try {
            level.addFreshEntity(mob);
        } finally {
            SPAWNING.set(false);
        }
        poof(mob);
        return mob;
    }

    private static boolean attacks(Mob mob) {
        for (var wrapped : mob.goalSelector.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (goal instanceof MeleeAttackGoal || goal instanceof RangedAttackGoal || goal instanceof RangedBowAttackGoal<?>
                || goal instanceof RangedCrossbowAttackGoal<?>) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the party should fight: what the player's summoner whip marked, whoever last hurt the player, or what the
     * player last hit, or a hostile creature near.
     */
    private static @Nullable LivingEntity target(ServerPlayer player) {
        LivingEntity marked = WhipTags.focus(player);
        if (fightable(player, marked)) {
            return marked;
        }
        for (LivingEntity t : new LivingEntity[] {player.getLastHurtByMob(), player.getLastHurtMob()}) {
            if (fightable(player, t) && t.distanceToSqr(player) < 24 * 24) {
                return t;
            }
        }
        LivingEntity best = null;
        double bestDist = 12 * 12;
        for (Mob m : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(12.0), e -> e instanceof Enemy)) {
            double d = m.distanceToSqr(player);
            if (d < bestDist && fightable(player, m)) {
                best = m;
                bestDist = d;
            }
        }
        return best;
    }

    private static boolean fightable(ServerPlayer player, @Nullable LivingEntity t) {
        return t != null && t.isAlive() && !allies(player, t) && t.level() == player.level() && !(t instanceof Player p && (p.isCreative() || p.isSpectator()));
    }

    private static void keep(ServerPlayer player, Mob mob, @Nullable Def def, @Nullable LivingEntity target) {
        double leash = def == null ? 24.0 : def.leash();
        double dist = mob.distanceToSqr(player);
        if (dist > leash * leash) {
            mob.teleportTo(player.getX(), player.getY(), player.getZ());
            mob.getNavigation().stop();
            mob.setTarget(null);
            return;
        }
        if (target != null && (mob.getTarget() == null || !mob.getTarget().isAlive() || allies(mob, mob.getTarget())
            || target != mob.getTarget() && WhipTags.mark(target, player.getUUID()) != null)) {
            mob.setTarget(target);
        } else if (target == null && mob.getTarget() != null && (!mob.getTarget().isAlive() || allies(mob, mob.getTarget()))) {
            mob.setTarget(null);
        }
        if (mob.getTarget() == null && dist > 4.0 * 4.0) {
            mob.getNavigation().moveTo(player, 1.15);
        }
    }

    private static void set(LivingEntity entity, Holder<Attribute> attribute, @Nullable Double value) {
        AttributeInstance instance = value == null ? null : entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static void vanish(Mob mob) {
        if (!mob.isRemoved()) {
            poof(mob);
            mob.discard();
        }
    }

    private static void poof(Entity e) {
        if (e.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ(), 10, e.getBbWidth() / 2, e.getBbHeight() / 3,
                e.getBbWidth() / 2, 0.02);
        }
    }

    // ------------------------------------------------------------------------------------------------ events
    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(Summons::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> dismiss(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> dismiss(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof Player player) {
                dismiss(player);
            }
        });
        // No fighting within a side; minion hits take their player's whip mark bonus, then scale with summon_damage.
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent e) -> {
            Entity attacker = e.getSource().getEntity();
            if (attacker == null) {
                return;
            }
            if (allies(attacker, e.getEntity())) {
                e.setCanceled(true);
                return;
            }
            ServerPlayer owner = owner(attacker);
            if (owner != null) {
                float amount = WhipTags.minionHit(owner.getUUID(), e.getEntity(), e.getAmount(), owner.getRandom());
                e.setAmount((float) (amount * owner.getAttributeValue(StatAttributes.SUMMON_DAMAGE)));
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingChangeTargetEvent e) -> {
            LivingEntity target = e.getNewAboutToBeSetTarget();
            if (target != null && isMinion(e.getEntity()) && allies(e.getEntity(), target)) {
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingDropsEvent e) -> {
            if (isMinion(e.getEntity())) {
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingExperienceDropEvent e) -> {
            if (isMinion(e.getEntity())) {
                e.setCanceled(true);
            }
        });
        // A minion loaded from disk (after a crash, say) belongs to no party: remove it.
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (!e.getLevel().isClientSide() && isMinion(e.getEntity())
                && PARTIES.values().stream().noneMatch(p -> p.minions.values().stream().anyMatch(l -> l.contains(e.getEntity())))
                && !SPAWNING.get()) {
                e.setCanceled(true);
            }
        });
    }

    /** True while spawn() (or Sentries.place) adds a fresh minion, so the leftover check above lets it in. */
    static final ThreadLocal<Boolean> SPAWNING = ThreadLocal.withInitial(() -> false);

    private Summons() {}
}
