package com.fundamentum.multiblock;

import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.Timers;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Multiblock structures: shapes from {@code data/<ns>/fundamentum/multiblocks/*.json} (see {@link MultiblockPattern}),
 * matching around a controller block, formed and unformed events, a ghost preview on the client and a recipe viewer page.
 * <p>
 * {@link #match} only looks. {@link #check} also remembers the structure as formed and posts
 * {@code fundamentum:multiblock_formed} (cancel it to refuse). A formed structure is checked again when a block inside
 * it changes (found through an index by chunk, so a block change elsewhere costs almost nothing), after /reload, and by
 * a slow safety net (the {@code fundamentum:performance} config's {@code multiblocks_recheck_seconds}), and posts
 * {@code fundamentum:multiblock_unformed} once broken. The patterns travel to clients with the datapack. The same file
 * on every version.
 */
public final class Multiblocks {
    private Multiblocks() { }

    public static final String PATH = "fundamentum/multiblocks";
    public static final DataTable<MultiblockPattern> TABLE = DataTables.register(FId.of("fundamentum", "multiblocks").vanilla(), PATH,
        MultiblockPattern::parse).synced();

    private static final Map<ServerLevel, Map<BlockPos, Match>> FORMED = new WeakHashMap<>();
    /** Formed controllers by every chunk their structure touches, and each structure's box, all under FORMED's lock. */
    private static final Map<ServerLevel, Map<Long, List<BlockPos>>> BY_CHUNK = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, int[]>> BOXES = new WeakHashMap<>();
    /** Structures a block change touched, checked once at the end of the tick. */
    private static final Map<ServerLevel, Set<BlockPos>> DIRTY = new WeakHashMap<>();
    private static volatile boolean anyFormed;
    private static ConfigValue<Integer> safetyNet;
    private static volatile Projection projection;
    private static boolean initialised;

    /** A structure found around {@code controller}, built facing {@code facing}. */
    public record Match(FId id, MultiblockPattern pattern, Direction facing, boolean mirrored, BlockPos controller) {
        /** Every block position the structure checks (any-block cells left out). */
        public List<BlockPos> positions() {
            List<BlockPos> out = new ArrayList<>();
            this.pattern.forEach(this.controller, this.facing, this.mirrored, (pos, part) -> out.add(pos));
            return out;
        }

        public boolean contains(BlockPos pos) {
            return positions().contains(pos);
        }

        /** Whether the world still holds this structure. */
        public boolean stillThere(Level level) {
            return this.pattern.matches(level::getBlockState, this.controller, this.facing, this.mirrored);
        }
    }

    /** A cell of a structure that is not built yet, with the block a preview shows there. */
    public record Missing(BlockPos pos, BlockState expected) { }

    /** What the client projects. */
    public record Projection(FId id, BlockPos controller, Direction facing, boolean mirrored) { }

    /** Server to client: show a projection, or clear it (an empty structure). */
    public record Preview(String structure, BlockPos controller, Direction facing, boolean mirrored) {
        static final FStreamCodec<Preview> CODEC = FStreamCodec.composite(
            FStreamCodec.STRING, Preview::structure,
            FStreamCodec.BLOCK_POS, Preview::controller,
            FStreamCodec.enumOf(Direction.class), Preview::facing,
            FStreamCodec.BOOL, Preview::mirrored,
            Preview::new);
    }

    /** Wires the system up. Called once while Fundamentum loads. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        FNet.register(FId.of("fundamentum", "multiblock_preview"), Preview.class, Preview.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) ->
            projection = msg.structure().isEmpty() ? null : new Projection(FId.of(msg.structure()), msg.controller(), msg.facing(), msg.mirrored()));
        FLife.serverTick(Multiblocks::tick);
        FLife.blockChanged(Multiblocks::touched);
        FLife.dataReloaded(s -> {
            synchronized (FORMED) {
                FORMED.forEach((level, m) -> DIRTY.computeIfAbsent(level, l -> new LinkedHashSet<>()).addAll(m.keySet()));
            }
        });
        safetyNet = Timers.seconds("multiblocks_recheck_seconds", 60);
        FLife.serverStopped(s -> {
            synchronized (FORMED) {
                FORMED.clear();
                BY_CHUNK.clear();
                BOXES.clear();
                DIRTY.clear();
                anyFormed = false;
            }
        });
        MultiblockCommand.init();
        MultiblockViews.init();
    }

    // ---- patterns

    /** Every loaded pattern by id, sorted. On a client, the copy the server sent. */
    public static Map<FId, MultiblockPattern> patterns() {
        Map<FId, MultiblockPattern> out = new TreeMap<>();
        for (var e : TABLE.entries().entrySet()) out.put(FId.from(e.getKey()), e.getValue());
        return Collections.unmodifiableMap(out);
    }

    public static MultiblockPattern pattern(FId id) {
        return TABLE.get(id.vanilla());
    }

    // ---- matching

    /** The structure whose controller sits at {@code pos}, trying every pattern, facing and flip; null when none fits. */
    public static Match match(Level level, BlockPos pos) {
        for (Map.Entry<FId, MultiblockPattern> e : patterns().entrySet()) {
            Match m = match(level, pos, e.getKey(), e.getValue());
            if (m != null) return m;
        }
        return null;
    }

    /** Whether one pattern is built with its controller at {@code pos}; null when not (or no such pattern). */
    public static Match match(Level level, BlockPos pos, FId id) {
        MultiblockPattern p = pattern(id);
        return p == null ? null : match(level, pos, id, p);
    }

    private static Match match(Level level, BlockPos pos, FId id, MultiblockPattern p) {
        BlockState at = level.getBlockState(pos);
        for (boolean mirrored : p.mirrors() ? new boolean[] {false, true} : new boolean[] {false}) {
            for (Direction facing : p.facings()) {
                if (!p.controllerPart().accepts(at, MultiblockPattern.rotation(facing), MultiblockPattern.mirror(mirrored))) continue;
                if (p.matches(level::getBlockState, pos, facing, mirrored)) return new Match(id, p, facing, mirrored, pos.immutable());
            }
        }
        return null;
    }

    /** The cells of a pattern built at {@code controller} that do not hold what they should yet. */
    public static List<Missing> missing(Level level, FId id, BlockPos controller, Direction facing, boolean mirrored) {
        MultiblockPattern p = pattern(id);
        if (p == null) return List.of();
        var r = MultiblockPattern.rotation(facing);
        var m = MultiblockPattern.mirror(mirrored);
        List<Missing> out = new ArrayList<>();
        p.forEach(controller, facing, mirrored, (pos, part) -> {
            if (!part.accepts(level.getBlockState(pos), r, m)) out.add(new Missing(pos, part.display(r, m)));
        });
        return out;
    }

    /** Places a pattern's blocks (the first choice of each key entry), for tests and creative tools. Returns how many. */
    public static int build(Level level, FId id, BlockPos controller, Direction facing, boolean mirrored) {
        MultiblockPattern p = pattern(id);
        if (p == null) return 0;
        var r = MultiblockPattern.rotation(facing);
        var m = MultiblockPattern.mirror(mirrored);
        int[] n = {0};
        p.forEach(controller, facing, mirrored, (pos, part) -> {
            BlockState s = part.display(r, m);
            if (s != null) {
                level.setBlock(pos, s, 3);
                n[0]++;
            }
        });
        return n[0];
    }

    // ---- forming

    /**
     * Looks at {@code pos} and updates what is formed there: a new structure posts {@code multiblock_formed} (cancelled
     * means not formed, and null comes back); a formed structure that is gone posts {@code multiblock_unformed}.
     * Returns the formed structure, or null.
     */
    public static Match check(ServerLevel level, BlockPos pos) {
        BlockPos key = pos.immutable();
        Match found = match(level, key);
        Match old;
        synchronized (FORMED) {
            old = FORMED.computeIfAbsent(level, l -> new HashMap<>()).get(key);
        }
        if (old != null && found != null && old.id().equals(found.id()) && old.facing() == found.facing() && old.mirrored() == found.mirrored()) {
            return old;
        }
        if (old != null) unform(level, old);
        if (found == null) return null;
        if (FEvents.post(new Events.MultiblockFormed(level, key, found.id(), found.facing(), found.mirrored()))) return null;
        synchronized (FORMED) {
            Match before = FORMED.computeIfAbsent(level, l -> new HashMap<>()).put(key, found);
            if (before != null) unindex(level, before);
            index(level, found);
        }
        return found;
    }

    // ---- the chunk index (callers hold FORMED's lock)

    private static void index(ServerLevel level, Match m) {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        Set<Long> chunks = new HashSet<>();
        for (BlockPos p : m.positions()) {
            box[0] = Math.min(box[0], p.getX());
            box[1] = Math.min(box[1], p.getY());
            box[2] = Math.min(box[2], p.getZ());
            box[3] = Math.max(box[3], p.getX());
            box[4] = Math.max(box[4], p.getY());
            box[5] = Math.max(box[5], p.getZ());
            chunks.add(chunkKey(p.getX() >> 4, p.getZ() >> 4));
        }
        chunks.add(chunkKey(m.controller().getX() >> 4, m.controller().getZ() >> 4));
        Map<Long, List<BlockPos>> byChunk = BY_CHUNK.computeIfAbsent(level, l -> new HashMap<>());
        for (Long c : chunks) byChunk.computeIfAbsent(c, x -> new ArrayList<>(2)).add(m.controller());
        BOXES.computeIfAbsent(level, l -> new HashMap<>()).put(m.controller(), box);
        anyFormed = true;
    }

    private static void unindex(ServerLevel level, Match m) {
        Map<BlockPos, int[]> boxes = BOXES.get(level);
        int[] box = boxes == null ? null : boxes.remove(m.controller());
        Map<Long, List<BlockPos>> byChunk = BY_CHUNK.get(level);
        if (box == null || byChunk == null) return;
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                long key = chunkKey(cx, cz);
                List<BlockPos> list = byChunk.get(key);
                if (list == null) continue;
                list.remove(m.controller());
                if (list.isEmpty()) byChunk.remove(key);
            }
        }
        long own = chunkKey(m.controller().getX() >> 4, m.controller().getZ() >> 4);
        List<BlockPos> list = byChunk.get(own);
        if (list != null) {
            list.remove(m.controller());
            if (list.isEmpty()) byChunk.remove(own);
        }
    }

    private static long chunkKey(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    /** The controllers of formed structures whose box holds {@code pos}. */
    private static List<BlockPos> covering(ServerLevel level, BlockPos pos) {
        synchronized (FORMED) {
            Map<Long, List<BlockPos>> byChunk = BY_CHUNK.get(level);
            if (byChunk == null) return List.of();
            List<BlockPos> here = byChunk.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
            if (here == null) return List.of();
            Map<BlockPos, int[]> boxes = BOXES.get(level);
            List<BlockPos> out = new ArrayList<>(2);
            for (BlockPos c : here) {
                int[] b = boxes.get(c);
                if (b != null && pos.getX() >= b[0] && pos.getY() >= b[1] && pos.getZ() >= b[2]
                    && pos.getX() <= b[3] && pos.getY() <= b[4] && pos.getZ() <= b[5]) out.add(c);
            }
            return out;
        }
    }

    /** From the block change hook: remember the structures to check at the end of the tick. */
    private static void touched(ServerLevel level, BlockPos pos) {
        if (!anyFormed) return;
        List<BlockPos> hit = covering(level, pos);
        if (hit.isEmpty()) return;
        synchronized (FORMED) {
            DIRTY.computeIfAbsent(level, l -> new LinkedHashSet<>()).addAll(hit);
        }
    }

    /** The formed structure whose controller is at {@code pos}, or null. */
    public static Match formed(ServerLevel level, BlockPos pos) {
        synchronized (FORMED) {
            Map<BlockPos, Match> m = FORMED.get(level);
            return m == null ? null : m.get(pos);
        }
    }

    /** Every formed structure in a level. */
    public static Collection<Match> formed(ServerLevel level) {
        synchronized (FORMED) {
            Map<BlockPos, Match> m = FORMED.get(level);
            return m == null ? List.of() : List.copyOf(m.values());
        }
    }

    /** Forgets a formed structure without posting anything (its controller block was removed by its owner, say). */
    public static void forget(ServerLevel level, BlockPos controller) {
        synchronized (FORMED) {
            Map<BlockPos, Match> m = FORMED.get(level);
            Match gone = m == null ? null : m.remove(controller);
            if (gone != null) unindex(level, gone);
        }
    }

    /** Checks at once every formed structure that covers {@code pos}. Call it when a block changes. */
    public static void blockChanged(ServerLevel level, BlockPos pos) {
        for (BlockPos c : covering(level, pos)) {
            Match m = formed(level, c);
            if (m != null && m.contains(pos)) recheck(level, m);
        }
    }

    /** Checks every formed structure in a level whose chunk is loaded, and unforms the broken ones. */
    public static void recheck(ServerLevel level) {
        for (Match m : formed(level)) {
            if (level.isLoaded(m.controller())) recheck(level, m);
        }
    }

    private static void recheck(ServerLevel level, Match m) {
        MultiblockPattern now = pattern(m.id());
        if (now != null && now != m.pattern()) {
            // the datapack was reloaded: hold the structure to the new shape, same facing
            Match updated = new Match(m.id(), now, m.facing(), m.mirrored(), m.controller());
            if (updated.stillThere(level)) {
                synchronized (FORMED) {
                    Map<BlockPos, Match> map = FORMED.get(level);
                    if (map != null && map.get(m.controller()) == m) {
                        map.put(m.controller(), updated);
                        unindex(level, m);
                        index(level, updated);
                    }
                }
                return;
            }
        } else if (now != null && m.stillThere(level)) {
            return;
        }
        unform(level, m);
    }

    private static void unform(ServerLevel level, Match m) {
        synchronized (FORMED) {
            Map<BlockPos, Match> map = FORMED.get(level);
            Match gone = map == null ? null : map.remove(m.controller());
            if (gone == null) return;
            unindex(level, gone);
        }
        FEvents.post(new Events.MultiblockUnformed(level, m.controller(), m.id()));
    }

    private static void tick(MinecraftServer server) {
        if (!anyFormed) return;
        Map<ServerLevel, Set<BlockPos>> work = null;
        synchronized (FORMED) {
            if (!DIRTY.isEmpty()) {
                work = new HashMap<>(DIRTY);
                DIRTY.clear();
            }
        }
        if (work != null) {
            work.forEach((level, controllers) -> {
                for (BlockPos c : controllers) {
                    Match m = formed(level, c);
                    if (m != null && level.isLoaded(c)) recheck(level, m);
                }
            });
        }
        // the safety net: each structure checked once per interval, spread over it
        int period = Timers.ticks(safetyNet, 60);
        if (period <= 0) return;
        List<ServerLevel> levels;
        synchronized (FORMED) {
            levels = new ArrayList<>(FORMED.keySet());
        }
        for (ServerLevel level : levels) {
            List<Match> due = new ArrayList<>();
            synchronized (FORMED) {
                Map<BlockPos, Match> m = FORMED.get(level);
                if (m == null) continue;
                for (Match x : m.values()) {
                    if (Timers.due(period, server, x.controller())) due.add(x);
                }
            }
            for (Match x : due) {
                if (level.isLoaded(x.controller())) recheck(level, x);
            }
        }
    }

    // ---- preview

    /** Shows a ghost of a structure to one player, controller at {@code controller}. */
    public static void preview(ServerPlayer player, FId id, BlockPos controller, Direction facing, boolean mirrored) {
        FNet.toPlayer(player, new Preview(id.toString(), controller.immutable(), facing, mirrored));
    }

    public static void clearPreview(ServerPlayer player) {
        FNet.toPlayer(player, new Preview("", BlockPos.ZERO, Direction.NORTH, false));
    }

    /** What this client projects, or null. Client side. */
    public static Projection projection() {
        return projection;
    }

    /** Sets or clears (null) the client's projection directly, for client-only tools. */
    public static void project(Projection p) {
        projection = p;
    }
}
