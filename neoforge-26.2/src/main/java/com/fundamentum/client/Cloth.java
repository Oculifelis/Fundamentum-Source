package com.fundamentum.client;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Cape and cloth physics for worn things (1.25.3): a cape, veil or cloak drawn as a chain of model parts gets sway and
 * trail with speed, lift when falling, a stream behind an elytra glide, a gentle idle drift, lag down the chain and no
 * clipping into the body when crouching. It is driven by the wearer's vanilla cape inertia (the cloak position, bob and
 * walk distance that vanilla's cape layer reads), so it moves exactly when a cape would. The drawing stays in the mod.
 * <pre>{@code
 * // in a render state modifier (26.1.2: the layer gets no entity), once per frame:
 * Cloth.Pose pose = Cloth.pose(player, "mymod:cloak", 4, partialTick);
 * // in the layer: segment i is a child of segment i-1
 * part[i].xRot = pose.pitch()[i]; part[i].zRot = pose.roll()[i];
 * if (pose.chestArmour()) poseStack.translate(0, Cloth.ARMOUR_Y, Cloth.ARMOUR_Z);
 * }</pre>
 * Client thread only. The chains live as long as their entity (weakly held).
 */
public final class Cloth {
    /** Vanilla's cape shift over a chestplate, in blocks (CapeLayer: 0, -0.053125, 0.06875). */
    public static final float ARMOUR_Y = -0.053125F;
    public static final float ARMOUR_Z = 0.06875F;

    /**
     * One frame of a cloth chain: per segment the pitch and roll in radians, each relative to the segment above (the
     * first relative to the body), ready for {@code ModelPart.xRot}/{@code zRot}; and whether the wearer has a
     * chestplate on (shift the cloth out by {@link #ARMOUR_Y}/{@link #ARMOUR_Z} so it does not cut into it).
     */
    public record Pose(float[] pitch, float[] roll, boolean chestArmour) {}

    private static final Map<LivingEntity, Map<String, ClothSim>> SIMS = new WeakHashMap<>();

    /** The chain {@code id} (any name, one per kind of cloth) of this wearer with this many segments, stepped to now. */
    public static Pose pose(LivingEntity wearer, String id, int segments, float partialTick) {
        ClothSim sim = sim(wearer, id, segments);
        float[] pitch = new float[segments];
        float[] roll = new float[segments];
        for (int i = 0; i < segments; i++) {
            pitch[i] = sim.relativePitch(i, partialTick) * Mth.DEG_TO_RAD;
            roll[i] = sim.relativeRoll(i, partialTick) * Mth.DEG_TO_RAD;
        }
        return new Pose(pitch, roll, chestArmour(wearer));
    }

    /** The raw chain (absolute angles in degrees too), stepped to the wearer's current tick. */
    public static ClothSim sim(LivingEntity wearer, String id, int segments) {
        ClothSim sim = SIMS.computeIfAbsent(wearer, e -> new HashMap<>()).computeIfAbsent(id + "#" + segments, k -> new ClothSim(segments));
        sim.advanceTo(wearer.tickCount, input(wearer));
        return sim;
    }

    /** This tick's drive: vanilla's cape numbers (AvatarRenderer.extractCapeState) plus fall, glide, crouch and speed. */
    public static ClothSim.Input input(LivingEntity e) {
        double dx;
        double dy;
        double dz;
        float bob;
        float walk;
        if (e instanceof ClientAvatarEntity avatar) {
            ClientAvatarState s = avatar.avatarState();
            dx = s.getInterpolatedCloakX(1.0F) - e.getX();
            dy = s.getInterpolatedCloakY(1.0F) - e.getY();
            dz = s.getInterpolatedCloakZ(1.0F) - e.getZ();
            bob = s.getInterpolatedBob(1.0F);
            walk = s.getInterpolatedWalkDistance(1.0F);
        } else {
            // no cloak to read: a cloak that lags a tick behind the entity
            dx = (e.xo - e.getX()) * 3.0;
            dy = (e.yo - e.getY()) * 3.0;
            dz = (e.zo - e.getZ()) * 3.0;
            bob = 0.0F;
            walk = 0.0F;
        }
        float body = e.yBodyRot * Mth.DEG_TO_RAD;
        double fx = Mth.sin(body);
        double fz = -Mth.cos(body);
        float glideTicks = e.isFallFlying() ? e.getFallFlyingTicks() : 0.0F;
        float glide = Mth.clamp(glideTicks * glideTicks / 100.0F, 0.0F, 1.0F);
        float flap = Mth.clamp((float) dy * 10.0F, -6.0F, 32.0F) + Mth.sin(walk * 6.0F) * 32.0F * bob;
        float lean = Mth.clamp((float) (dx * fx + dz * fz) * 100.0F * (1.0F - glide), 0.0F, 150.0F);
        float lean2 = Mth.clamp((float) (dx * fz - dz * fx) * 100.0F, -20.0F, 20.0F);
        float fall = (float) Math.max(0.0, e.yo - e.getY());
        float speed = (float) Math.sqrt((e.getX() - e.xo) * (e.getX() - e.xo) + (e.getZ() - e.zo) * (e.getZ() - e.zo));
        return new ClothSim.Input(lean, lean2, flap, e.onGround() ? 0.0F : fall, glide, e.isCrouching(), speed, e.tickCount);
    }

    /** Whether a chestplate (anything drawn on the body from the chest slot) is on, as vanilla's cape checks. */
    public static boolean chestArmour(LivingEntity e) {
        Equippable equippable = e.getItemBySlot(EquipmentSlot.CHEST).get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST && equippable.assetId().isPresent()
            && !e.getItemBySlot(EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.ELYTRA);
    }

    private Cloth() {}
}
