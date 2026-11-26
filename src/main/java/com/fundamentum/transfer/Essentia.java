package com.fundamentum.transfer;

import com.fundamentum.port.FId;
import com.fundamentum.port.FTransfer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

/**
 * Essentia containers for any mod: one lookup that finds jars, tanks and carts whether they come from Fundamentum,
 * Thaumaturge or an addon. Fundamentum itself never needs Thaumaturge. When Thaumaturge is installed its containers are
 * reached through a bridge, and blocks exposed here are also offered to Thaumaturge's own pipes and tools.
 *
 * <p>Mods expose their own containers with {@link #expose} while loading, then find any container with {@link #find} and
 * move essentia with {@link #move}, {@link #push} and {@link #pull}.
 */
public final class Essentia {
    private Essentia() { }

    /** Finds an outside system's container at a block (Thaumaturge's, for one). */
    @FunctionalInterface
    public interface Bridge {
        @Nullable EssentiaStore find(Level level, BlockPos pos, @Nullable Direction side);
    }

    /** A block entity type whose containers Fundamentum exposes. */
    public record Exposed(FId type, BiFunction<BlockEntity, Direction, EssentiaBuffer> provider) {
        public @Nullable BlockEntityType<?> blockEntityType() {
            return FTransfer.blockEntityType(type);
        }
    }

    private static final List<Exposed> EXPOSED = new CopyOnWriteArrayList<>();
    private static final List<Bridge> BRIDGES = new CopyOnWriteArrayList<>();

    /**
     * Exposes a block entity type's essentia. The provider gets the block entity and the side asked from (null for no
     * side) and returns its buffer, or null to refuse that side. Call while the mod loads.
     */
    public static void expose(FId blockEntityType, BiFunction<BlockEntity, Direction, EssentiaBuffer> provider) {
        EXPOSED.add(new Exposed(blockEntityType, provider));
    }

    /** Every exposed type, for compat code that offers them to another system. */
    public static List<Exposed> exposed() {
        return List.copyOf(EXPOSED);
    }

    /** Adds a lookup for another system's containers. */
    public static void addBridge(Bridge bridge) {
        BRIDGES.add(bridge);
    }

    /** True when another essentia system (Thaumaturge) is connected. */
    public static boolean bridged() {
        return !BRIDGES.isEmpty();
    }

    /** The exposed buffer of a block entity, without asking bridges. */
    public static @Nullable EssentiaBuffer exposedAt(BlockEntity be, @Nullable Direction side) {
        for (Exposed e : EXPOSED) {
            if (be.getType() != e.blockEntityType()) continue;
            EssentiaBuffer b = e.provider().apply(be, side);
            if (b != null) return b;
        }
        return null;
    }

    /** The essentia container at a block, seen from {@code side} (null for no side), or null. */
    public static @Nullable EssentiaStore find(Level level, BlockPos pos, @Nullable Direction side) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            EssentiaBuffer b = exposedAt(be, side);
            if (b != null) return b;
        }
        for (Bridge bridge : BRIDGES) {
            EssentiaStore s = bridge.find(level, pos, side);
            if (s != null) return s;
        }
        return null;
    }

    /**
     * Moves up to {@code max} essentia. With an aspect, only that aspect; with null, every aspect {@code from} holds, in
     * its order, until {@code max} is reached. Returns how much moved.
     */
    public static int move(EssentiaStore from, EssentiaStore to, @Nullable FId aspect, int max) {
        if (max <= 0 || from == to) return 0;
        if (aspect != null) return moveOne(from, to, aspect, max);
        int moved = 0;
        for (FId a : from.contents().keySet()) {
            moved += moveOne(from, to, a, max - moved);
            if (moved >= max) break;
        }
        return moved;
    }

    private static int moveOne(EssentiaStore from, EssentiaStore to, FId aspect, int max) {
        int can = from.extract(aspect, max, true);
        if (can <= 0) return 0;
        int fits = to.insert(aspect, can, true);
        if (fits <= 0) return 0;
        int took = from.extract(aspect, fits, false);
        int put = to.insert(aspect, took, false);
        if (put < took) from.insert(aspect, took - put, false);
        return put;
    }

    /** Pushes from {@code from} into the containers around {@code pos}, in direction order. Returns how much moved. */
    public static int push(Level level, BlockPos pos, EssentiaStore from, @Nullable FId aspect, int max) {
        int moved = 0;
        for (Direction d : Direction.values()) {
            if (moved >= max) break;
            EssentiaStore to = find(level, pos.relative(d), d.getOpposite());
            if (to != null) moved += move(from, to, aspect, max - moved);
        }
        return moved;
    }

    /** Pulls into {@code to} from the containers around {@code pos}. Returns how much moved. */
    public static int pull(Level level, BlockPos pos, EssentiaStore to, @Nullable FId aspect, int max) {
        int moved = 0;
        for (Direction d : Direction.values()) {
            if (moved >= max) break;
            EssentiaStore from = find(level, pos.relative(d), d.getOpposite());
            if (from != null) moved += move(from, to, aspect, max - moved);
        }
        return moved;
    }
}
