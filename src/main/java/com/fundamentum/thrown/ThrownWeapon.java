package com.fundamentum.thrown;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.whip.WhipVec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A thrown weapon in flight. The server runs its {@link ThrownFlight} each tick. A returning throw flies home to the
 * thrower and is caught into the slot it left ({@link ThrownWeapons#giveBack}), or drops when the thrower is gone, too
 * far or it times out. Any other throw falls after its last pierce and then sticks in the block it lands on (anyone can
 * walk into it to pick it up), drops as an item, or breaks. Each creature is hit at most once per leg; a hit deals the
 * stats' damage through the item's damage enchantments and is a thrower-class hit (its weapon is the thrown item).
 * Extend it for your own effects through the hooks ({@link #onThrown}, {@link #flightTick}, {@link #onStrike},
 * {@link #onRicochet}, {@link #onGlance}, {@link #onTurnHome}, {@link #onCaught}, {@link #onStick}, {@link #onBreak});
 * the generic {@link ThrownWeapons#THROWN_WEAPON} is this class as it is.
 */
public class ThrownWeapon extends ThrowableItemProjectile {
    /** Furthest the thrower of a returning throw may be before it drops. */
    public static final double LEASH = 64;
    /** How far a ricochet looks for its next enemy. */
    public static final double RICOCHET_REACH = 8;

    private static final EntityDataAccessor<Boolean> STUCK = SynchedEntityData.defineId(ThrownWeapon.class, EntityDataSerializers.BOOLEAN);

    private ThrownFlight flight = new ThrownFlight(ThrownStats.DEFAULT);
    private final Set<Integer> hit = new HashSet<>();
    /** The inventory slot it was thrown from; a returning throw goes back there. */
    public int origin = ThrownWeapons.NO_SLOT;
    /** Whether it can be picked up or caught as an item (false for creative throws: they just vanish). */
    public boolean pickup = true;

    public ThrownWeapon(EntityType<? extends ThrownWeapon> type, Level level) {
        super(type, level);
    }

    /** Sets it off from {@code thrower}'s eyes along their look, with these stats. {@link ThrownWeapons#throwFrom} calls it. */
    public void launch(LivingEntity thrower, ItemStack stack, ThrownStats stats, int slot) {
        setOwner(thrower);
        setItem(stack);
        flight = new ThrownFlight(stats);
        origin = slot;
        setPos(thrower.getX(), thrower.getEyeY() - 0.1, thrower.getZ());
        // Straight along the look at the stat speed. shootFromRotation would add the thrower's own movement, so a
        // running or falling throw would fly faster, slower or off line.
        Vec3 look = thrower.getViewVector(1.0F);
        shoot(look.x, look.y, look.z, (float) stats.throwSpeed(), 0.0F);
    }

    public ThrownFlight flight() {
        return flight;
    }

    public ThrownStats stats() {
        return flight.stats();
    }

    public boolean returning() {
        return flight.returning();
    }

    /** Stuck in a block (synced, so the client stops its spin). */
    public boolean isStuck() {
        return entityData.get(STUCK);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STUCK, false);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.STICK;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.0;
    }

    /** The thrown item is the weapon, so damage classes, elements and soul fire read it. */
    @Override
    public @Nullable ItemStack getWeaponItem() {
        return getItem();
    }

    @Override
    public void tick() {
        if (!level().isClientSide() && level() instanceof ServerLevel level) {
            Entity owner = getOwner();
            if (flight.expired() || stats().returning() && (owner == null || !owner.isAlive() || owner.level() != level
                || owner.distanceToSqr(this) > LEASH * LEASH)) {
                drop(level);
                return;
            }
            if (flight.stuck()) {
                if (level.noCollision(this, getBoundingBox().inflate(0.06))) {
                    // the block it was in is gone
                    flight.unstick();
                    entityData.set(STUCK, false);
                } else {
                    setDeltaMovement(Vec3.ZERO);
                    return;
                }
            }
            WhipVec home = owner == null ? vec(position()) : vec(owner.getEyePosition());
            WhipVec pos = vec(position());
            if (owner != null && flight.caught(pos, home)) {
                caught(level, owner);
                return;
            }
            boolean wasReturning = flight.returning();
            WhipVec v = flight.tick(pos, vec(getDeltaMovement()), home);
            if (flight.returning() && !wasReturning) {
                hit.clear();
                onTurnHome(level);
            }
            noPhysics = flight.returning();
            setDeltaMovement(v.x(), v.y(), v.z());
            flightTick(level);
        } else if (isStuck()) {
            return;
        }
        super.tick();
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && target != getOwner() && !hit.contains(target.getId()) && flight.canHit();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity target = result.getEntity();
        if (!(level() instanceof ServerLevel level) || hit.contains(target.getId()) || !flight.canHit()) {
            return;
        }
        hit.add(target.getId());
        Entity owner = getOwner();
        DamageSource source = damageSources().thrown(this, owner == null ? this : owner);
        ItemStack weapon = getItem();
        float damage = EnchantmentHelper.modifyDamage(level, weapon, target, source, (float) stats().hitDamage());
        // fundamentum:thrown_weapon_hit, cancelled: no damage
        boolean struck = !FEvents.post(new Events.ThrownWeaponHit(owner instanceof LivingEntity thrower ? thrower : null, target, weapon));
        boolean hurt = struck && target.hurt(source, damage);
        if (hurt) {
            EnchantmentHelper.doPostAttackEffectsWithItemSource(level, target, source, weapon);
        }
        onStrike(level, target, hurt);
        ThrownFlight.HitResult then = flight.onHit();
        if (then == ThrownFlight.HitResult.RICOCHET) {
            LivingEntity next = nextEnemy(level, owner);
            if (next != null) {
                Vec3 to = next.getBoundingBox().getCenter().subtract(position());
                flight.ricochet(to.length());
                setDeltaMovement(to.normalize().scale(stats().throwSpeed()));
                onRicochet(level, next);
            } else {
                then = flight.noRicochetTarget();
            }
        }
        switch (then) {
            case TURN_HOME -> {
                hit.clear();
                hit.add(target.getId());
                onTurnHome(level);
            }
            case FALL -> setDeltaMovement(getDeltaMovement().scale(ThrownFlight.DEFLECT));
            case BREAK -> breakApart(level);
            default -> { }
        }
    }

    /** The nearest living enemy in ricochet reach not yet hit this leg, or null. */
    protected @Nullable LivingEntity nextEnemy(ServerLevel level, @Nullable Entity owner) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(RICOCHET_REACH),
            e -> e.isAlive() && e != owner && e instanceof Enemy && !hit.contains(e.getId()))) {
            double d = e.distanceToSqr(this);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        switch (flight.onBlock()) {
            case GLANCE -> {
                Direction face = result.getDirection();
                WhipVec v = ThrownFlight.reflect(vec(getDeltaMovement()), new WhipVec(face.getStepX(), face.getStepY(), face.getStepZ()));
                setDeltaMovement(v.x(), v.y(), v.z());
                onGlance(level, result);
            }
            case TURN_HOME -> {
                hit.clear();
                onTurnHome(level);
            }
            case STICK -> {
                Vec3 move = getDeltaMovement();
                if (move.lengthSqr() > 1.0E-6) {
                    setYRot((float) (Mth.atan2(move.x, move.z) * Mth.RAD_TO_DEG));
                    setXRot((float) (Mth.atan2(move.y, move.horizontalDistance()) * Mth.RAD_TO_DEG));
                }
                Vec3 at = result.getLocation().subtract(move.normalize().scale(0.05));
                setPos(at.x, at.y, at.z);
                setDeltaMovement(Vec3.ZERO);
                entityData.set(STUCK, true);
                onStick(level, result);
            }
            case DROP -> drop(level);
            case BREAK -> breakApart(level);
            case NONE -> { }
        }
    }

    /** A stuck or spent throw is picked up by the player who walks into it. */
    @Override
    public void playerTouch(Player player) {
        if (level().isClientSide() || !(flight.stuck() || flight.phase() == ThrownFlight.Phase.FALLING && onGround())) {
            return;
        }
        if (!pickup) {
            discard();
        } else if (player.getInventory().add(getItem().copy())) {
            player.take(this, 1);
            discard();
        }
    }

    // hooks for subclasses (all server side)

    /** Just thrown (it is in the level). Plays the throw sound. */
    public void onThrown(ServerLevel level) {
        if (getOwner() instanceof LivingEntity thrower) {
            ThrownWeapons.throwSound(level, thrower);
        }
    }

    /** Every server tick in flight, after the flight has moved on: trails, sounds, effects on the ground below. */
    protected void flightTick(ServerLevel level) {
        if (stats().returning() && tickCount % 4 == 0) {
            level.playSound(null, blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.4F, 1.6F);
        }
    }

    /** A creature was struck ({@code hurt}: whether the damage went through). */
    protected void onStrike(ServerLevel level, Entity target, boolean hurt) { }

    /** A ricochet just aimed it at {@code next}. */
    protected void onRicochet(ServerLevel level, LivingEntity next) { }

    /** It glanced off a block. */
    protected void onGlance(ServerLevel level, BlockHitResult result) { }

    /** A returning throw just turned home. */
    protected void onTurnHome(ServerLevel level) { }

    /** It just stuck in a block. Plays the trident's thunk. */
    protected void onStick(ServerLevel level, BlockHitResult result) {
        level.playSound(null, blockPosition(), SoundEvents.TRIDENT_HIT_GROUND, SoundSource.PLAYERS, 0.8F, 1.2F);
    }

    /** It is breaking on a hit (before it is removed): a molotov's fire, a bomb's blast. Shows the item's pieces. */
    protected void onBreak(ServerLevel level) {
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, getItem()), getX(), getY(), getZ(), 8, 0.1, 0.1, 0.1, 0.08);
        level.playSound(null, blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 0.8F, 1.0F);
    }

    /** A returning throw was caught (before it is removed). */
    protected void onCaught(ServerLevel level, Entity owner) {
        level.playSound(null, owner.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 1.2F);
    }

    /** Home: into the slot it left (or the first free one); dropped at the thrower's feet when the inventory is full. */
    protected void caught(ServerLevel level, Entity owner) {
        if (!(owner instanceof Player player) || !pickup || player.hasInfiniteMaterials() || ThrownWeapons.giveBack(player, getItem(), origin)) {
            onCaught(level, owner);
            discard();
        } else {
            drop(level);
        }
    }

    /** Drops it as an item where it is (creative throws just vanish). */
    protected void drop(ServerLevel level) {
        if (pickup) {
            level.addFreshEntity(new ItemEntity(level, getX(), getY(), getZ(), getItem()));
        }
        discard();
    }

    /** Breaks it: {@link #onBreak}, then gone. */
    protected void breakApart(ServerLevel level) {
        onBreak(level);
        discard();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag output) {
        super.addAdditionalSaveData(output);
        output.putString("flight", flight.phase().name());
        output.putInt("age", flight.age());
        output.putInt("origin", origin);
        output.putBoolean("pickup", pickup);
        output.putDouble("travelled", flight.travelled());
        output.putInt("pierced", flight.pierced());
        output.putInt("bounces_used", flight.bouncesUsed());
        output.putInt("home_ticks", flight.homeTicks());
        output.putString("stats", stats().toJson().toString());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag input) {
        super.readAdditionalSaveData(input);
        String json = input.getString("stats");
        ThrownStats stats = ThrownWeapons.stats(getItem(), null);
        if (!json.isEmpty()) {
            stats = stats.withJson(parse(json));
        }
        flight = new ThrownFlight(stats);
        flight.restore(input.getString("flight"), input.getDouble("travelled"), input.getInt("pierced"),
            input.getInt("bounces_used"), input.getInt("age"), input.getInt("home_ticks"));
        origin = input.contains("origin") ? input.getInt("origin") : ThrownWeapons.NO_SLOT;
        pickup = !input.contains("pickup") || input.getBoolean("pickup");
        entityData.set(STUCK, flight.stuck());
    }

    private static JsonObject parse(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    protected static WhipVec vec(Vec3 v) {
        return new WhipVec(v.x, v.y, v.z);
    }
}
