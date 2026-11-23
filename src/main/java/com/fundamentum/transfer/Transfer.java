package com.fundamentum.transfer;

import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.Timers;
import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FTransfer;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Energy and fluid transfer over the loader's own capabilities, one call on every version. Read any block's energy or
 * fluids with {@link #energy} and {@link #fluids} (null when it has none on that side), move between stores with
 * {@link #moveEnergy} and {@link #moveFluid}, feed or drain all six neighbours with {@link #pushEnergy},
 * {@link #pullEnergy}, {@link #pushFluid} and {@link #pullFluid}. Expose your own {@link EnergyBuffer}, {@link FluidTank}
 * or {@link FluidTanks} with the {@code expose} calls while the mod loads; other mods then see them as normal loader
 * energy and fluid handlers. {@link #track} keeps tanks synced to players who can see them, for the tank widget: a tank
 * is sent when it changes and when a player starts receiving its chunk, with a slow full comparison as a safety net
 * (the {@code fundamentum:performance} config's {@code transfer_resync_seconds}). Item-to-fluid
 * declarations live in {@link ItemFluids}.
 */
public final class Transfer {
    private Transfer() { }

    /** What a client knows about a synced tank. */
    public record TankView(Fluid fluid, int amount, int capacity) {
        public static final TankView EMPTY = new TankView(Fluids.EMPTY, 0, 0);

        public float fraction() {
            return capacity <= 0 ? 0 : Math.min(1f, (float) amount / capacity);
        }

        public boolean empty() {
            return amount <= 0 || fluid == Fluids.EMPTY;
        }
    }

    /** One tank's contents for clients. {@code dimension} only tells levels apart. */
    public record TankSync(String dimension, BlockPos pos, int index, FluidTank.Contents contents, int capacity) {
        public static final FStreamCodec<TankSync> CODEC = FStreamCodec.composite(
            FStreamCodec.STRING, TankSync::dimension, FStreamCodec.BLOCK_POS, TankSync::pos, FStreamCodec.INT, TankSync::index,
            FluidTank.Contents.STREAM_CODEC, TankSync::contents, FStreamCodec.INT, TankSync::capacity, TankSync::new);
    }

    public static final FId TANK_SYNC = FId.of("fundamentum", "tank_sync");
    /** How far away players get tank updates. */
    public static final int SYNC_RANGE = 64;
    /** Ticks between tank sync checks. */
    public static final int SYNC_INTERVAL = 10;

    private static final class Tracked {
        final FluidTank[] tanks;
        final FluidTank.Contents[] sent;
        final int[] sentCapacity;
        Set<UUID> seen = new HashSet<>();

        Tracked(FluidTank[] tanks) {
            this.tanks = tanks;
            this.sent = new FluidTank.Contents[tanks.length];
            this.sentCapacity = new int[tanks.length];
        }
    }

    private static final Map<BlockEntity, Tracked> TRACKED = new WeakHashMap<>();
    private static final Map<BlockPos, Map<Integer, TankView>> CLIENT = new ConcurrentHashMap<>();
    private static volatile String clientDimension = "";
    private static int ticks;
    private static boolean initialised;
    /** Tracked block entities whose tanks changed since the last send. */
    private static final Set<BlockEntity> DIRTY = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static ConfigValue<Integer> safetyNet;

    /** Called once while Fundamentum loads (after the port layer's {@code FTransfer.init}). */
    public static void init() {
        if (initialised) return;
        initialised = true;
        FNet.register(TANK_SYNC, TankSync.class, TankSync.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> receive(msg));
        FLife.serverTick(Transfer::tick);
        FLife.chunkWatched(Transfer::chunkWatched);
        safetyNet = Timers.seconds("transfer_resync_seconds", 30);
        FLife.serverStopped(s -> {
            synchronized (TRACKED) {
                TRACKED.clear();
                DIRTY.clear();
            }
            ticks = 0;
        });
        ItemFluids.init();
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("transfer")
                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(Transfer::info)))));
    }

    // ---- reading other blocks ----

    /** The energy a block exposes on a side ({@code null} side: its inside view), or null when it has none. */
    public static EnergyStore energy(Level level, BlockPos pos, Direction side) {
        return level == null || pos == null ? null : FTransfer.energy(level, pos, side);
    }

    /** The fluids a block exposes on a side ({@code null} side: its inside view), or null when it has none. */
    public static FluidStore fluids(Level level, BlockPos pos, Direction side) {
        return level == null || pos == null ? null : FTransfer.fluids(level, pos, side);
    }

    // ---- moving ----

    /** Moves up to {@code max} energy; returns how much moved. */
    public static int moveEnergy(EnergyStore from, EnergyStore to, int max) {
        if (from == null || to == null || from == to || max <= 0) return 0;
        int can = from.extract(max, true);
        if (can <= 0) return 0;
        int fits = to.insert(can, true);
        if (fits <= 0) return 0;
        int out = from.extract(fits, false);
        int in = to.insert(out, false);
        if (in < out) from.insert(out - in, false);
        return in;
    }

    /** Moves up to {@code max} millibuckets of the first fluid that fits; returns how much moved. */
    public static int moveFluid(FluidStore from, FluidStore to, int max) {
        if (from == null || to == null || from == to || max <= 0) return 0;
        for (int i = 0; i < from.tanks(); i++) {
            Fluid f = from.fluid(i);
            if (f == Fluids.EMPTY || from.amount(i) <= 0) continue;
            int n = moveFluid(from, to, f, max);
            if (n > 0) return n;
        }
        return 0;
    }

    /** Moves up to {@code max} millibuckets of one fluid; returns how much moved. */
    public static int moveFluid(FluidStore from, FluidStore to, Fluid fluid, int max) {
        if (from == null || to == null || from == to || fluid == null || fluid == Fluids.EMPTY || max <= 0) return 0;
        int can = from.extract(fluid, max, true);
        if (can <= 0) return 0;
        int fits = to.insert(fluid, can, true);
        if (fits <= 0) return 0;
        int out = from.extract(fluid, fits, false);
        int in = to.insert(fluid, out, false);
        if (in < out) from.insert(fluid, out - in, false);
        return in;
    }

    /** Sends up to {@code max} energy in total out of {@code from} into the blocks around {@code pos}, in side order. */
    public static int pushEnergy(Level level, BlockPos pos, EnergyStore from, int max) {
        int left = max;
        for (Direction d : Direction.values()) {
            if (left <= 0) break;
            left -= moveEnergy(from, energy(level, pos.relative(d), d.getOpposite()), left);
        }
        return max - left;
    }

    /** Draws up to {@code max} energy in total from the blocks around {@code pos} into {@code to}. */
    public static int pullEnergy(Level level, BlockPos pos, EnergyStore to, int max) {
        int left = max;
        for (Direction d : Direction.values()) {
            if (left <= 0) break;
            left -= moveEnergy(energy(level, pos.relative(d), d.getOpposite()), to, left);
        }
        return max - left;
    }

    /** Sends up to {@code max} millibuckets in total out of {@code from} into the blocks around {@code pos}. */
    public static int pushFluid(Level level, BlockPos pos, FluidStore from, int max) {
        int left = max;
        for (Direction d : Direction.values()) {
            if (left <= 0) break;
            left -= moveFluid(from, fluids(level, pos.relative(d), d.getOpposite()), left);
        }
        return max - left;
    }

    /** Draws up to {@code max} millibuckets in total from the blocks around {@code pos} into {@code to}. */
    public static int pullFluid(Level level, BlockPos pos, FluidStore to, int max) {
        int left = max;
        for (Direction d : Direction.values()) {
            if (left <= 0) break;
            left -= moveFluid(fluids(level, pos.relative(d), d.getOpposite()), to, left);
        }
        return max - left;
    }

    // ---- exposing your own ----

    /**
     * Makes a block entity type offer energy through the loader's capability. {@code provider} gets the block entity
     * and the side asked about ({@code null}: no particular side) and returns its buffer, or null to offer nothing there.
     * Call while the mod loads.
     */
    @SuppressWarnings("unchecked")
    public static <B extends BlockEntity> void exposeEnergy(Supplier<? extends BlockEntityType<B>> type,
                                                            BiFunction<B, Direction, EnergyBuffer> provider) {
        FTransfer.exposeEnergy(type::get, (BiFunction<BlockEntity, Direction, EnergyBuffer>) (BiFunction<?, ?, ?>) provider);
    }

    /** {@link #exposeEnergy} for a block entity type named by id (one from another mod or vanilla). */
    public static void exposeEnergy(FId type, BiFunction<BlockEntity, Direction, EnergyBuffer> provider) {
        FTransfer.exposeEnergy(() -> FTransfer.blockEntityType(type), provider);
    }

    /** Makes a block entity type offer one tank through the loader's fluid capability. */
    @SuppressWarnings("unchecked")
    public static <B extends BlockEntity> void exposeTank(Supplier<? extends BlockEntityType<B>> type,
                                                          BiFunction<B, Direction, FluidTank> provider) {
        BiFunction<BlockEntity, Direction, FluidTank> p = (BiFunction<BlockEntity, Direction, FluidTank>) (BiFunction<?, ?, ?>) provider;
        FTransfer.exposeFluids(type::get, (be, side) -> {
            FluidTank t = p.apply(be, side);
            return t == null ? null : FluidTanks.of(t);
        });
    }

    /** {@link #exposeTank} for a block entity type named by id. */
    public static void exposeTank(FId type, BiFunction<BlockEntity, Direction, FluidTank> provider) {
        FTransfer.exposeFluids(() -> FTransfer.blockEntityType(type), (be, side) -> {
            FluidTank t = provider.apply(be, side);
            return t == null ? null : FluidTanks.of(t);
        });
    }

    /** Makes a block entity type offer several tanks through the loader's fluid capability. */
    @SuppressWarnings("unchecked")
    public static <B extends BlockEntity> void exposeFluids(Supplier<? extends BlockEntityType<B>> type,
                                                            BiFunction<B, Direction, FluidTanks> provider) {
        FTransfer.exposeFluids(type::get, (BiFunction<BlockEntity, Direction, FluidTanks>) (BiFunction<?, ?, ?>) provider);
    }

    /** {@link #exposeFluids} for a block entity type named by id. */
    public static void exposeFluids(FId type, BiFunction<BlockEntity, Direction, FluidTanks> provider) {
        FTransfer.exposeFluids(() -> FTransfer.blockEntityType(type), provider);
    }

    // ---- tank sync ----

    /**
     * Keeps these tanks of a block entity synced to players within {@link #SYNC_RANGE} blocks: sent when a tank changes
     * (at most every {@link #SYNC_INTERVAL} ticks) and when a player starts receiving the chunk. Call once on the server, for
     * example in {@code onLoad}; tracking ends when the block entity is removed. Clients read them with
     * {@link #clientTank}.
     */
    public static void track(BlockEntity be, FluidTank... tanks) {
        if (be == null || tanks.length == 0) return;
        Tracked t = new Tracked(tanks.clone());
        synchronized (TRACKED) {
            Tracked old = TRACKED.put(be, t);
            if (old != null) {
                for (FluidTank tank : old.tanks) tank.syncListener = null;
            }
            DIRTY.add(be);
        }
        Runnable mark = () -> {
            synchronized (TRACKED) {
                if (TRACKED.get(be) == t) DIRTY.add(be);
            }
        };
        for (FluidTank tank : tanks) tank.syncListener = mark;
    }

    /** Stops syncing a block entity's tanks. */
    public static void untrack(BlockEntity be) {
        synchronized (TRACKED) {
            Tracked old = TRACKED.remove(be);
            DIRTY.remove(be);
            if (old != null) {
                for (FluidTank tank : old.tanks) tank.syncListener = null;
            }
        }
    }

    /** True while a block entity's tanks are synced. */
    public static boolean tracked(BlockEntity be) {
        synchronized (TRACKED) {
            return TRACKED.containsKey(be);
        }
    }

    /** Client side: the last synced state of tank {@code index} at a position, {@link TankView#EMPTY} if unknown. */
    public static TankView clientTank(BlockPos pos, int index) {
        Map<Integer, TankView> m = CLIENT.get(pos);
        TankView v = m == null ? null : m.get(index);
        return v == null ? TankView.EMPTY : v;
    }

    /** Applies a sync message as the client would. Public for tests. */
    public static void receive(TankSync msg) {
        if (!msg.dimension().equals(clientDimension)) {
            CLIENT.clear();
            clientDimension = msg.dimension();
        }
        CLIENT.computeIfAbsent(msg.pos().immutable(), k -> new ConcurrentHashMap<>())
            .put(msg.index(), new TankView(msg.contents().toFluid(), msg.contents().amount(), msg.capacity()));
    }

    /** The messages a tracked block entity would send now (all tanks). Public for tests. */
    public static List<TankSync> syncMessages(BlockEntity be) {
        List<TankSync> out = new ArrayList<>();
        Tracked t;
        synchronized (TRACKED) {
            t = TRACKED.get(be);
        }
        if (t == null || be.getLevel() == null) return out;
        for (int i = 0; i < t.tanks.length; i++) out.add(message(be, i, t.tanks[i]));
        return out;
    }

    private static TankSync message(BlockEntity be, int i, FluidTank tank) {
        return new TankSync(be.getLevel().dimension().toString(), be.getBlockPos(), i, tank.contents(), tank.capacity());
    }

    /** A player can now see a chunk: send them every tracked tank in it. */
    private static void chunkWatched(ServerPlayer player, ServerLevel level, int cx, int cz) {
        List<Map.Entry<BlockEntity, Tracked>> here = new ArrayList<>();
        synchronized (TRACKED) {
            if (TRACKED.isEmpty()) return;
            for (Map.Entry<BlockEntity, Tracked> e : TRACKED.entrySet()) {
                BlockEntity be = e.getKey();
                if (be.getLevel() == level && be.getBlockPos().getX() >> 4 == cx && be.getBlockPos().getZ() >> 4 == cz) here.add(e);
            }
        }
        for (Map.Entry<BlockEntity, Tracked> e : here) {
            BlockEntity be = e.getKey();
            for (int i = 0; i < e.getValue().tanks.length; i++) FNet.toPlayer(player, message(be, i, e.getValue().tanks[i]));
            e.getValue().seen.add(player.getUUID());
        }
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        int full = Timers.ticks(safetyNet, 30);
        boolean resync = full > 0 && ticks % full == 0;
        if (!resync && ticks % SYNC_INTERVAL != 0) return;
        List<Map.Entry<BlockEntity, Tracked>> entries = new ArrayList<>();
        synchronized (TRACKED) {
            if (TRACKED.isEmpty()) return;
            if (resync) {
                TRACKED.keySet().removeIf(be -> be.isRemoved() || be.getLevel() == null);
                entries.addAll(TRACKED.entrySet());
            } else {
                if (DIRTY.isEmpty()) return;
                for (BlockEntity be : DIRTY) {
                    Tracked t = TRACKED.get(be);
                    if (t != null) entries.add(Map.entry(be, t));
                }
            }
            DIRTY.clear();
        }
        for (Map.Entry<BlockEntity, Tracked> e : entries) {
            if (e.getKey().isRemoved()) {
                untrack(e.getKey());
                continue;
            }
            BlockEntity be = e.getKey();
            Tracked t = e.getValue();
            if (!(be.getLevel() instanceof ServerLevel level)) continue;
            BlockPos pos = be.getBlockPos();
            boolean[] changed = new boolean[t.tanks.length];
            for (int i = 0; i < t.tanks.length; i++) {
                FluidTank.Contents c = t.tanks[i].contents();
                changed[i] = !c.equals(t.sent[i]) || t.sentCapacity[i] != t.tanks[i].capacity();
                t.sent[i] = c;
                t.sentCapacity[i] = t.tanks[i].capacity();
            }
            Set<UUID> near = new HashSet<>();
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > (double) SYNC_RANGE * SYNC_RANGE) continue;
                near.add(p.getUUID());
                boolean fresh = !t.seen.contains(p.getUUID());
                for (int i = 0; i < t.tanks.length; i++) {
                    if (fresh || changed[i]) FNet.toPlayer(p, message(be, i, t.tanks[i]));
                }
            }
            t.seen = near;
        }
    }

    /** The tank widget's tooltip: the fluid's name (or "Empty") and "amount / capacity mB". */
    public static List<Component> tankTooltip(Fluid fluid, int amount, int capacity) {
        List<Component> lines = new ArrayList<>();
        if (fluid == null || fluid == Fluids.EMPTY || amount <= 0) {
            lines.add(Component.translatableWithFallback("fundamentum.tank.empty", "Empty"));
        } else {
            lines.add(fluid.defaultFluidState().createLegacyBlock().getBlock().getName());
        }
        lines.add(Component.literal(Math.max(0, amount) + " / " + capacity + " mB"));
        return lines;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        Level level = c.getSource().getLevel();
        EnergyStore e = energy(level, pos, null);
        FluidStore f = fluids(level, pos, null);
        if (e == null && f == null) {
            c.getSource().sendFailure(Component.literal("No energy or fluid storage at " + pos.toShortString()));
            return 0;
        }
        StringBuilder sb = new StringBuilder(pos.toShortString());
        if (e != null) sb.append(" energy ").append(e.stored()).append('/').append(e.capacity());
        if (f != null) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < f.tanks(); i++) {
                parts.add(FluidTank.Contents.of(f.fluid(i), f.amount(i)).fluid() + " " + f.amount(i) + "/" + f.capacity(i));
            }
            sb.append(" tanks ").append(parts);
        }
        String text = sb.toString();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
