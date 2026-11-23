package com.fundamentum.whip;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A whip's own behaviour, on top of Fundamentum's swing and hits. Every method runs on the server and all are optional.
 * For one hit the order is {@code cordHit}, then {@code tipHit} (tip hits only), then the damage, then {@code landed};
 * a hook may change {@link WhipHit#damage} or {@link WhipHit#cancel()} the hit before the damage.
 */
public interface WhipHooks {
    WhipHooks NONE = new WhipHooks() {};

    /** The click: the swing starts (the windup). */
    default void swingStart(ServerPlayer player, ItemStack stack, WhipDefinition whip) {}

    /** The swing is over (or was cut short: the item left the hand), after {@code targets} hits. */
    default void swingEnd(ServerPlayer player, ItemStack stack, WhipDefinition whip, int targets) {}

    /** Any part of the cord touched a target it may hit. */
    default void cordHit(WhipHit hit) {}

    /** The tip touched a target (after {@link #cordHit}); {@link WhipHit#crit} says whether it was cracking. */
    default void tipHit(WhipHit hit) {}

    /** After the damage: {@code hurt} is whether the target took it. On-hit marks and effects go here. */
    default void landed(WhipHit hit, boolean hurt) {}

    /** The tip passed the crack speed for the first time this swing, at {@code tip}. */
    default void crack(ServerPlayer player, ItemStack stack, WhipDefinition whip, Vec3 tip) {}

    /**
     * The tip snapped against the block at {@code pos} (at least {@link WhipCord#STRIKE_SPEED} of the crack speed), the
     * first time this swing. The block's hit sound has played; break, light or mark it here.
     */
    default void struckBlock(ServerPlayer player, ItemStack stack, WhipDefinition whip, BlockPos pos, Vec3 tip) {}

    /**
     * A charged slam ({@link WhipDefinition.Slam}) came down at {@code at} with {@code strength} (0..1) and hurt
     * {@code hit}; their damage, knockback and the debris are done (1.22.0).
     */
    default void slam(ServerPlayer player, ItemStack stack, WhipDefinition whip, Vec3 at, float strength, List<LivingEntity> hit) {}
}
