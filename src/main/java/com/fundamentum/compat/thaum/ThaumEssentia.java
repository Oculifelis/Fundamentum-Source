package com.fundamentum.compat.thaum;

import com.fundamentum.port.FId;
import com.fundamentum.transfer.Essentia;
import com.fundamentum.transfer.EssentiaBuffer;
import com.fundamentum.transfer.EssentiaStore;
import com.leclowndu93150.thaumaturge.api.aspect.AspectInstance;
import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import com.leclowndu93150.thaumaturge.api.essentia.EssentiaCapabilities;
import com.leclowndu93150.thaumaturge.api.essentia.IEssentiaStorage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Joins Fundamentum's essentia to Thaumaturge's. Loaded only when Thaumaturge is installed: Fundamentum calls
 * {@link #init} behind a {@code ModList} check, so no other class touches Thaumaturge. Two directions:
 * Thaumaturge's jars and tubes are found by {@link Essentia#find} through a bridge, and every block exposed with
 * {@link Essentia#expose} is offered to Thaumaturge as essentia storage.
 */
public final class ThaumEssentia {
    private ThaumEssentia() { }

    public static void init(IEventBus modBus) {
        Essentia.addBridge((level, pos, side) -> {
            IEssentiaStorage s = level.getCapability(EssentiaCapabilities.STORAGE, pos, side);
            if (s == null || s instanceof Adapter) return null; // our own buffers are found before bridges
            return new View(s, level.registryAccess());
        });
        modBus.addListener(RegisterCapabilitiesEvent.class, ThaumEssentia::register);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(RegisterCapabilitiesEvent event) {
        for (Essentia.Exposed e : Essentia.exposed()) {
            BlockEntityType type = e.blockEntityType();
            if (type == null) continue;
            BiFunction<BlockEntity, Direction, EssentiaBuffer> p = e.provider();
            event.registerBlockEntity(EssentiaCapabilities.STORAGE, type, (be, side) -> {
                BlockEntity b = (BlockEntity) be;
                EssentiaBuffer buf = p.apply(b, (Direction) side);
                return buf == null ? null : new Adapter(buf, b.getLevel());
            });
        }
    }

    /**
     * For Fundamentum's transfer self-test: reads an exposed block through Thaumaturge's capability, checks that an
     * simulated insert leaves it unchanged, and moves essentia back out through the bridge. Returns what failed.
     */
    public static List<String> selfCheck(Level level, net.minecraft.core.BlockPos pos) {
        List<String> fails = new ArrayList<>();
        IEssentiaStorage s = level.getCapability(EssentiaCapabilities.STORAGE, pos, null);
        if (!(s instanceof Adapter)) {
            fails.add("no Thaumaturge storage on an exposed block");
            return fails;
        }
        FId id = FId.of("thaumaturge", "ignis");
        Holder<IAspect> ignis = holderOf(level.registryAccess(), id);
        if (ignis == null) {
            fails.add("ignis is not in the aspect registry");
            return fails;
        }
        int before = s.amount(ignis);
        if (s.insert(ignis, 5, true) != 5) fails.add("an insert through Thaumaturge put in the wrong amount");
        if (s.amount(ignis) != before) fails.add("a simulated insert left essentia behind");
        long rev = s.contentRevision();
        s.insert(ignis, 5, false);
        if (s.amount(ignis) != before + 5) fails.add("an insert did not stay");
        View v = new View(s, level.registryAccess());
        if (v.amount(id) != before + 5) fails.add("the bridge view misreads Thaumaturge contents");
        if (v.extract(id, 5, true) != 5 || s.amount(ignis) != before + 5) fails.add("a simulated bridge extract changed something");
        if (v.extract(id, 5, false) != 5 || s.amount(ignis) != before) fails.add("a bridge extract took the wrong amount");
        if (s.contentRevision() == rev) fails.add("the content revision did not change");
        return fails;
    }

    // ---- ids and holders ----

    static FId idOf(Holder<IAspect> h) {
        return h.unwrapKey().map(k -> FId.from(k.location())).orElse(null);
    }

    static Holder<IAspect> holderOf(RegistryAccess access, FId id) {
        if (access == null || id == null) return null;
        Optional<? extends Holder<IAspect>> h = access.lookupOrThrow(IAspect.REGISTRY_KEY)
            .get(ResourceKey.create(IAspect.REGISTRY_KEY, id.vanilla()));
        return h.isPresent() ? h.get() : null;
    }

    // ---- Thaumaturge storage seen as our store ----

    private record View(IEssentiaStorage s, RegistryAccess access) implements EssentiaStore {
        @Override
        public Map<FId, Integer> contents() {
            Map<FId, Integer> m = new LinkedHashMap<>();
            for (AspectInstance i : s.contents().entries()) {
                FId id = idOf(i.aspect());
                if (id != null && i.amount() > 0) m.merge(id, i.amount(), Integer::sum);
            }
            return m;
        }

        @Override
        public int insert(FId aspect, int amount, boolean simulate) {
            Holder<IAspect> h = holderOf(access, aspect);
            if (h == null || amount <= 0) return 0;
            return s.insert(h, amount, simulate);
        }

        @Override
        public int extract(FId aspect, int amount, boolean simulate) {
            Holder<IAspect> h = holderOf(access, aspect);
            if (h == null || amount <= 0) return 0;
            return s.extract(h, amount, simulate);
        }
    }

    // ---- our buffer seen as Thaumaturge storage ----

    private static final class Adapter implements IEssentiaStorage {
        private final EssentiaBuffer b;
        private final Level level;
        Adapter(EssentiaBuffer b, Level level) {
            this.b = b;
            this.level = level;
        }

        @Override
        public AspectList contents() {
            RegistryAccess access = level == null ? null : level.registryAccess();
            List<AspectInstance> list = new ArrayList<>();
            b.contents().forEach((id, n) -> {
                Holder<IAspect> h = holderOf(access, id);
                if (h != null && n > 0) list.add(new AspectInstance(h, n));
            });
            return list.isEmpty() ? AspectList.EMPTY : AspectList.ofEntries(list);
        }

        @Override
        public int insert(Holder<IAspect> aspect, int amount, boolean simulate) {
            if (amount < 0) throw new IllegalArgumentException("negative amount");
            FId id = idOf(aspect);
            return id == null ? 0 : b.insert(id, amount, simulate);
        }

        @Override
        public int extract(Holder<IAspect> aspect, int amount, boolean simulate) {
            if (amount < 0) throw new IllegalArgumentException("negative amount");
            FId id = idOf(aspect);
            return id == null ? 0 : b.extract(id, amount, simulate);
        }

        @Override
        public long contentRevision() {
            return b.revision();
        }
    }
}
