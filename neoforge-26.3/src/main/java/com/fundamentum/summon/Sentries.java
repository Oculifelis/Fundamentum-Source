package com.fundamentum.summon;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.data.Unknown;
import com.fundamentum.whip.WhipTags;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Sentries: placed, stationary turrets that belong to the Summons system. A sentry item is used on a block to place its
 * turret there; the turret stays put, turns to the nearest foe in range and attacks at its fire rate, never hurts its
 * player or their minions, and scales its hits with {@code fundamentum:summon_damage} like any minion. How many a player
 * may have out is the {@code fundamentum:sentry_slots} attribute (1 to start, separate from the minion slots), which
 * armour and accessories raise with ordinary attribute modifiers. Placing one past the limit sends away the oldest first.
 * Sentries also go when their lifetime runs out, when their player logs out or changes dimension, or when the player
 * walks further than the sentry's leash from it. They drop nothing and are never saved.
 * <p>
 * Stats are data, in {@code data/<namespace>/fundamentum/sentries/<name>.json}, keyed by "item" (default: the file id):
 * <pre>{"item": "mymod:flame_turret_staff", "entity": "mymod:flame_turret", "slots": 1, "placement": "ground",
 *  "health": 40, "damage": 6, "range": 16, "fire_rate": 1.5, "lifetime": 0, "leash": 0, "cooldown": 10,
 *  "projectile": "minecraft:small_fireball", "projectile_speed": 1.2}</pre>
 * Only "entity" is required. "placement" is ground, wall, ceiling or any; "fire_rate" is attacks per second; "lifetime"
 * is in ticks (0 keeps it until sent away); "leash" in blocks (0: no leash); "cooldown" is the item's cooldown after a
 * placement. The attack is, in order: the code hook given to {@link #register}, else the "projectile" fired at the
 * target ({@code damage} sets an arrow's damage), else the creature's own ranged attack when it has one, else a melee
 * hit of {@code damage} on a target within 3 blocks. Code registers with
 * {@code Sentries.register(item, SentryDefinition.builder(entityId).range(20).fireRate(2).attack(hook).build())}; a data
 * file for the same item overrides the stats and keeps the hook.
 */
public final class Sentries {
    /** How many sentry slots a player has: each placed sentry uses its "slots" cost. */
    public static final Holder<Attribute> SENTRY_SLOTS = StatAttributes.add("sentry_slots", 1, 0, 1024);

    /** Where a sentry may stand. */
    public enum Placement {
        GROUND, WALL, CEILING, ANY;

        static Placement parse(String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return GROUND;
            }
        }

        boolean allows(Direction face) {
            return switch (this) {
                case GROUND -> face == Direction.UP;
                case CEILING -> face == Direction.DOWN;
                case WALL -> face.getAxis().isHorizontal();
                case ANY -> true;
            };
        }
    }

    /** A sentry's attack: return true when it fired (the fire-rate timer then restarts). */
    @FunctionalInterface
    public interface Attack {
        boolean fire(Mob sentry, LivingEntity target, ServerPlayer owner, SentryDefinition def);
    }

    /** One sentry item's definition. */
    public record SentryDefinition(Identifier entity, double slots, Placement placement, @Nullable Double health, double damage, double range,
                                   double fireRate, int lifetime, double leash, int cooldown, @Nullable Identifier projectile,
                                   double projectileSpeed, @Nullable Attack attack) {
        /** Ticks between attacks. */
        public int interval() {
            return Math.max(1, (int) Math.round(20.0 / Math.max(0.05, fireRate)));
        }

        public SentryDefinition withAttack(@Nullable Attack hook) {
            return new SentryDefinition(entity, slots, placement, health, damage, range, fireRate, lifetime, leash, cooldown, projectile,
                projectileSpeed, hook);
        }

        public static Builder builder(Identifier entity) {
            return new Builder(entity);
        }

        static SentryDefinition fromJson(JsonObject json) {
            Builder b = builder(Identifier.parse(json.get("entity").getAsString()));
            if (json.has("slots")) b.slots(json.get("slots").getAsDouble());
            if (json.has("placement")) b.placement(Placement.parse(json.get("placement").getAsString()));
            if (json.has("health")) b.health(json.get("health").getAsDouble());
            if (json.has("damage")) b.damage(json.get("damage").getAsDouble());
            if (json.has("range")) b.range(json.get("range").getAsDouble());
            if (json.has("fire_rate")) b.fireRate(json.get("fire_rate").getAsDouble());
            if (json.has("lifetime")) b.lifetime(json.get("lifetime").getAsInt());
            if (json.has("leash")) b.leash(json.get("leash").getAsDouble());
            if (json.has("cooldown")) b.cooldown(json.get("cooldown").getAsInt());
            if (json.has("projectile")) b.projectile(Identifier.parse(json.get("projectile").getAsString()));
            if (json.has("projectile_speed")) b.projectileSpeed(json.get("projectile_speed").getAsDouble());
            return b.build();
        }
    }

    /** Builds a {@link SentryDefinition}; every stat has a default. */
    public static final class Builder {
        private final Identifier entity;
        private double slots = 1, damage = 4, range = 16, fireRate = 1, leash = 0, projectileSpeed = 1.6;
        private Placement placement = Placement.GROUND;
        private @Nullable Double health;
        private int lifetime = 0, cooldown = 10;
        private @Nullable Identifier projectile;
        private @Nullable Attack attack;

        Builder(Identifier entity) {
            this.entity = entity;
        }

        public Builder slots(double v) { slots = Math.max(0, v); return this; }
        public Builder placement(Placement v) { placement = v; return this; }
        public Builder health(double v) { health = v; return this; }
        public Builder damage(double v) { damage = Math.max(0, v); return this; }
        public Builder range(double v) { range = Math.max(1, v); return this; }
        public Builder fireRate(double v) { fireRate = Math.max(0.05, v); return this; }
        public Builder lifetime(int ticks) { lifetime = Math.max(0, ticks); return this; }
        public Builder leash(double v) { leash = Math.max(0, v); return this; }
        public Builder cooldown(int ticks) { cooldown = Math.max(0, ticks); return this; }
        public Builder projectile(@Nullable Identifier v) { projectile = v; return this; }
        public Builder projectileSpeed(double v) { projectileSpeed = Math.max(0.05, v); return this; }
        public Builder attack(@Nullable Attack v) { attack = v; return this; }

        public SentryDefinition build() {
            return new SentryDefinition(entity, slots, placement, health, damage, range, fireRate, lifetime, leash, cooldown, projectile,
                projectileSpeed, attack);
        }
    }

    /** Why a placement failed, or the placed sentry. */
    public record Placed(@Nullable Mob sentry, @Nullable String failure) {
        public boolean ok() {
            return sentry != null;
        }

        static Placed fail(String why) {
            return new Placed(null, why);
        }
    }

    public static final DataTable<SentryDefinition> SENTRIES = DataTables
        .register(Identifier.fromNamespaceAndPath("fundamentum", "sentries"), "fundamentum/sentries", SentryDefinition::fromJson)
        .keyField("item");

    private static final Map<Item, SentryDefinition> CODE = new ConcurrentHashMap<>();
    /** Persistent-data key on a sentry: the id of the item that placed it. Sentries also carry the minion owner key. */
    private static final String SENTRY = "fundamentum:sentry";
    private static final String OWNER = "fundamentum:summon_owner";
    private static final Map<UUID, SentryRoster<Mob>> ROSTERS = new ConcurrentHashMap<>();
    private static final Map<Mob, Live> LIVE = new HashMap<>();
    /** Owners that are fake players (a machine placing sentries, say): they are not in the player list. */
    private static final Map<UUID, ServerPlayer> FAKE_OWNERS = new ConcurrentHashMap<>();

    private static final class Live {
        final SentryDefinition def;
        final UUID owner;
        final long expires;
        long nextShot;

        Live(SentryDefinition def, UUID owner, long expires, long nextShot) {
            this.def = def;
            this.owner = owner;
            this.expires = expires;
            this.nextShot = nextShot;
        }
    }

    /** Declares a sentry item in code (with an attack hook, say); a data file for the item overrides the stats. */
    public static void register(Item item, SentryDefinition def) {
        CODE.put(item, def);
    }

    /** The definition for a sentry item, or null. */
    public static @Nullable SentryDefinition def(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        SentryDefinition data = SENTRIES.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        SentryDefinition code = CODE.get(stack.getItem());
        if (data == null) {
            return code;
        }
        return code != null && data.attack() == null ? data.withAttack(code.attack()) : data;
    }

    /** True for a turret placed as a sentry. */
    public static boolean isSentry(Entity entity) {
        return entity.getPersistentData().contains(SENTRY);
    }

    /** A player's sentries now, oldest first. */
    public static List<Mob> sentries(Player player) {
        SentryRoster<Mob> roster = ROSTERS.get(player.getUUID());
        return roster == null ? List.of() : roster.sentries();
    }

    /** Sentry slots a player uses now. */
    public static double used(Player player) {
        SentryRoster<Mob> roster = ROSTERS.get(player.getUUID());
        return roster == null ? 0 : roster.used();
    }

    /** Sends away all of a player's sentries. */
    public static void dismiss(Player player) {
        SentryRoster<Mob> roster = ROSTERS.remove(player.getUUID());
        if (roster != null) {
            roster.clear().forEach(Sentries::vanish);
        }
    }

    /**
     * Places the sentry {@code def} for {@code owner} against the {@code face} of block {@code clicked}. Returns the
     * sentry, or why it failed: "placement" (wrong face), "blocked" (no room), "no_slots" (costs more than the limit)
     * or "entity" (unknown or not a creature).
     */
    public static Placed place(ServerPlayer owner, Identifier item, SentryDefinition def, BlockPos clicked, Direction face) {
        if (!def.placement().allows(face)) {
            return Placed.fail("placement");
        }
        double limit = owner.getAttributeValue(SENTRY_SLOTS);
        if (!SentryRoster.fits(def.slots(), limit)) {
            return Placed.fail("no_slots");
        }
        ServerLevel level = owner.level();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(def.entity()).orElse(null);
        if (type == null) {
            Unknown.warn("entity", def.entity(), "sentry");
            return Placed.fail("entity");
        }
        Entity entity = type.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return Placed.fail("entity");
        }
        BlockPos at = clicked.relative(face);
        double x = at.getX() + 0.5, y = at.getY(), z = at.getZ() + 0.5;
        if (face == Direction.DOWN) {
            y = at.getY() + 1.0 - mob.getBbHeight();
        } else if (face.getAxis().isHorizontal()) {
            double half = mob.getBbWidth() / 2.0;
            x = at.getX() + 0.5 - face.getStepX() * (0.5 - half);
            z = at.getZ() + 0.5 - face.getStepZ() * (0.5 - half);
            y = at.getY() + Math.max(0.0, 0.5 - mob.getBbHeight() / 2.0);
        }
        float yaw = face.getAxis().isHorizontal() ? face.toYRot() : Mth.wrapDegrees(owner.getYRot() + 180.0F);
        mob.snapTo(x, y, z, yaw, 0.0F);
        if (!level.noCollision(mob)) {
            mob.discard();
            return Placed.fail("blocked");
        }
        mob.getPersistentData().putString(OWNER, owner.getUUID().toString());
        mob.getPersistentData().putString(SENTRY, item.toString());
        mob.setNoAi(true);
        mob.setNoGravity(face != Direction.UP);
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
        set(mob, Attributes.MAX_HEALTH, def.health());
        set(mob, Attributes.KNOCKBACK_RESISTANCE, 1.0);
        mob.setHealth(mob.getMaxHealth());
        mob.setYHeadRot(yaw);
        mob.yBodyRot = yaw;
        Summons.SPAWNING.set(true);
        try {
            level.addFreshEntity(mob);
        } finally {
            Summons.SPAWNING.set(false);
        }
        long now = level.getGameTime();
        LIVE.put(mob, new Live(def, owner.getUUID(), def.lifetime() > 0 ? now + def.lifetime() : Long.MAX_VALUE, now + def.interval()));
        if (owner instanceof FakePlayer) {
            FAKE_OWNERS.put(owner.getUUID(), owner);
        }
        SentryRoster<Mob> roster = ROSTERS.computeIfAbsent(owner.getUUID(), k -> new SentryRoster<>());
        roster.add(mob, def.slots(), limit).forEach(Sentries::vanish);
        poof(mob);
        return new Placed(mob, null);
    }

    // ------------------------------------------------------------------------------------------------ the turrets at work
    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (var it = ROSTERS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            if (owner == null) {
                ServerPlayer fake = FAKE_OWNERS.get(entry.getKey());
                owner = fake != null && !fake.isRemoved() ? fake : null;
            }
            SentryRoster<Mob> roster = entry.getValue();
            if (owner == null) {
                roster.clear().forEach(Sentries::vanish);
                FAKE_OWNERS.remove(entry.getKey());
                it.remove();
                continue;
            }
            roster.trim(owner.getAttributeValue(SENTRY_SLOTS)).forEach(Sentries::vanish);
            for (Mob sentry : roster.sentries()) {
                Live live = LIVE.get(sentry);
                long now = sentry.level().getGameTime();
                if (live == null || !sentry.isAlive() || sentry.isRemoved() || sentry.level() != owner.level() || now >= live.expires
                    || live.def.leash() > 0 && sentry.distanceToSqr(owner) > live.def.leash() * live.def.leash()) {
                    roster.remove(sentry);
                    vanish(sentry);
                    continue;
                }
                if (now >= live.nextShot) {
                    LivingEntity target = target(owner, sentry, live.def.range());
                    if (target != null) {
                        face(sentry, target);
                        if (attack(sentry, target, owner, live.def)) {
                            live.nextShot = now + live.def.interval();
                        }
                    }
                }
            }
            if (roster.isEmpty()) {
                it.remove();
            }
        }
        LIVE.keySet().removeIf(m -> m.isRemoved() || !m.isAlive());
    }

    /** The player's whip mark, whoever they fight, or the nearest hostile creature in range and in sight. */
    static @Nullable LivingEntity target(ServerPlayer owner, Mob sentry, double range) {
        double r2 = range * range;
        LivingEntity marked = WhipTags.focus(owner);
        if (fightable(owner, sentry, marked, r2)) {
            return marked;
        }
        for (LivingEntity t : new LivingEntity[] {owner.getLastHurtByMob(), owner.getLastHurtMob()}) {
            if (fightable(owner, sentry, t, r2)) {
                return t;
            }
        }
        LivingEntity best = null;
        double bestDist = r2;
        for (Mob m : sentry.level().getEntitiesOfClass(Mob.class, sentry.getBoundingBox().inflate(range), e -> e instanceof Enemy)) {
            double d = m.distanceToSqr(sentry);
            if (d < bestDist && fightable(owner, sentry, m, r2)) {
                best = m;
                bestDist = d;
            }
        }
        return best;
    }

    private static boolean fightable(ServerPlayer owner, Mob sentry, @Nullable LivingEntity t, double r2) {
        return t != null && t.isAlive() && t != sentry && !Summons.allies(owner, t) && t.level() == sentry.level() && t.distanceToSqr(sentry) <= r2
            && !(t instanceof Player p && (p.isCreative() || p.isSpectator())) && sentry.hasLineOfSight(t);
    }

    private static void face(Mob sentry, LivingEntity target) {
        double dx = target.getX() - sentry.getX(), dz = target.getZ() - sentry.getZ();
        double dy = target.getEyeY() - sentry.getEyeY();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        sentry.setYRot(yaw);
        sentry.setYHeadRot(yaw);
        sentry.yBodyRot = yaw;
        sentry.setXRot(pitch);
    }

    private static boolean attack(Mob sentry, LivingEntity target, ServerPlayer owner, SentryDefinition def) {
        if (def.attack() != null) {
            return def.attack().fire(sentry, target, owner, def);
        }
        ServerLevel level = (ServerLevel) sentry.level();
        if (def.projectile() != null) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(def.projectile()).orElse(null);
            Entity shot = type == null ? null : type.create(level, EntitySpawnReason.TRIGGERED);
            if (!(shot instanceof Projectile projectile)) {
                if (type == null) {
                    Unknown.warn("entity", def.projectile(), "sentry projectile");
                }
                if (shot != null) {
                    shot.discard();
                }
                return false;
            }
            Vec3 from = sentry.getEyePosition();
            projectile.snapTo(from.x, from.y - 0.1, from.z, sentry.getYRot(), sentry.getXRot());
            projectile.setOwner(sentry);
            double dx = target.getX() - from.x, dz = target.getZ() - from.z;
            double dy = target.getY(0.3333) - (from.y - 0.1);  // aimed like a skeleton: a third of the way up, lifted for the drop
            if (projectile instanceof AbstractArrow arrow) {
                dy += Math.sqrt(dx * dx + dz * dz) * 0.2;
                arrow.setBaseDamage(def.damage() / def.projectileSpeed());
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            }
            projectile.shoot(dx, dy, dz, (float) def.projectileSpeed(), 1.0F);
            level.addFreshEntity(projectile);
            return true;
        }
        if (sentry instanceof RangedAttackMob ranged) {
            ranged.performRangedAttack(target, 1.0F);
            return true;
        }
        if (sentry.distanceToSqr(target) <= 9.0) {
            sentry.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
            target.hurtServer(level, sentry.damageSources().mobAttack(sentry), (float) def.damage());
            return true;
        }
        return false;
    }

    private static void set(LivingEntity entity, Holder<Attribute> attribute, @Nullable Double value) {
        AttributeInstance instance = value == null ? null : entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static void vanish(Mob mob) {
        LIVE.remove(mob);
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
        NeoForge.EVENT_BUS.addListener(Sentries::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> dismiss(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> dismiss(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            ROSTERS.clear();
            LIVE.clear();
            FAKE_OWNERS.clear();
        });
        // Using a sentry item on a block places its turret.
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            if (!(e.getEntity() instanceof ServerPlayer player) || e.getFace() == null) {
                return;
            }
            ItemStack stack = e.getItemStack();
            SentryDefinition def = def(stack);
            if (def == null || player.getCooldowns().isOnCooldown(stack)) {
                return;
            }
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            Placed placed = place(player, BuiltInRegistries.ITEM.getKey(stack.getItem()), def, e.getPos(), e.getFace());
            if (placed.ok()) {
                player.swing(e.getHand(), net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
                if (def.cooldown() > 0) {
                    player.getCooldowns().addCooldown(stack, def.cooldown());
                }
            } else {
                player.sendOverlayMessage(Component.translatable("message.fundamentum.sentry." + placed.failure()));
            }
        });
    }

    /** Every sentry out now, by player (for tests and tools). */
    static Map<UUID, List<Mob>> snapshot() {
        Map<UUID, List<Mob>> out = new HashMap<>();
        ROSTERS.forEach((k, v) -> out.put(k, new ArrayList<>(v.sentries())));
        return out;
    }

    private Sentries() {}
}
