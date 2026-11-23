package com.fundamentum.flail;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Code a mod runs when its flail hits. Register per item with {@link Flails#hooks}, or by name with {@link Flails#hook}
 * and list the name in a flail file's {@code "on_hit"} (so one hook serves any number of flails, from data). Every method
 * runs on the server; all are optional.
 */
public interface FlailHooks {
    /** A head (or the chain) is about to hit: change {@link FlailHit#damage} or {@link FlailHit#cancel} it. Fundamentum's own hits only. */
    default void hit(FlailHit hit) {}

    /**
     * A hit with the flail landed for {@code dealt} damage: Fundamentum's own swings, spins and throws, and also Better
     * Combat's or vanilla's left click with the flail in hand.
     */
    default void landed(ServerPlayer player, ItemStack stack, FlailSpec spec, LivingEntity target, float dealt) {}

    /** A ground slam came down at {@code at}, hitting {@code hit}. */
    default void slam(ServerPlayer player, ItemStack stack, FlailSpec spec, Vec3 at, List<LivingEntity> hit) {}

    /** A head struck a block at {@code speed} blocks a tick. */
    default void block(ServerPlayer player, ItemStack stack, FlailSpec spec, BlockPos pos, double speed) {}
}
