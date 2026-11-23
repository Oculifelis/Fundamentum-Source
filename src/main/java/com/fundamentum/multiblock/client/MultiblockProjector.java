package com.fundamentum.multiblock.client;

import com.fundamentum.client.ClientRefresh;
import com.fundamentum.multiblock.MultiblockPattern;
import com.fundamentum.multiblock.Multiblocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ghost preview: while {@link Multiblocks#projection()} is set, every cell that is not built yet is listed in
 * {@link #ghosts()} for the version's renderer ({@code GhostRenderer}) to draw as a see-through block. The list is
 * worked out again only when the projection changes or a block inside it changes ({@link #blockChanged}, called from a
 * client level mixin), plus a slow safety recheck ({@link ClientRefresh}). The projection ends by itself once the
 * structure is complete or the player changes level. Called each client tick from {@code MultiblockClientTick}. The
 * same file on every version.
 */
public final class MultiblockProjector {
    private MultiblockProjector() { }

    /** What a ghost cell shows. */
    public enum Kind {
        /** Nothing there yet: the wanted block, see-through. */
        MISSING,
        /** Another block is in the way: the wanted block, tinted red. */
        WRONG,
        /** Something must be cleared away: a faint red box. */
        CLEAR
    }

    /** One cell to draw. {@code expected} is null for {@link Kind#CLEAR}. */
    public record Ghost(BlockPos pos, BlockState expected, Kind kind) { }

    private static final int MAX_GHOSTS = 512;
    private static final double RANGE = 64;
    private static volatile List<Ghost> ghosts = List.of();
    private static Multiblocks.Projection shown;
    private static Level shownIn;
    private static int minX, minY, minZ, maxX, maxY, maxZ;
    private static volatile boolean dirty;
    private static int epoch;
    private static int checks;

    /** The cells to draw now (an empty list when nothing is projected). */
    public static List<Ghost> ghosts() {
        return ghosts;
    }

    /** How many times the cells were worked out, for tests and profiling. */
    public static int checks() {
        return checks;
    }

    /** A block changed on the client: the cells are worked out again on the next tick if it is inside the projection. */
    public static void blockChanged(BlockPos pos) {
        if (shown != null && pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
            dirty = true;
        }
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Multiblocks.Projection p = Multiblocks.projection();
        Level level = mc.level;
        Player player = mc.player;
        if (p == null || level == null || player == null) {
            shownIn = null;
            shown = null;
            ghosts = List.of();
            return;
        }
        if (shownIn != null && shownIn != level) {
            Multiblocks.project(null);
            shownIn = null;
            shown = null;
            ghosts = List.of();
            return;
        }
        shownIn = level;
        MultiblockPattern pattern = Multiblocks.pattern(p.id());
        if (pattern == null) return;
        if (!p.equals(shown)) {
            shown = p;
            bounds(pattern, p);
            dirty = true;
        }
        if (!dirty && epoch == ClientRefresh.epoch()) return;
        dirty = false;
        epoch = ClientRefresh.epoch();
        checks++;
        var missing = Multiblocks.missing(level, p.id(), p.controller(), p.facing(), p.mirrored());
        if (missing.isEmpty()) {
            Multiblocks.project(null);
            shown = null;
            ghosts = List.of();
            return;
        }
        List<Ghost> out = new ArrayList<>();
        for (Multiblocks.Missing m : missing) {
            BlockPos pos = m.pos();
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > RANGE * RANGE) continue;
            BlockState want = m.expected();
            if (want == null || want.isAir()) out.add(new Ghost(pos, null, Kind.CLEAR));
            else out.add(new Ghost(pos, want, level.getBlockState(pos).isAir() ? Kind.MISSING : Kind.WRONG));
            if (out.size() >= MAX_GHOSTS) break;
        }
        ghosts = List.copyOf(out);
    }

    private static void bounds(MultiblockPattern pattern, Multiblocks.Projection p) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        pattern.forEach(p.controller(), p.facing(), p.mirrored(), (pos, part) -> {
            b[0] = Math.min(b[0], pos.getX());
            b[1] = Math.min(b[1], pos.getY());
            b[2] = Math.min(b[2], pos.getZ());
            b[3] = Math.max(b[3], pos.getX());
            b[4] = Math.max(b[4], pos.getY());
            b[5] = Math.max(b[5], pos.getZ());
        });
        minX = b[0];
        minY = b[1];
        minZ = b[2];
        maxX = b[3];
        maxY = b[4];
        maxZ = b[5];
    }
}
