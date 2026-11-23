package com.fundamentum.multiblock.client;

import com.fundamentum.multiblock.MultiblockPattern;
import com.fundamentum.multiblock.Multiblocks;
import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A multiblock pattern as a list of blocks for drawing as real block models in the guide and the recipe viewer: each
 * non-air cell with the first block its part accepts, placed facing north with the controller at 0, 0, 0. Kept per
 * pattern until the pattern table reloads. The same file on every version; the drawing is per version.
 */
public final class MultiblockScene {
    private MultiblockScene() { }

    /** One block of a scene. */
    public record Cell(BlockPos pos, BlockState state) { }

    /**
     * A pattern's blocks and its bounds (min and max corner, inclusive). Compared by identity, so a drawer can keep what
     * it built from a scene until it gets a different one.
     */
    public static final class Scene {
        private final List<Cell> cells;
        private final BlockPos min;
        private final BlockPos max;

        Scene(List<Cell> cells, BlockPos min, BlockPos max) {
            this.cells = cells;
            this.min = min;
            this.max = max;
        }

        public List<Cell> cells() { return this.cells; }
        public BlockPos min() { return this.min; }
        public BlockPos max() { return this.max; }

        /** The longest side in blocks, at least 1. */
        public int size() {
            return Math.max(1, Math.max(this.max.getX() - this.min.getX(), Math.max(this.max.getY() - this.min.getY(), this.max.getZ() - this.min.getZ())) + 1);
        }
    }

    private static final Map<MultiblockPattern, Optional<Scene>> SCENES = new WeakHashMap<>();

    /** The scene for a pattern, or null when there is no such pattern or it has no blocks to show. */
    public static Scene of(FId id) {
        MultiblockPattern p = Multiblocks.pattern(id);
        return p == null ? null : of(p);
    }

    /** The scene for a pattern; a reload makes new pattern objects, so old scenes drop out by themselves. */
    public static synchronized Scene of(MultiblockPattern p) {
        return SCENES.computeIfAbsent(p, k -> Optional.ofNullable(build(k))).orElse(null);
    }

    private static Scene build(MultiblockPattern p) {
        List<Cell> cells = new ArrayList<>();
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        p.forEach(BlockPos.ZERO, Direction.NORTH, false, (pos, part) -> {
            if (part.air()) return;
            BlockState s;
            try {
                s = part.display(Rotation.NONE, Mirror.NONE);
            } catch (RuntimeException e) {
                return;
            }
            if (s == null || s.isAir()) return;
            cells.add(new Cell(pos.immutable(), s));
            b[0] = Math.min(b[0], pos.getX());
            b[1] = Math.min(b[1], pos.getY());
            b[2] = Math.min(b[2], pos.getZ());
            b[3] = Math.max(b[3], pos.getX());
            b[4] = Math.max(b[4], pos.getY());
            b[5] = Math.max(b[5], pos.getZ());
        });
        if (cells.isEmpty()) return null;
        return new Scene(List.copyOf(cells), new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5]));
    }
}
