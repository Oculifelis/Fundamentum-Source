package com.fundamentum.hook;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.fundamentum.state.BossStateMachine;
import org.jspecify.annotations.Nullable;

/**
 * A grappling hook in flight or latched onto a block (see {@link Hooks}). It flies straight, ignores creatures, and
 * comes back (is removed) past its range; latched, it stays until released, its block goes or its owner moves too far.
 */
public class HookEntity extends Projectile {
    /** Flying out; latched on a block; anchored in the air where its chain ran out (for {@link Hooks.Def#airSeconds}). */
    public enum State { FLYING, LATCHED, ANCHORED }


    private static final EntityDataAccessor<String> ITEM = SynchedEntityData.defineId(HookEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(HookEntity.class, EntityDataSerializers.INT);

    /** Only ever leaves FLYING: a latched or anchored hook stays put until it is removed. */
    private final BossStateMachine<State> state = new BossStateMachine<>(State.class, State.FLYING).allow((from, to) -> from == State.FLYING);
    private @Nullable BlockPos latchedOn;
    /** How long an anchor in the air holds ({@link Hooks.Def#airSeconds}), counted by the state's clock. */
    private int airTicks;

    public HookEntity(EntityType<? extends HookEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        state.syncTo(entityData, STATE);
    }

    HookEntity(Level level, Player owner, Identifier item) {
        this(Hooks.HOOK.get(), level);
        setOwner(owner);
        entityData.set(ITEM, item.toString());
        setPos(anchor(owner, 1.0F));
    }

    /**
     * Where a hook and its chain start: the middle of the owner's body (1.22.1; before, the eyes and a guessed hand
     * point in front of them, so in first person the chain began in mid-air).
     */
    public static Vec3 anchor(Player owner, float partialTicks) {
        return owner.getPosition(partialTicks).add(0, owner.getBbHeight() * 0.55, 0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ITEM, "");
        builder.define(STATE, State.FLYING.ordinal());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (STATE.equals(key) && level().isClientSide()) {
            state.onSynced(entityData.get(STATE));
        }
    }

    /** The hook item that fired it; its definition is {@code Hooks.HOOKS.get(item())}. */
    public @Nullable Identifier item() {
        return Identifier.tryParse(entityData.get(ITEM));
    }

    public Hooks.Def def() {
        Identifier item = item();
        Hooks.Def def = item == null ? null : Hooks.HOOKS.get(item);
        return def == null ? Hooks.Def.DEFAULT : def;
    }

    public State state() {
        return state.state();
    }

    /** Latched on a block or anchored in the air: no longer flying. */
    public boolean isLatched() {
        return !state.is(State.FLYING);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        if (!(getOwner() instanceof Player owner) || !owner.isAlive() || owner.level() != level()) {
            discard();
            return;
        }
        Hooks.Def def = def();
        double reach = distanceTo(owner);
        state.tick();
        switch (state.state()) {
            case ANCHORED -> {
                if (state.ticks() >= airTicks || reach > def.range() + 4) {
                    discard();
                } else if ((airTicks - state.ticks()) % 4 == 0 && level() instanceof ServerLevel server) {
                    server.sendParticles(ParticleTypes.CLOUD, getX(), getY(), getZ(), 1, 0.1, 0.1, 0.1, 0.01);
                }
                return;
            }
            case LATCHED -> {
                if (latchedOn == null || level().getBlockState(latchedOn).getCollisionShape(level(), latchedOn).isEmpty()
                    || reach > def.range() + 4) {
                    discard();
                }
                return;
            }
            case FLYING -> {}
        }
        if (reach > def.range()) {
            if (def.airSeconds() > 0) {
                // Anchors in the air where it ran out of chain.
                setDeltaMovement(Vec3.ZERO);
                airTicks = Math.max(1, (int) Math.round(def.airSeconds() * 20));
                state.to(State.ANCHORED);
                playSound(SoundEvents.CHAIN_PLACE, 1.0F, 1.8F);
            } else {
                discard();
            }
            return;
        }
        Vec3 from = position();
        Vec3 to = from.add(getDeltaMovement());
        BlockHitResult hit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (hit.getType() == HitResult.Type.BLOCK) {
            setPos(hit.getLocation());
            setDeltaMovement(Vec3.ZERO);
            latchedOn = hit.getBlockPos();
            state.to(State.LATCHED);
            playSound(SoundEvents.CHAIN_PLACE, 1.0F, 1.3F);
        } else {
            setPos(to);
        }
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    /** Never saved: a hook is removed when its chunk or owner goes, so it has no state to load. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96 * 96;
    }
}
