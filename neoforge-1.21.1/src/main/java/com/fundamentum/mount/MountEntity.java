package com.fundamentum.mount;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A base for summoned mounts: the rider steers it like a horse (WASD, the horse jump bar on space), or, when it flies
 * (no gravity), it flies where the rider looks while moving forward. Its speed, jump and flight speed are its
 * movement_speed, jump_strength and flying_speed attributes, which {@link Mounts} sets from the mount's data. It is
 * never saved with the world, never despawns, takes no fall damage and has no behaviour of its own. Mods subclass it for their
 * own mounts and register a renderer; nothing else is needed. Don't build the mount's {@code EntityType} with
 * {@code noSave()}: the server refuses to seat a player on an entity whose type can't be saved, so the player never
 * boards (Fundamentum discards summoned mounts itself instead).
 */
public class MountEntity extends PathfinderMob implements PlayerRideableJumping {
    private float jumpPending;

    public MountEntity(EntityType<? extends MountEntity> type, Level level) {
        super(type, level);
    }

    /** Default attributes; register them for your type in EntityAttributeCreationEvent (add or override as needed). */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.25)
            .add(Attributes.JUMP_STRENGTH, 0.7)
            .add(Attributes.FLYING_SPEED, 0.05)
            .add(Attributes.STEP_HEIGHT, 1.0);
    }

    /** True when it flies rather than walks (set by {@link Mounts} from the mount's data). */
    public boolean isFlying() {
        return isNoGravity();
    }

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player player ? player : null;
    }

    @Override
    protected void tickRidden(Player controller, Vec3 riddenInput) {
        super.tickRidden(controller, riddenInput);
        setRot(controller.getYRot(), controller.getXRot() * 0.5F);
        yRotO = yBodyRot = yHeadRot = getYRot();
        if (jumpPending > 0 && onGround() && !isFlying()) {
            Vec3 v = getDeltaMovement();
            setDeltaMovement(v.x, getAttributeValue(Attributes.JUMP_STRENGTH) * jumpPending * getBlockJumpFactor(), v.z);
            hasImpulse = true;
        }
        if (onGround()) {
            jumpPending = 0;
        }
    }

    @Override
    protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
        float sideways = controller.xxa * 0.5F;
        float forward = controller.zza;
        if (isFlying()) {
            // Fly where the rider looks: pitch turns forward input into climb or dive.
            float pitch = controller.getXRot() * ((float) Math.PI / 180.0F);
            return new Vec3(sideways, -Math.sin(pitch) * forward, Math.cos(pitch) * forward);
        }
        return new Vec3(sideways, 0.0, forward <= 0.0F ? forward * 0.25F : forward);
    }

    @Override
    protected float getRiddenSpeed(Player controller) {
        return (float) getAttributeValue(isFlying() ? Attributes.FLYING_SPEED : Attributes.MOVEMENT_SPEED);
    }

    @Override
    public void travel(Vec3 input) {
        if (isFlying() && isVehicle()) {
            moveRelative(getSpeed(), input);
            move(MoverType.SELF, getDeltaMovement());
            setDeltaMovement(getDeltaMovement().scale(0.91));
        } else {
            super.travel(input);
        }
    }

    @Override
    public void onPlayerJump(int jumpAmount) {
        // The horse jump bar: a full charge (90) jumps at full strength, less scales from 0.4.
        jumpPending = jumpAmount >= 90 ? 1.0F : 0.4F + 0.4F * Math.max(0, jumpAmount) / 90.0F;
    }

    @Override
    public boolean canJump() {
        return !isFlying();
    }

    @Override
    public void handleStartJump(int jumpScale) {
    }

    @Override
    public void handleStopJump() {
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float damageModifier, DamageSource damageSource) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
    }
}
